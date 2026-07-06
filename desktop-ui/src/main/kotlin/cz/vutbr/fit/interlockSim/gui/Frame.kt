/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Main application window for Railway Interlocking Simulator.
 *
 * Provides dynamic layout that adapts based on context type:
 * - **Editing Mode** ([EditingContext]): StatusBar visible, ControlPanel hidden
 * - **Simulation Mode** ([SimulationContext]): ControlPanel and EventTimelinePanel visible, StatusBar remains visible (speed indicator shown)
 *
 * ## Layout Structure
 *
 * ### Editing Mode
 * ```
 * ┌─────────────────────────────────┐
 * │ MenuBar                         │
 * ├─────────────────────────────────┤
 * │ ToolBar                         │
 * ├─────────────────────────────────┤
 * │ RailwayNetGridCanvas            │
 * │ (scrollable)                    │
 * ├─────────────────────────────────┤
 * │ StatusBar                       │
 * └─────────────────────────────────┘
 * ```
 *
 * ### Simulation Mode
 * ```
 * ┌───────────────────────────────┬─────────────────────┐
 * │ MenuBar                       │                     │
 * ├───────────────────────────────┤                     │
 * │ ToolBar                       │                     │
 * ├───────────────────────────────┤                     │
 * │ ControlPanel (NEW - #205)     │                     │
 * │ [Time] [Status]               │ ConflictResolution  │
 * ├───────────────────────────────┤ Panel (EAST, #590)  │
 * │ RailwayNetGridCanvas          │ (visible only when  │
 * │ (animated, scrollable)        │  a conflict needs   │
 * ├───────────────────────────────┤  dispatcher input)  │
 * │ EventTimelinePanel (NEW)      │                     │
 * │ [Filters] [Event log...]      │                     │
 * ├───────────────────────────────┤                     │
 * │ StatusBar (speed indicator)   │                     │
 * └───────────────────────────────┴─────────────────────┘
 * ```
 *
 * ## Animation Integration (Issue #205)
 *
 * When a [SimulationContext] is set, the frame:
 * 1. Creates [EventTimelinePanel] (lazy, reused across simulations)
 * 2. Wires EventTimelinePanel to [RailwayNetGridCanvas] → [cz.vutbr.fit.interlockSim.gui.animation.AnimationController]
 * 3. Starts 10 Hz timer for [ControlPanel] time updates
 * 4. Shows ControlPanel and EventTimelinePanel (above StatusBar); StatusBar remains visible
 *
 * When an [EditingContext] is set, the frame:
 * 1. Hides ControlPanel and EventTimelinePanel
 * 2. Shows StatusBar for mouse position feedback
 * 3. Stops animation timer (cleanup)
 *
 * ## Thread Safety
 *
 * All public methods must be called from the Event Dispatch Thread (EDT).
 * Mode switching methods enforce EDT requirement with `require()` checks.
 *
 * @since 2006-2007
 * @see setContext
 * @see startSimulation
 * @see stopSimulation
 * @see switchToEditingMode
 * @see switchToSimulationMode
 */
class Frame : JFrame(PROGRAM_FULL_NAME) {
	val railwayNetGridCanvas: RailwayNetGridCanvas = RailwayNetGridCanvas()
	internal val statusBar: StatusBar = StatusBar()
	private val toolBar: ToolBar = ToolBar()

	// Animation UI components (Issue #205)
	private val controlPanel: ControlPanel = ControlPanel()
	internal val simulationControlPanel: SimulationControlPanel = SimulationControlPanel()
	private var eventTimelinePanel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel? = null
	private var animationUpdateTimer: Timer? = null

	// Path preview panel (Issue #596) – visible in editing mode
	private val pathPreviewPanel: PathPreviewPanel = PathPreviewPanel()

	// Warning log panel (Issue #616, Goal 3 SP6) – visible in simulation mode
	internal val warningPanel: WarningPanel = WarningPanel()

