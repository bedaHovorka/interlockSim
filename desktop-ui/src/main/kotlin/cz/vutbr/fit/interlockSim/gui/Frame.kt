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

import cz.vutbr.fit.interlockSim.DispatcherRunSummaries
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.scope.Scope
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
	private val controlPanel: ControlPanel = ControlPanel()
	internal val simulationControlPanel: SimulationControlPanel = SimulationControlPanel()
	private var eventTimelinePanel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel? = null
	private var animationUpdateTimer: Timer? = null

	// Dispatcher control panel (Issue #561, Goal 10 SP2b.6)
	internal val dispatcherControlPanel: DispatcherControlPanel = DispatcherControlPanel()

	// Decision-listener hub wired to the active context's Koin scope (Issue #561, SP2b.6).
	// Held so the STOPPED transition can detach the sink and stop the sim thread from
	// pushing applied decisions into a stale panel.
	private var wiredDecisionHub: DispatchDecisionListenerHub? = null

	// SEMI_AUTO approval gateway wired to the active context's Koin scope (Issue #806, SP2b.6 follow-up).
	// Held so the STOPPED transition can detach the approver and prevent the sim thread from
	// calling a stale dialog after the GUI is torn down.
	private var wiredSemiAutoGateway: SemiAutoApprovalGateway? = null

	// MeasuringPlanAdapter wired to the active context's Koin scope, captured at RUNNING time
	// (Issue tracking: none — internal polish from final review) so the STOPPED transition
	// logs metrics for the run that just ended, not whatever context happens to be current
	// when STOPPED is (asynchronously) delivered on the EDT. Only the shuntingLoopAI example
	// registers one; every other example leaves this null.
	private var wiredMeasuringAdapter: MeasuringPlanAdapter? = null

	// DispatcherRunRecorder wired to the active context's Koin scope, captured at RUNNING time
	// (SP2c.22, Issue #845) so the STOPPED transition calls finish() and logFinalSummary() for
	// the run that just ended. Present for any context that has one scoped (all contexts with
	// the dispatcherAgentModule wired). Null for contexts without the dispatcher module.
	private var wiredRunRecorder: DispatcherRunRecorder? = null

	/**
	 * Koin scope of the run captured at RUNNING, used at STOPPED to persist the run snapshot.
	 *
	 * Captured alongside [wiredRunRecorder] and for the same reason: the current context may have
	 * changed by the time STOPPED fires, and the snapshot must belong to the run that just ended.
	 *
	 * @since Issue #847 round 4 (PR #891 — R4-5)
	 */
	private var wiredRunScope: Scope? = null

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
				// Read synchronously, on the calling thread, before any hand-off to the EDT
				// (see SimulationController.lastStopWasNatural kdoc for why this ordering is
				// race-free): captures which RunEndCause produced this STOPPED transition.
				val runEndCause =
					if (simulationController.lastStopWasNatural) {
						RunEndCause.NATURAL_COMPLETION
					} else {
						RunEndCause.MANUAL_STOP
					}
				runOnEdt {
					when (state) {
						SimulationController.SimulationStatus.RUNNING -> {
							toolBar.showSimulationControls()
							controlPanel.updateStatus(ControlPanel.SimulationStatus.RUNNING)
							controlPanel.setStopEnabled(true)
							// Capture now so the STOPPED transition logs metrics for the run that just
							// ended, not whatever context happens to be current when STOPPED fires.
							wiredMeasuringAdapter = currentSimulationContext?.scope?.getOrNull<MeasuringPlanAdapter>()
							// SP2c.22 (#845): capture DispatcherRunRecorder at RUNNING so the STOPPED
							// transition can call finish() and logFinalSummary() for this specific run.
							wiredRunRecorder = currentSimulationContext?.scope?.getOrNull<DispatcherRunRecorder>()
							// Issue #847 round 4 (R4-5): capture the scope alongside the recorder so
							// STOPPED can also persist the run snapshot. Until round 4 the GUI called
							// finish() but nothing ever wrote the result, and nothing fed onTick or
							// onActionOutcome — so what it froze and discarded was an all-zero snapshot.
							wiredRunScope = currentSimulationContext?.scope
							// Wire DispatcherControlPanel with DispatcherModeState from the active context (Issue #561)
							wireDispatcherControlPanel()
						}

						SimulationController.SimulationStatus.STOPPED -> {
							toolBar.hideSimulationControls()
							simulationControlPanel.runner = null
							// Detach the decision sink first so the sim thread can no longer push
							// applied decisions into the panel, then clear panel state (Issue #561).
							wiredDecisionHub?.setSink(null)
							wiredDecisionHub = null
							// Detach the SEMI_AUTO approver so the sim thread cannot call a stale
							// dialog after the GUI is torn down (Issue #806, SP2b.6 follow-up).
							wiredSemiAutoGateway?.setApprover(null)
							wiredSemiAutoGateway = null
							dispatcherControlPanel.modeState = null
							dispatcherControlPanel.clearRationale()
							controlPanel.setStopEnabled(false)
							controlPanel.updateStatus(ControlPanel.SimulationStatus.STOPPED)
							// Log the dispatcher's final PlannerMetricsSnapshot for the run that just
							// ended (captured at RUNNING time above). Null for every example except
							// shuntingLoopAI (see ExampleRegistry.createShuntingLoopAIGuiExample).
							// Placed last so a failure here can never skip the safety-motivated
							// detach calls above.
							wiredMeasuringAdapter?.logFinalSummary()
							wiredMeasuringAdapter = null
							// SP2c.22 (#845): finish the run recorder and log its final summary.
							// runEndCause was captured above, before this runOnEdt block, from
							// SimulationController.lastStopWasNatural — see that property's kdoc for
							// why the read is race-free with respect to which thread produced this
							// STOPPED transition (manual stop() vs. the monitor thread's natural
							// completion path both reach the identical STOPPED emission here).
							// Round 4 (R4-5): finishAndPersist performs finish() + logFinalSummary() and
							// then writes the snapshot under build/reports/dispatcher-runs/<arm>/, giving
							// SP2c.23's aggregator (#846) a producer. Until round 4 the GUI called finish()
							// but nothing wrote the result, and nothing fed onTick/onActionOutcome, so the
							// frozen snapshot was all zeroes and was then discarded. It is a no-op for a
							// context that wired no dispatcher, and idempotent per run.
							// Issue #930: finishAndPersist decides the cause actually recorded — a natural
							// completion over a railway that achieved nothing becomes STARVED — and hands
							// it back so the verdict reaches the user instead of only the log file.
							val runScope = wiredRunScope
							if (runScope != null) {
								val persisted = DispatcherRunSummaries.finishAndPersist(runScope, runEndCause)
								statusBar.setStarvedIndicator(persisted.endCause == RunEndCause.STARVED)
							} else {
								// No scope captured (a context set outside the RUNNING transition): fall back to
								// the recorder alone so the run is still finished and summarised.
								wiredRunRecorder?.finish(runEndCause)
								wiredRunRecorder?.logFinalSummary()
							}
							wiredRunRecorder = null
							wiredRunScope = null
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
		// Height reserves room for the simulation-mode north panels (ToolBar + ControlPanel +
		// SimulationControlPanel + DispatcherControlPanel, Issue #561). Without the extra
		// height the DispatcherControlPanel line steals vertical space from the scrollable
		// RailwayNetGridCanvas, hiding station tracks (PR #801 review comment).
		setSize(1024, 818)
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
		dispatcherControlPanel.isVisible = false // Initially hidden (shown only in simulation mode)
		northContainer.add(dispatcherControlPanel)
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
		dispatcherControlPanel.isVisible = true

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
		dispatcherControlPanel.isVisible = false
		dispatcherControlPanel.modeState = null
		dispatcherControlPanel.clearRationale()
		// Detach the SEMI_AUTO approver in case switchToEditingMode is called without a prior
		// STOPPED transition (e.g. a context swap while the simulation is not running).
		wiredSemiAutoGateway?.setApprover(null)
		wiredSemiAutoGateway = null

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
	 * Wire the [DispatcherControlPanel] to the active simulation context (Issue #561, SP2b.6).
	 *
	 * Resolves four things from the context's Koin scope (when the dispatcher-agent
	 * module is loaded):
	 * 1. [cz.vutbr.fit.interlockSim.sim.DispatcherModeState] → drives the panel's mode
	 *    combo box and indicator.
	 * 2. [cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub] → the panel's
	 *    `onModeChanged` callback propagates the operator's selection to
	 *    [cz.vutbr.fit.interlockSim.sim.DispatcherModeState.setOverride]; the hub's
	 *    sink feeds every applied [cz.vutbr.fit.interlockSim.sim.DispatchDecision]'s rationale into the panel so
	 *    the "Why this route?" button can display it.
	 * 3. The panel's `onRationale` callback → shows the rationale in a dialog.
	 * 4. [cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway] → installs a
	 *    [SemiAutoApprovalDialog]-based blocking approver on the gateway so that
	 *    decisions in [cz.vutbr.fit.interlockSim.sim.DispatcherMode.SEMI_AUTO] mode
	 *    wait for the operator to click Approve or Dismiss (Issue #806, SP2b.6 follow-up).
	 *
	 * If any lookup fails (e.g. the context is not a [DefaultSimulationContext], or the
	 * dispatcher-agent module is not loaded) the panel remains disabled but the GUI is
	 * still functional (backward compatibility).
	 *
	 * **Must be called from EDT.**
	 */
	private fun wireDispatcherControlPanel() {
		val runner = simulationController.runner ?: return
		val simContext = runner.simulationContext
		// Cast to DefaultSimulationContext is necessary to access the Koin scope.
		// SimulationContext interface does not expose the scope (by design);
		// only DefaultSimulationContext provides Koin DI bindings like DispatcherModeState.
		// This is acceptable because the dispatcher-agent module is only used with
		// DefaultSimulationContext implementations created by DefaultSimulationContextFactory.
		val context = simContext as? DefaultSimulationContext
		if (context == null) {
			logger.debug {
				"Context type ${simContext::class.simpleName} is not DefaultSimulationContext; " +
					"dispatcher control panel remains disabled (backward compatible)"
			}
			return
		}
		try {
			val modeState = context.scope.getOrNull<DispatcherModeState>()
			if (modeState != null) {
				dispatcherControlPanel.modeState = modeState
				// The operator's combo-box selection propagates to the shared DispatcherModeState override.
				// In SEMI_AUTO mode, the SemiAutoApprovalGateway (wired below) will prompt the
				// operator to approve or dismiss each decision (Issue #806, SP2b.6 follow-up).
				dispatcherControlPanel.onModeChanged = { mode -> modeState.setOverride(mode) }
				// The "Why this route?" button shows the last decision's rationale in a modal dialog.
				dispatcherControlPanel.onRationale = { rationale ->
					JOptionPane.showMessageDialog(
						this@Frame,
						formatRationale(rationale),
						"Dispatcher decision rationale",
						JOptionPane.INFORMATION_MESSAGE
					)
				}
				// The sim-thread applier pushes every applied decision through the hub;
				// marshal the update onto the EDT before touching the panel.
				val hub = context.scope.getOrNull<DispatchDecisionListenerHub>()
				if (hub != null) {
					hub.setSink { decision ->
						SwingUtilities.invokeLater {
							dispatcherControlPanel.updateDecisionRationale(decision)
						}
					}
					wiredDecisionHub = hub
				} else {
					logger.debug { "DispatchDecisionListenerHub not available in context scope; rationale button will have no feed" }
				}
				// SP2b.6 follow-up (Issue #806): install a blocking SemiAutoApprovalDialog
				// as the SEMI_AUTO approver. The gateway bridges the sim-thread call to this
				// EDT-side dialog via SwingUtilities.invokeAndWait inside SemiAutoApprovalDialog.promptOnEdt.
				// On stop, the STOPPED transition calls setApprover(null) to detach.
				val semiAutoGateway = context.scope.getOrNull<SemiAutoApprovalGateway>()
				if (semiAutoGateway != null) {
					semiAutoGateway.setApprover { decision ->
						// Called on the sim thread; invokeAndWait hands off to EDT to show modal dialog.
						SemiAutoApprovalDialog.promptOnEdt(this@Frame, decision)
					}
					wiredSemiAutoGateway = semiAutoGateway
				} else {
					logger.debug {
						"SemiAutoApprovalGateway not available in context scope; SEMI_AUTO mode will drop decisions with warning"
					}
				}
			} else {
				logger.debug { "DispatcherModeState not available in context scope; dispatcher control panel remains disabled" }
			}
		} catch (e: Exception) {
			logger.debug(e) {
				"Failed to wire DispatcherModeState to control panel; dispatcher control panel remains disabled " +
					"(backward compatible)"
			}
		}
	}

	/**
	 * Format the decision rationale list for the "Why this route?" dialog (Issue #561, SP2b.6).
	 *
	 * An empty list (no rationale recorded) yields a single explanatory line; a non-empty
	 * list is rendered as one bullet line per entry.
	 */
	private fun formatRationale(rationale: List<String>): String =
		if (rationale.isEmpty()) {
			"No rationale recorded for the last decision."
		} else {
			rationale.joinToString("\n") { "• $it" }
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
		// Same for the previous run's starvation verdict (Issue #930). Cleared here, at start,
		// and deliberately NOT in stopSimulation(): the verdict is set by the STOPPED transition
		// and has to stay on screen until the user starts the next run.
		statusBar.setStarvedIndicator(false)
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
