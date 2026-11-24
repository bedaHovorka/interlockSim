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

import cz.vutbr.fit.interlockSim.DispatcherRunLifecycle
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.SimulationTimeTracker
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * Main application window for Railway Interlocking Simulator.
 *
 * One layout for both modes (Issue #1421): the parts stay in place and only their enabled state
 * changes, so the window never jumps when the mode changes.
 * - **Editing Mode** ([EditingContext]): Path Preview tab on, side dock speed and dispatcher
 *   controls disabled, ControlPanel hidden
 * - **Simulation Mode** ([SimulationContext]): Warnings and Timeline tabs on, side dock
 *   controls on, KPI dashboard polling, ControlPanel visible
 *
 * ## Layout Structure
 *
 * ```
 * ┌──────────────────────────────────────┬──────────────────────┐
 * │ MenuBar                              │                      │
 * ├──────────────────────────────────────┤ SideDock (EAST)      │
 * │ ToolBar                              │ ┌──────────────────┐ │
 * │ [Time] [Status] [Stop] [Pause] [Step]│ │ Simulation Speed │ │
 * │ (simulation mode only)               │ ├──────────────────┤ │
 * │                                      │ │ Metrics (KPI)    │ │
 * ├──────────────────────────────────────┤ ├──────────────────┤ │
 * │                                      │ │ Dispatcher       │ │
 * │ RailwayNetGridCanvas                 │ ├──────────────────┤ │
 * │ (scrollable; takes the extra height) │ │ Conflict         │ │
 * ╞══════════ JSplitPane bar ════════════╡ │ Resolution (#590,│ │
 * │ [Path Preview][Warnings (n)]         │ │ when a conflict  │ │
 * │ [Timeline]  – bottomTabs             │ │ needs input)     │ │
 * │                                      │ └──────────────────┘ │
 * ├──────────────────────────────────────┴──────────────────────┤
 * │ StatusBar (speed indicator, warning indicator)              │
 * └─────────────────────────────────────────────────────────────┘
 * ```
 *
 * The start size comes from [WindowGeometry]; with a [WindowStateStore] the frame restores the
 * saved bounds and tab-area height and saves them again on exit.
 *
 * ## Animation Integration (Issue #205)
 *
 * When a [SimulationContext] is set, the frame:
 * 1. Creates [EventTimelinePanel] (lazy, reused across simulations)
 * 2. Wires EventTimelinePanel to [RailwayNetGridCanvas] → [cz.vutbr.fit.interlockSim.gui.animation.AnimationController]
 * 3. Starts 10 Hz timer for [ControlPanel] time updates
 * 4. Shows ControlPanel and puts EventTimelinePanel into the Timeline tab
 *
 * When an [EditingContext] is set, the frame:
 * 1. Hides ControlPanel, disables the Timeline and Warnings tabs, clears the KPI dashboard
 * 2. Keeps StatusBar for mouse position feedback
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
class Frame internal constructor(
	private val windowStateStore: WindowStateStore?
) : JFrame(PROGRAM_FULL_NAME) {
	/**
	 * A frame that does not read or write the saved window layout (tests, tools). The application
	 * frame comes from Koin with a [WindowStateStore] (Issue #1421).
	 */
	constructor() : this(null)

	/**
	 * Base window title, without the file name and dirty marker (Issue #839).
	 *
	 * Defaults to [PROGRAM_FULL_NAME]. The GUI launch path replaces it with
	 * [cz.vutbr.fit.interlockSim.PROGRAM_LLM_FULL_NAME] when the run is driven by the LLM
	 * dispatcher, so the title bar alone says which dispatcher arm is deciding.
	 *
	 * Must be set on the EDT, like every other public member of this class.
	 */
	var appTitle: String = PROGRAM_FULL_NAME
		set(value) {
			field = value
			updateTitle()
		}

	val railwayNetGridCanvas: RailwayNetGridCanvas = RailwayNetGridCanvas()
	internal val statusBar: StatusBar = StatusBar()
	private val toolBar: ToolBar = ToolBar()

	// Animation UI components (Issue #205)
	internal val controlPanel: ControlPanel = ControlPanel()
	internal val simulationControlPanel: SimulationControlPanel = SimulationControlPanel()
	private var eventTimelinePanel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel? = null

	/**
	 * The clock for [ControlPanel] (Issue #1421): the 10 Hz timer, the [SimulationTimeTracker] and
	 * the shown time live there; [Frame] keeps only the wiring and the test delegates.
	 */
	private val clock: SimulationClock =
		SimulationClock(controlPanel, pacedTime = { simulationController.runner?.pacedSimulationTime() ?: 0.0 })

	/** The current run's time tracker; owned by [clock], kept as a test seam. */
	internal val simulationTimeTracker: SimulationTimeTracker?
		get() = clock.tracker

	// Live KPI dashboard (Issue #675, Goal 6 SP3): shown in simulation mode, polls the metrics service
	// off the EDT between RUNNING and STOPPED.
	internal val metricsDashboardPanel: MetricsDashboardPanel = MetricsDashboardPanel()

	// Dispatcher control panel (Issue #561, Goal 10 SP2b.6)
	internal val dispatcherControlPanel: DispatcherControlPanel = DispatcherControlPanel()

	// Path preview panel (Issue #596) – visible in editing mode
	private val pathPreviewPanel: PathPreviewPanel = PathPreviewPanel()

	// Warning log panel (Issue #616, Goal 3 SP6) – visible in simulation mode
	internal val warningPanel: WarningPanel = WarningPanel()

	// Conflict resolution side panel (Issue #590, Goal 9 SP5) – visible when a conflict is active
	internal val conflictResolutionPanel: ConflictResolutionPanel = ConflictResolutionPanel()

	/**
	 * The run's scope-bound wiring (issues #561/#806/#845/#1328/#930, Goal 9 SP5): the
	 * scope captures, the run-record finish and the panel wiring live in [SimulationRunWiring];
	 * [Frame]'s STOPPED transition calls it in the original order.
	 *
	 * Declared after its three panels so the initializer reads non-null fields.
	 */
	private val runWiring: SimulationRunWiring =
		SimulationRunWiring(
			dispatcherControlPanel = dispatcherControlPanel,
			dashboardPanel = metricsDashboardPanel,
			conflictResolutionPanel = conflictResolutionPanel,
			dialogOwner = this
		)

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

	// ── Main window layout (Issue #1421) ──────────────────────────────────────

	/**
	 * The bottom pane under the canvas (Issue #1421): the vertical split with the three tabs, their
	 * titles and the tab-area height live in [BottomPane]; [Frame] keeps the assembly and the seams
	 * ([bottomTabs] as a test delegate, the split pane at CENTER).
	 */
	private val bottomPane: BottomPane =
		BottomPane(railwayNetGridCanvas, pathPreviewPanel, warningPanel)

	/** The bottom tabs under the canvas; owned by [bottomPane], kept as a test seam. */
	internal val bottomTabs: JTabbedPane
		get() = bottomPane.bottomTabs

	/** The vertical split above [bottomTabs]; owned by [bottomPane], kept as a test seam. */
	internal val splitPane: JSplitPane
		get() = bottomPane.splitPane

	/**
	 * The collision-warning presentation (Issue #616, Goal 3 SP6): the selection highlight and the
	 * clear wiring and [showCollisionWarning] live in [WarningPresenter]; the volatile settings stay
	 * here, where MenuBar's toggles write them and the simulation-thread listener reads them.
	 */
	private val warningPresenter: WarningPresenter =
		WarningPresenter(
			warningPanel = warningPanel,
			statusBar = statusBar,
			canvas = railwayNetGridCanvas,
			bottomPane = bottomPane,
			resumeRun = { simulationController.runner?.isPaused = false }
		)

	/** Right-hand column: speed, KPI dashboard, dispatcher and conflict panels, in both modes. */
	internal val sideDock: SideDock =
		SideDock(simulationControlPanel, metricsDashboardPanel, dispatcherControlPanel, conflictResolutionPanel)

	/**
	 * Window chrome lifecycle (Issue #1421): the start geometry, the normal-bounds tracking and
	 * the close path live in [WindowLifecycle]; [Frame] keeps its two event listeners and the
	 * [requestClose] menu seam.
	 */
	private val windowLifecycle: WindowLifecycle =
		WindowLifecycle(
			frame = this,
			store = windowStateStore,
			bottomPane = bottomPane,
			isDirty = { modificationTracker.isDirty() },
			save = { (jMenuBar as MenuBar).triggerSave() },
			teardown = {
				stopSimulation() // Stop any running simulation before exit
				currentSimulationContext?.close() // Release simulation resources before JVM exit
				clock.stopUpdates() // Stop Frame's 10 Hz timer
				railwayNetGridCanvas.cleanupAnimation() // Stop AnimationController - CRITICAL for GC
				// Issue #1072: close the shared Ollama/Koog executor so its worker threads do not
				// outlive the application. Terminal for this JVM process (System.exit follows).
				DispatcherRunLifecycle.closeSharedOllamaExecutor()
			}
		)

	// Simulation lifecycle delegated to SimulationController for testability (Issue #189)
	internal val simulationController: SimulationController =
		SimulationController(
			onStateChanged = { state ->
				// Read synchronously, on the calling thread, before any hand-off to the EDT
				// (see SimulationController.lastStopWasNatural kdoc for why this ordering is
				// race-free): captures which RunEndCause produced this STOPPED transition, and
				// the run's final paced position the controller kept before it cleared the
				// runner (PR #1430 review: the clock's final refresh reads it).
				val runEndCause =
					if (simulationController.lastStopWasNatural) {
						RunEndCause.NATURAL_COMPLETION
					} else {
						RunEndCause.MANUAL_STOP
					}
				val finalPacedTime = simulationController.lastRunFinalPacedTime
				runOnEdt {
					when (state) {
						SimulationController.SimulationStatus.RUNNING -> {
							toolBar.showSimulationControls()
							controlPanel.updateStatus(ControlPanel.SimulationStatus.RUNNING)
							controlPanel.setStopEnabled(true)
							// Capture this run's scope-bound collaborators, wire the panel and
							// start the dashboard (issues #561, #675, #845, #847, #1328).
							runWiring.onRunning(currentSimulationContext, simulationController.runner)
						}

						SimulationController.SimulationStatus.STOPPED -> {
							toolBar.hideSimulationControls()
							simulationControlPanel.runner = null
							// Detach the decision-listener sink, the SEMI_AUTO approver and the
							// dispatcher panel state first: a failure later in this branch can
							// never skip the safety-motivated detach calls (issues #561, #806).
							runWiring.detachFromStaleRun()
							controlPanel.setStopEnabled(false)
							controlPanel.updateStatus(ControlPanel.SimulationStatus.STOPPED)
							// Issue #1072: stop the 10 Hz time-update timer and the 30 FPS animation
							// loop when the run ends (natural completion or manual stop). Before this,
							// natural completion left AnimationController ticking until the user clicked
							// Stop or closed the window — the "does not return control" symptom.
							// captureFinalFrameAndPause (#1096 review) runs one last state capture
							// before pausing — pauseAnimation alone left the painted frame one state
							// behind the run's real end, because stop() clears the caches a bare
							// pause never re-captured. The next startSimulation() restarts via
							// ensureAnimationRunning().
							// Issue #1421: show the time the run reached before the clock timer stops;
							// the final paced position covers the gap's tail the 10 Hz timer never saw.
							refreshSimulationTime(finalPacedTime)
							clock.stopUpdates()
							railwayNetGridCanvas.captureFinalFrameAndPause()
							// One last dashboard poll, then stop (Issue #675 + #1326).
							runWiring.stopDashboard(manual = runEndCause != RunEndCause.NATURAL_COMPLETION)
							// Finish the run record, release the Koog agent (issues #830, #930).
							// runEndCause was captured above, before this runOnEdt block, from
							// SimulationController.lastStopWasNatural — see that property's kdoc for
							// why the read is race-free with respect to which thread produced this
							// STOPPED transition (manual stop() vs. the monitor thread's natural
							// completion path both reach the identical STOPPED emission here).
							runWiring.finishRun(runEndCause)?.let { endedCause ->
								statusBar.setStarvedIndicator(endedCause == RunEndCause.STARVED)
							}
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
		ModificationTracker { _ ->
			updateTitle()
		}

	init {
		// Height reserves room for the simulation-mode north panels (ToolBar + ControlPanel +
		// SimulationControlPanel + DispatcherControlPanel, Issue #561). Without the extra
		// height the DispatcherControlPanel line steals vertical space from the scrollable
		// RailwayNetGridCanvas, hiding station tracks (PR #801 review comment).
		// Issue #1421: the start size comes from the screen (or the saved layout), not a fixed 1024x818.
		windowLifecycle.applyInitialGeometry()
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE) // Handle close event manually
		setLayout(BorderLayout())
		jMenuBar = MenuBar()
		contentPane.add(bottomPane.splitPane, BorderLayout.CENTER)

		// North container: ToolBar + ControlPanel (Issue #205). The speed and dispatcher panels moved
		// to the side dock (Issue #1421).
		val northContainer = JPanel()
		northContainer.layout = BoxLayout(northContainer, BoxLayout.PAGE_AXIS)
		northContainer.add(toolBar)
		controlPanel.isVisible = false // Initially hidden (shown only in simulation mode)
		// Run bar (Issue #1421): time, status and Stop, then Pause, Step Event, Step Time and the delta.
		// A left-aligned FlowLayout without gaps: each part keeps its own width (a BoxLayout let the
		// ControlPanel take the whole row), and the bar takes no height while both parts are hidden.
		val runBar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0))
		runBar.add(controlPanel)
		simulationControlPanel.stepControls.isVisible = false
		runBar.add(simulationControlPanel.stepControls)
		northContainer.add(runBar)
		contentPane.add(northContainer, BorderLayout.NORTH)

		// Side dock (Issue #1421): visible in both modes; editing mode disables the speed controls.
		sideDock.setSimulationMode(false)
		contentPane.add(sideDock, BorderLayout.EAST)

		// Route speed changes from SimulationControlPanel through SimulationController so
		// desiredSpeed stays in sync and is applied to the next simulation start.
		simulationControlPanel.onSpeedChanged = { speed -> simulationController.setSpeed(speed) }

		// The status bar alone sits at SOUTH; the other bottom panels are tabs (Issue #1421).
		statusBar.registerProducer(railwayNetGridCanvas)
		contentPane.add(statusBar, BorderLayout.SOUTH)

		// Bottom pane assembles its tabs itself (Issue #1421); the count wiring travels with it,
		// the selection highlight and clear wiring live in [WarningPresenter].

		// Conflict resolution panel (Issue #590, Goal 9 SP5) – initially hidden; it lives in the side
		// dock (Issue #1421) and shows itself when an active conflict requires dispatcher attention.
		conflictResolutionPanel.isVisible = false

		// Keyboard focus rests on the canvas (Issue #1421). The shortcuts of SimulationKeyBindings ignore
		// keys while a text field has focus, and the Delta spinner was the first focusable component,
		// so Space did nothing. A click on the canvas takes the focus back from the Delta field.
		railwayNetGridCanvas.isFocusable = true
		railwayNetGridCanvas.addMouseListener(
			object : java.awt.event.MouseAdapter() {
				override fun mousePressed(e: java.awt.event.MouseEvent) {
					railwayNetGridCanvas.requestFocusInWindow()
				}
			}
		)

		// Add component listener to refresh canvas when frame is resized
		addComponentListener(
			object : ComponentAdapter() {
				override fun componentResized(e: ComponentEvent) {
					// When frame is resized, refresh grid canvas to handle scrollbar appearance/disappearance
					railwayNetGridCanvas.revalidate()
					windowLifecycle.rememberNormalBounds()
				}

				override fun componentMoved(e: ComponentEvent) {
					windowLifecycle.rememberNormalBounds()
				}
			}
		)

		// Add window listener to handle close event with unsaved changes warning
		addWindowListener(
			object : WindowAdapter() {
				override fun windowClosing(e: WindowEvent) {
					requestClose()
				}
			}
		)
	}

	/**
	 * Switch UI layout to simulation mode (Issue #205).
	 *
	 * - StatusBar remains visible (its speed indicator [StatusBar.updateSpeedIndicator] shows
	 *   non-default speeds; [StatusBar.statusLabel] continues to display simulation events)
	 * - Puts EventTimelinePanel into the Timeline tab (if created)
	 * - Enables the Warnings and Timeline tabs, selects Timeline (Issue #1421)
	 * - Shows ControlPanel and enables the side dock's speed controls
	 * - Disables editing ToolBar
	 * - Installs global keyboard shortcuts for speed control (Phase 3.1, Issue #193)
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToSimulationMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToSimulationMode must be called from EDT"
		}

		// Put the EventTimelinePanel into its tab, in place of the placeholder (Issue #1421)
		bottomPane.showTimelinePanel(eventTimelinePanel)

		// The KPI dashboard (Issue #675, Goal 6 SP3) stays in the side dock. Entering simulation mode
		// from another simulation context must not keep the previous run's figures or poll.
		metricsDashboardPanel.detach()

		// Warnings and Timeline tabs on; Path Preview off (Issue #1421)
		bottomPane.applyTabMode(simulation = true)

		// Show ControlPanel; enable the side dock's speed controls
		controlPanel.isVisible = true
		controlPanel.updateStatus(ControlPanel.SimulationStatus.READY)
		simulationControlPanel.stepControls.isVisible = true
		sideDock.setSimulationMode(true)

		// Disable editing toolbar in simulation mode
		toolBar.setToolsEnabled(false)

		// Install keyboard shortcuts for simulation control (Phase 3.1, Issue #193)
		simulationKeyBindings.install(rootPane)
		// The shortcuts need the focus outside any text field (Issue #1421)
		railwayNetGridCanvas.requestFocusInWindow()

		contentPane.revalidate()
		contentPane.repaint()
	}

	/**
	 * Switch UI layout to editing mode (Issue #205).
	 *
	 * - Enables only the Path Preview tab and selects it (Issue #1421)
	 * - Hides ControlPanel; disables the side dock's speed controls
	 * - Enables editing ToolBar
	 * - Uninstalls global keyboard shortcuts (Phase 3.1, Issue #193)
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToEditingMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToEditingMode must be called from EDT"
		}

		// Stop the KPI dashboard's polling and clear it (Issue #675, Goal 6 SP3); it stays in the side dock
		metricsDashboardPanel.detach()

		// Only the Path Preview tab has content in editing mode (Issue #1421)
		bottomPane.applyTabMode(simulation = false)

		// Hide ConflictResolutionPanel in editing mode (Goal 9 SP5)
		conflictResolutionPanel.clearResolutions()

		// Hide ControlPanel; the side dock stays, with its speed and dispatcher controls disabled
		controlPanel.isVisible = false
		simulationControlPanel.stepControls.isVisible = false
		sideDock.setSimulationMode(false)
		// Detach the SEMI_AUTO approver and the dispatcher panel state in case
		// switchToEditingMode is called without a prior STOPPED transition (e.g. a context
		// swap while the simulation is not running).
		runWiring.detachFromStaleRun()

		// Enable editing toolbar in editing mode
		toolBar.setToolsEnabled(true)

		// Uninstall keyboard shortcuts (Phase 3.1, Issue #193)
		simulationKeyBindings.uninstall(rootPane)

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
		clock.stopUpdates() // Cleanup existing timer

		val previousSimulationContext = currentSimulationContext

		when (context) {
			is SimulationContext -> {
				currentSimulationContext = context

				// Lazy-create event timeline panel (reused across simulations)
				if (eventTimelinePanel == null) {
					eventTimelinePanel = EventTimelinePanel()
				}

				switchToSimulationMode()
				clock.attach(context)
				refreshSimulationTime()
				railwayNetGridCanvas.setEventTimelinePanel(eventTimelinePanel)
				railwayNetGridCanvas.setContext(context)
				railwayNetGridCanvas.autoCenterViewport()
				clock.startUpdates()

				// Wire stop button to stopSimulation()
				controlPanel.onStop = { stopSimulation() }
				controlPanel.setStopEnabled(false) // enabled only after startSimulation()
				// Clear PathPreviewPanel context (Issue #596)
				pathPreviewPanel.setEditingContext(null)
			}
			is EditingContext -> {
				currentSimulationContext = null
				controlPanel.onStop = null
				clock.attach(null)
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
				clock.attach(context)
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

	// ── The ControlPanel clock; [SimulationClock] owns the details ────────────

	/**
	 * Shows the simulated time of the run in [ControlPanel] (Issue #1421). EDT only. The shown
	 * time never goes backward within one run; [finalPacedTime] feeds in the run's final paced
	 * position already captured by [SimulationController] (neutral `0.0` during normal ticks):
	 * the clock ratchets, keeping both readings.
	 */
	internal fun refreshSimulationTime(finalPacedTime: Double = 0.0) = clock.refresh(finalPacedTime)

	/**
	 * Show one collision warning; **must be called from EDT**.
	 *
	 * Every warning is listed in [warningPanel]. Only a [CollisionWarning.Severity.CRITICAL] warning
	 * lights the status-bar indicator, beeps when [sound] is on, and resumes the run the collision
	 * service paused when [autoPause] is off. An advisory [CollisionWarning.Severity.WARNING] (a train
	 * still queued when the run stopped, Issue #1331) neither paused the run nor is an alarm, so it
	 * leaves the indicator, the sound and the pause state alone.
	 */
	internal fun showCollisionWarning(
		warning: CollisionWarning,
		autoPause: Boolean,
		sound: Boolean
	) = warningPresenter.show(warning, autoPause, sound)

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
	 * [runName] is what the run JSON records as `example` when the run ends (Issue #1328): the
	 * example name for an example run, or the XML file's base name with its extension
	 * was loaded from. `null` records no name.
	 *
	 * **Must be called from EDT.**
	 */
	fun startSimulation(runName: String? = null) {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startSimulation must be called from EDT"
		}

		val context =
			currentSimulationContext ?: run {
				logger.warn { "startSimulation called without a SimulationContext — ignoring" }
				return
			}
		runWiring.stageRunName(runName)

		// Register collision warning listener BEFORE context.run() is called (Issue #616).
		// The listener is delivered on the simulation thread; all UI updates are dispatched
		// back to the EDT.
		context.getCollisionServices().onCollisionWarning { warning ->
			// Capture settings atomically from the volatile fields before dispatch.
			val autoPause = autoPauseOnCriticalWarning
			val sound = soundOnCriticalWarning
			SwingUtilities.invokeLater { showCollisionWarning(warning, autoPause, sound) }
		}

		// Apply the current auto-halt setting to the newly created service (Goal 3 SP6).
		applyAutoHaltSetting(autoHaltTrainOnViolation)

		// Register conflict-detected listener to drive ConflictResolutionPanel (Goal 9 SP5, SC3 + SC4).
		runWiring.wireConflictResolution(context)

		// Clear any stale warnings from a previous run.
		warningPanel.clearWarnings()
		statusBar.setWarningIndicator(false)
		// Same for the previous run's starvation verdict (Issue #930). Cleared here, at start,
		// and deliberately NOT in stopSimulation(): the verdict is set by the STOPPED transition
		// and has to stay on screen until the user starts the next run.
		statusBar.setStarvedIndicator(false)
		conflictResolutionPanel.clearResolutions()

		try {
			// Issue #1421: a fresh clock source per run, so a second run on this context starts at zero.
			// Attached before start, so the tracker sees the run's first events too.
			clock.attach(context)
			refreshSimulationTime()
			simulationController.start(context)
			// Issue #1072 + #1096 review: a previous STOPPED paused the animation and the 10 Hz
			// time timer. Restart both so the new run is visible again without requiring a full
			// setContext cycle — moved here, after a successful start, so a failed start leaves
			// the previous run's animation paused instead of resurrecting it with no run behind it.
			railwayNetGridCanvas.ensureAnimationRunning()
			clock.stopUpdates()
			clock.startUpdates()
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
		runWiring.unwireConflictResolution()
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
	}

	/**
	 * Updates the window title to reflect current file and dirty state.
	 */
	private fun updateTitle() {
		val fileName = modificationTracker.getDisplayFileName()
		val suffix = modificationTracker.getTitleSuffix()

		title =
			if (fileName != null) {
				"$appTitle - $fileName$suffix"
			} else {
				appTitle
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
	 * Handles a close request: confirms unsaved changes and exits, or stays open (Issue #1421
	 * layout save lives in [WindowLifecycle]; PR #1430 review). Also the route menu Exit takes,
	 * so both exit paths confirm and save the layout.
	 */
	internal fun requestClose() = windowLifecycle.requestClose()
}