	// Conflict resolution side panel (Issue #590, Goal 9 SP5) – visible when a conflict is active
	internal val conflictResolutionPanel: ConflictResolutionPanel = ConflictResolutionPanel()

	// ── Collision Response settings (Goal 3 SP6) ──────────────────────────────

	/**
	 * When `true`, the simulation remains paused after a collision warning
	 * (the service always calls [SimulationRunner.requestPause]; this flag
	 * controls whether we immediately resume on the EDT).
	 * Defaults to `true` (auto-pause enabled).
	 *
	 * `@Volatile` because it is written on the EDT (via the menu toggle in [MenuBar]) and
	 * read on the simulation thread inside the `onCollisionWarning` listener registered in
	 * [startSimulation].
	 */
	@Volatile
	var autoPauseOnCriticalWarning: Boolean = true

	/**
	 * When `true`, the [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * is asked to halt the offending train on a
	 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning.BlockEntryViolation].
	 * Defaults to `false`.
	 */
	var autoHaltTrainOnViolation: Boolean = false
		set(value) {
			field = value
			applyAutoHaltSetting(value)
		}

	/**
	 * When `true`, a short audio beep is played for each CRITICAL collision warning.
	 * Defaults to `false`.
	 *
	 * `@Volatile` because it is written on the EDT (via the menu toggle in [MenuBar]) and
	 * read on the simulation thread inside the `onCollisionWarning` listener registered in
	 * [startSimulation].
	 */
	@Volatile
	var soundOnCriticalWarning: Boolean = false

	// South panel: always at BorderLayout.SOUTH; holds StatusBar and optionally EventTimelinePanel
	private val southPanel: JPanel =
		JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
		}

	// Simulation lifecycle delegated to SimulationController for testability (Issue #189)
	internal val simulationController: SimulationController =
		SimulationController(
			onStateChanged = { state ->
				runOnEdt {
					when (state) {
						SimulationController.SimulationStatus.RUNNING -> {
							toolBar.showSimulationControls()
							controlPanel.updateStatus(ControlPanel.SimulationStatus.RUNNING)
							controlPanel.setStopEnabled(true)
						}

						SimulationController.SimulationStatus.STOPPED -> {
							toolBar.hideSimulationControls()
							simulationControlPanel.runner = null
							controlPanel.setStopEnabled(false)
							controlPanel.updateStatus(ControlPanel.SimulationStatus.STOPPED)
						}
					}
				}
			},
			onSpeedChanged = { speed ->
				runOnEdt { statusBar.updateSpeedIndicator(speed) }
			}
		)
	private var currentSimulationContext: SimulationContext? = null

	/** Listener registered on the active runner for pause-state changes; removed on stop. */
	private var pausedListener: PropertyChangeListener? = null

	// Global keyboard shortcuts for simulation speed control (Phase 3.1, Issue #193)
	private val simulationKeyBindings: SimulationKeyBindings = SimulationKeyBindings(simulationController)

	/**
	 * Tracks modification state for unsaved changes warning.
	 */
	val modificationTracker: ModificationTracker =
		ModificationTracker { isDirty ->
			updateTitle()
		}

	init {
		setSize(1024, 768)
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE) // Handle close event manually
		setLayout(BorderLayout())
		jMenuBar = MenuBar()
		contentPane.add(JScrollPane(railwayNetGridCanvas), BorderLayout.CENTER)

		// Create north container with ToolBar + ControlPanel (Issue #205)
		val northContainer = JPanel()
		northContainer.layout = BoxLayout(northContainer, BoxLayout.PAGE_AXIS)
		northContainer.add(toolBar)
		controlPanel.isVisible = false // Initially hidden (shown only in simulation mode)
		northContainer.add(controlPanel)
		simulationControlPanel.isVisible = false // Initially hidden (shown only in simulation mode)
		northContainer.add(simulationControlPanel)
		contentPane.add(northContainer, BorderLayout.NORTH)

		// Route speed changes from SimulationControlPanel through SimulationController so
		// desiredSpeed stays in sync and is applied to the next simulation start.
		simulationControlPanel.onSpeedChanged = { speed -> simulationController.setSpeed(speed) }

		// South panel contains StatusBar (edit mode) and EventTimelinePanel (simulation mode)
		statusBar.registerProducer(railwayNetGridCanvas)
		southPanel.add(statusBar)
		contentPane.add(southPanel, BorderLayout.SOUTH)

		// Wire PathPreviewPanel callbacks to the canvas (Issue #596)
		pathPreviewPanel.onRouteSelected = { routes, selectedIndex ->
			railwayNetGridCanvas.setPathPreview(routes, selectedIndex)
		}
		pathPreviewPanel.onClear = {
			railwayNetGridCanvas.clearPathPreview()
		}
		// PathPreviewPanel sits above the status bar in the south panel; visible in editing mode
		pathPreviewPanel.isVisible = true
		southPanel.add(pathPreviewPanel, PATH_PREVIEW_SOUTH_INDEX)

		// Warning panel (Issue #616, Goal 3 SP6) – initially hidden; shown in simulation mode
		warningPanel.isVisible = false
		// Selection highlights the involved block on the canvas when known.
		warningPanel.onWarningSelected = { warning ->
			railwayNetGridCanvas.highlightWarningBlock(warning)
		}
		// Clearing the log also silences the status-bar warning indicator (Issue #616 follow-up).
		warningPanel.onClear = {
			statusBar.setWarningIndicator(false)
		}

		// Conflict resolution side panel (Issue #590, Goal 9 SP5) – initially hidden;
		// shown in the east slot when an active conflict requires dispatcher attention.
		conflictResolutionPanel.isVisible = false
		contentPane.add(conflictResolutionPanel, BorderLayout.EAST)

		// Add component listener to refresh canvas when frame is resized
		addComponentListener(
			object : ComponentAdapter() {
				override fun componentResized(e: ComponentEvent) {
					// When frame is resized, refresh grid canvas to handle scrollbar appearance/disappearance
					railwayNetGridCanvas.revalidate()
				}
			}
		)

		// Add window listener to handle close event with unsaved changes warning
		addWindowListener(
			object : WindowAdapter() {
				override fun windowClosing(e: WindowEvent) {
					handleWindowClosing()
				}
			}
		)
	}

	/**
	 * Switch UI layout to simulation mode (Issue #205).
	 *
	 * - StatusBar remains visible (its speed indicator [StatusBar.updateSpeedIndicator] shows
	 *   non-default speeds; [StatusBar.statusLabel] continues to display simulation events)
	 * - Adds EventTimelinePanel to south panel (if created)
	 * - Shows ControlPanel
	 * - Disables editing ToolBar
	 * - Installs global keyboard shortcuts for speed control (Phase 3.1, Issue #193)
	 * - Shows WarningPanel (Issue #616, Goal 3 SP6)
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToSimulationMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToSimulationMode must be called from EDT"
		}

		// Add EventTimelinePanel before StatusBar (index 0 = top of south panel, above StatusBar)
		eventTimelinePanel?.let { panel ->
			if (panel.parent == null) {
				southPanel.add(panel, TIMELINE_PANEL_SOUTH_INDEX)
			}
		}

		// Show WarningPanel in simulation mode (Goal 3 SP6)
		if (warningPanel.parent == null) {
			southPanel.add(warningPanel, WARNING_PANEL_SOUTH_INDEX)
		}
		warningPanel.isVisible = true

		// Hide PathPreviewPanel in simulation mode
		pathPreviewPanel.isVisible = false

		// Show ControlPanel and SimulationControlPanel
		controlPanel.isVisible = true
		controlPanel.updateStatus(ControlPanel.SimulationStatus.READY)
		simulationControlPanel.isVisible = true

		// Disable editing toolbar in simulation mode
		toolBar.setToolsEnabled(false)

		// Install keyboard shortcuts for simulation control (Phase 3.1, Issue #193)
		simulationKeyBindings.install(rootPane)

		southPanel.revalidate()
		southPanel.repaint()
		contentPane.revalidate()
		contentPane.repaint()
	}

	/**
	 * Switch UI layout to editing mode (Issue #205).
	 *
	 * - Removes EventTimelinePanel from south panel (StatusBar remains visible throughout)
	 * - Hides ControlPanel
	 * - Enables editing ToolBar
	 * - Uninstalls global keyboard shortcuts (Phase 3.1, Issue #193)
	 * - Hides WarningPanel (Issue #616, Goal 3 SP6)
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToEditingMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToEditingMode must be called from EDT"
		}

		// Remove EventTimelinePanel from south panel (StatusBar stays visible always)
		eventTimelinePanel?.let { panel ->
			southPanel.remove(panel)
		}

		// Hide WarningPanel in editing mode (Goal 3 SP6)
		warningPanel.isVisible = false

		// Hide ConflictResolutionPanel in editing mode (Goal 9 SP5)
		conflictResolutionPanel.clearResolutions()

		// Show PathPreviewPanel in editing mode
		pathPreviewPanel.isVisible = true

		// Hide ControlPanel and SimulationControlPanel
		controlPanel.isVisible = false
		simulationControlPanel.isVisible = false

		// Enable editing toolbar in editing mode
		toolBar.setToolsEnabled(true)

		// Uninstall keyboard shortcuts (Phase 3.1, Issue #193)
		simulationKeyBindings.uninstall(rootPane)

		southPanel.revalidate()
		southPanel.repaint()
		contentPane.revalidate()
		contentPane.repaint()
	}

	/**
	 * Set the railway network context and switch UI mode accordingly.
	 *
	 * - [EditingContext]: Switches to editing mode with StatusBar
	 * - [SimulationContext]: Switches to simulation mode with EventTimelinePanel and ControlPanel
	 *
	 * **Animation Integration (Issue #205):**
	 * When switching to SimulationContext, this method:
	 * 1. Lazy-creates EventTimelinePanel (reused across multiple simulations)
	 * 2. Wires EventTimelinePanel to RailwayNetGridCanvas → AnimationController
	 * 3. Starts 10 Hz timer for ControlPanel time updates
	 *
	 * **Must be called from EDT.**
	 */
	fun setContext(context: Context<*, *>) {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"setContext must be called from EDT"
		}
		stopSimulation() // Stop any running simulation before switching context
		stopAnimationUpdates() // Cleanup existing timer

		val previousSimulationContext = currentSimulationContext

		when (context) {
			is SimulationContext -> {
				currentSimulationContext = context

				// Lazy-create event timeline panel (reused across simulations)
				if (eventTimelinePanel == null) {
					eventTimelinePanel = EventTimelinePanel()
				}

				switchToSimulationMode()
				railwayNetGridCanvas.setEventTimelinePanel(eventTimelinePanel)
				railwayNetGridCanvas.setContext(context)
				railwayNetGridCanvas.autoCenterViewport()
				startAnimationUpdates()

				// Wire stop button to stopSimulation()
				controlPanel.onStop = { stopSimulation() }
				controlPanel.setStopEnabled(false) // enabled only after startSimulation()
				// Clear PathPreviewPanel context (Issue #596)
				pathPreviewPanel.setEditingContext(null)
			}
			is EditingContext -> {
				currentSimulationContext = null
				controlPanel.onStop = null
				switchToEditingMode()
				railwayNetGridCanvas.setContext(context)
				context.addPropertyChangeListener(modificationTracker)
				// Bind PathPreviewPanel to new editing context (Issue #596)
				pathPreviewPanel.setEditingContext(context)
			}
			else -> {
				currentSimulationContext = null
				controlPanel.onStop = null
				// Unknown context type - default to simulation mode (read-only)
				switchToSimulationMode()
				railwayNetGridCanvas.setContext(context)
				// Clear PathPreviewPanel context (Issue #596)
				pathPreviewPanel.setEditingContext(null)
			}
		}

		context.addPropertyChangeListener(statusBar)

		// Only close the previous context when it is a different instance.
		// Closing the same context that was just set would invalidate the Koin scope we just wired.
		if (previousSimulationContext !== null && previousSimulationContext !== context) {
			previousSimulationContext.close()
		}
	}

	/**
	 * Start timer for ControlPanel time updates (Issue #205).
	 *
	 * Creates a 10 Hz (100ms) Swing Timer that reads the current simulation time
	 * from [AnimationController] and updates [ControlPanel].
	 *
	 * **Timer Frequency Rationale:**
	 * - 100ms interval (10 Hz) provides responsive display without excessive CPU overhead
	 * - Much less frequent than 30 FPS rendering, but frequent enough for smooth time display
	 * - Swing Timer automatically runs on EDT (thread-safe)
	 *
	 * **Must be called from EDT.**
	 */
	private fun startAnimationUpdates() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startAnimationUpdates must be called from EDT"
		}

		animationUpdateTimer =
			Timer(100) {
				// 10 Hz update rate
				val controller = railwayNetGridCanvas.getAnimationController()
				controller?.getCurrentState()?.let { state ->
					controlPanel.updateTime(state.simulationTime)
				}
			}
		animationUpdateTimer?.start()
	}

	/**
	 * Stop ControlPanel update timer and clean up resources (Issue #205).
	 *
	 * Prevents memory leaks when switching between contexts or exiting simulation mode.
	 * Safe to call multiple times or when timer is not running.
	 *
	 * **Must be called from EDT.**
	 */
	private fun stopAnimationUpdates() {
		animationUpdateTimer?.stop()
		animationUpdateTimer = null
	}

	/**
	 * Launch the simulation on a background thread via [SimulationController] (Issue #189).
	 *
	 * Delegates to [SimulationController.start]. Idempotent: if a simulation is already
	 * running this call is a no-op.
	 *
	 * Wires the [cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService]
	 * listener to feed incoming warnings into [warningPanel] and [statusBar], and
	 * applies the current [autoPauseOnCriticalWarning] / [soundOnCriticalWarning] settings
	 * (Issue #616, Goal 3 SP6).
	 *
	 * Also registers a [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onConflictDetectedEvent]
	 * listener to populate [conflictResolutionPanel] with ranked resolution candidates whenever
	 * a spatial conflict is detected (Issue #590, Goal 9 SP5).
	 *
	 * **Must be called from EDT.**
	 */
	fun startSimulation() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startSimulation must be called from EDT"
		}

		val context =
			currentSimulationContext ?: run {
				logger.warn { "startSimulation called without a SimulationContext — ignoring" }
				return
			}

		// Register collision warning listener BEFORE context.run() is called (Issue #616).
		// The listener is delivered on the simulation thread; all UI updates are dispatched
		// back to the EDT.
		context.getCollisionServices().onCollisionWarning { warning ->
			// Capture settings atomically from the volatile fields before dispatch.
			val autoPause = autoPauseOnCriticalWarning
			val sound = soundOnCriticalWarning
			SwingUtilities.invokeLater {
				warningPanel.addWarning(warning)
				statusBar.setWarningIndicator(true)
				if (sound) {
					Toolkit.getDefaultToolkit().beep()
				}
				// If auto-pause is OFF, resume the simulation immediately.
				if (!autoPause) {
					simulationController.runner?.isPaused = false
				}
			}
		}

		// Apply the current auto-halt setting to the newly created service (Goal 3 SP6).
		applyAutoHaltSetting(autoHaltTrainOnViolation)

		// Register conflict-detected listener to drive ConflictResolutionPanel (Goal 9 SP5).
		// Listener is delivered on the simulation thread; UI updates dispatched to EDT.
		// Resolver + preference stores are retrieved from the context's Koin scope so every
		// collaborator shares the same scoped instances (Goal 9 SC3 + SC4 wiring).
		val resolver = context.scope.get<ConflictResolver>()
		val dispatcherStore = context.scope.get<DispatcherPreferenceStore>()
		val strategyStore = context.scope.get<StrategyPreferenceStore>()

		// Operator Apply: record the chosen resolution (SC3) and feed the choice into the
		// preference-learning store so future rankings reflect it (SC4). Invoked on the EDT.
		conflictResolutionPanel.onResolutionApplied = { conflict, resolution ->
			dispatcherStore.record(
				conflict,
				resolution,
				DispatcherPreferenceStore.ApplicationSource.OPERATOR
			)
			val conflictTypeKey = conflict.block.name ?: "unknown-block"
			strategyStore.recordChoice(conflictTypeKey, resolution.strategy)
			logger.debug {
				"Operator applied ${resolution.strategy} to conflict " +
					"(${conflict.trainId} vs ${conflict.conflictingTrainId}) " +
					"on block ${conflict.block.name} — recorded for preference learning"
			}
		}

		context.onConflictDetectedEvent { conflict ->
			val resolutions = resolver.generateResolutions(conflict)
			conflictResolutionPanel.showResolutions(conflict, resolutions)
			logger.debug {
				"ConflictDetectedEvent: ${conflict.trainId} vs ${conflict.conflictingTrainId} " +
					"on block ${conflict.block.name} — ${resolutions.size} resolution candidates shown"
			}
		}

		// Clear any stale warnings from a previous run.
		warningPanel.clearWarnings()
		statusBar.setWarningIndicator(false)
		conflictResolutionPanel.clearResolutions()

		try {
			simulationController.start(context)
			val activeRunner = simulationController.runner?.takeIf { it.isRunning() }
			simulationControlPanel.runner = activeRunner

			// Wire statusBar paused indicator to the runner's PROP_IS_PAUSED events.
			pausedListener?.let { old ->
				simulationController.runner?.removePropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, old)
			}
			if (activeRunner != null) {
				val listener =
					PropertyChangeListener { evt ->
						val paused = evt.newValue as? Boolean ?: return@PropertyChangeListener
						statusBar.setPaused(paused)
					}
				pausedListener = listener
				activeRunner.addPropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, listener)
				// Sync immediately with the current paused state.
				statusBar.setPaused(activeRunner.isPaused)
			}
		} catch (e: Exception) {
			logger.error(e) { "Failed to start simulation" }
		}
	}

	/**
	 * Request immediate simulation shutdown (Issue #189).
	 *
	 * Delegates to [SimulationController.stop]. Safe to call when no simulation is
	 * running (no-op in that case).
	 *
	 * **Must be called from EDT.**
	 */
	fun stopSimulation() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"stopSimulation must be called from EDT"
		}
		// Remove paused listener before stopping the runner.
		pausedListener?.let { listener ->
			simulationController.runner?.removePropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, listener)
		}
		pausedListener = null
		simulationController.stop()
		// Detach SimulationControlPanel from runner when simulation stops
		simulationControlPanel.runner = null
		// Clear the paused indicator when the simulation stops.
		statusBar.setPaused(false)
		// Clear the warning indicator when the simulation stops (Issue #616).
		statusBar.setWarningIndicator(false)
		// Clear and hide the conflict resolution panel when the simulation stops (Goal 9 SP5).
		// Drop the operator-Apply callback so it can no longer reference this run's stores.
		conflictResolutionPanel.onResolutionApplied = null
		conflictResolutionPanel.clearResolutions()
	}

	/**
	 * Apply [enabled] to the [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * of the current simulation context (if any).
	 *
	 * @since Issue #616 (Goal 3 SP6)
	 */
	private fun applyAutoHaltSetting(enabled: Boolean) {
		val service =
			currentSimulationContext
				?.getCollisionServices()
				?.getCollisionDetectionService()
				as? cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
		service?.autoHaltTrainOnViolation = enabled
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Index at which EventTimelinePanel is inserted in [southPanel] (above StatusBar). */
		private const val TIMELINE_PANEL_SOUTH_INDEX = 0

		/**
		 * Index at which PathPreviewPanel is inserted in [southPanel] (Issue #596).
		 *
		 * Sits above the StatusBar (index 0 is top). When EventTimelinePanel is also present
		 * it is at index 0, so PathPreviewPanel lands at index 1, just below it. When no
		 * timeline panel is present it sits at index 0, just above the StatusBar.
		 */
		private const val PATH_PREVIEW_SOUTH_INDEX = 0

		/**
		 * Index at which WarningPanel is inserted in [southPanel] (Issue #616, Goal 3 SP6).
		 *
		 * The WarningPanel appears above the StatusBar (and below the EventTimelinePanel and
		 * PathPreviewPanel when they are present).  Using index 0 keeps the panel ordering
		 * consistent with the other south-panel panels added dynamically.
		 */
		private const val WARNING_PANEL_SOUTH_INDEX = 0
	}

	/**
	 * Updates the window title to reflect current file and dirty state.
	 */
	private fun updateTitle() {
		val fileName = modificationTracker.getDisplayFileName()
		val suffix = modificationTracker.getTitleSuffix()

		title =
			if (fileName != null) {
				"$PROGRAM_FULL_NAME - $fileName$suffix"
			} else {
				PROGRAM_FULL_NAME
			}
	}

	/**
	 * Execute [action] on EDT.
	 *
	 * Runs immediately if already on EDT; otherwise schedules asynchronously via
	 * [javax.swing.SwingUtilities.invokeLater] to avoid blocking monitor/background threads.
	 */
	private fun runOnEdt(action: () -> Unit) {
		if (SwingUtilities.isEventDispatchThread()) {
			action()
		} else {
			SwingUtilities.invokeLater(action)
		}
	}

	/**
	 * Handles window closing event.
	 * Shows confirmation dialog if there are unsaved changes.
	 *
	 * Thread safety: Ensures execution on EDT for Swing component access.
	 */
	private fun handleWindowClosing() {
		// Defensive programming: ensure we're on the Event Dispatch Thread
		if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
			javax.swing.SwingUtilities.invokeLater { handleWindowClosing() }
			return
		}

		if (modificationTracker.isDirty()) {
			val result =
				JOptionPane.showConfirmDialog(
					this,
					"The railway network has unsaved changes.\n\n" +
						"Do you want to save your changes before closing?",
					"Unsaved Changes",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.WARNING_MESSAGE
				)

			when (result) {
				JOptionPane.YES_OPTION -> {
					// Trigger save action
					saveAndExit()
				}

				JOptionPane.NO_OPTION -> {
					// Exit without saving
					exitWithoutSaving()
				}

				JOptionPane.CANCEL_OPTION -> {
					// Do nothing - keep window open
					return
				}
			}
		} else {
			// No unsaved changes - exit immediately
			exitWithoutSaving()
		}
	}

	/**
	 * Attempts to save the current context and then exits.
	 * If save fails, the window remains open.
	 */
	private fun saveAndExit() {
		// Get the save action from menu bar and trigger it
		val menuBar = jMenuBar as MenuBar
		val saved = menuBar.triggerSave()

		// Only exit if save was successful
		if (saved) {
			stopAnimationUpdates() // Stop Frame's 10 Hz timer
			railwayNetGridCanvas.cleanupAnimation() // Stop AnimationController - CRITICAL for GC
			exitWithoutSaving()
		}
	}

	/**
	 * Exits the application without saving.
	 */
	private fun exitWithoutSaving() {
		stopSimulation() // Stop any running simulation before exit
		currentSimulationContext?.close() // Release simulation resources before JVM exit
		stopAnimationUpdates() // Stop Frame's 10 Hz timer
		railwayNetGridCanvas.cleanupAnimation() // Stop AnimationController - CRITICAL for GC
		dispose()
		System.exit(0)
	}
}
