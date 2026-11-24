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
import cz.vutbr.fit.interlockSim.DispatcherRunSummaries
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.publishedOrNull
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import cz.vutbr.fit.interlockSim.sim.metrics.MetricGroup
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.scope.Scope
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Everything wired to the active run's Koin scope (issues #561, #806, #845, #847, #1328, #930,
 * #1326): the [DispatcherControlPanel] binding, the decision-listener hub and SEMI_AUTO approver,
 * the KPI dashboard's polling lifecycle, the conflict-resolution wiring and the run-record
 * bookkeeping captured at RUNNING and finished at STOPPED.
 *
 * [Frame]'s STOPPED transition calls [detachFromStaleRun] and [finishRun] in its original order;
 * the order is load-bearing (a failure in the safety-motivated detach calls can never skip them,
 * and the Koog agent is released only after persistence), see the comments on [finishRun].
 *
 * **All methods are EDT-only** (the sim-thread side of the wiring pushes through
 * `SwingUtilities`), except where marked.
 */
internal class SimulationRunWiring(
	private val dispatcherControlPanel: DispatcherControlPanel,
	private val dashboardPanel: MetricsDashboardPanel,
	private val conflictResolutionPanel: ConflictResolutionPanel,
	/** Owner for the modal dialogs; today [Frame] itself (SemiAutoApprovalDialog needs it). */
	private val dialogOwner: Frame?
) {
	private val logger = KotlinLogging.logger {}

	// Decision-listener hub wired to the active context's Koin scope (Issue #561, SP2b.6).
	// Held so the STOPPED transition can detach the sink and stop the sim thread from
	// pushing applied decisions into a stale panel.
	private var wiredDecisionHub: DispatchDecisionListenerHub? = null

	// SEMI_AUTO approval gateway wired to the active context's Koin scope (Issue #806, SP2b.6 follow-up).
	// Held so the STOPPED transition can detach the approver and prevent the sim thread from
	// calling a stale dialog after the GUI is torn down.
	private var wiredSemiAutoGateway: SemiAutoApprovalGateway? = null

	// MeasuringPlanAdapter wired to the active context's Koin scope, captured at RUNNING time.
	// Only the shuntingLoopAI example registers one; every other example leaves this null.
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

	/**
	 * Name handed to the latest start of the run: the example name, or the XML file's
	 * [DispatcherRunSummaries.fileRunName] (Issue #1328). `null` when the caller passed none.
	 * One-shot: RUNNING moves it into [wiredRunName] and clears it. A start that fails before RUNNING
	 * leaves it set; the next start overwrites it first, so a stale name never reaches a run JSON.
	 */
	private var startedRunName: String? = null

	/**
	 * [startedRunName] captured at RUNNING, beside [wiredRunScope] and for the same reason: STOPPED
	 * persists it as the run JSON's `example` for the run that just ended (Issue #1328).
	 */
	private var wiredRunName: String? = null

	/** Stages the name the next run shall record (example name or XML file's name, Issue #1328). */
	fun stageRunName(name: String?) {
		startedRunName = name
	}

	/**
	 * The RUNNING transition: capture this run's scope-bound collaborators, wire the dispatcher
	 * panel and start the KPI dashboard. [context] and [runner] are read by [Frame] as
	 * `currentSimulationContext` / `simulationController.runner` at the transition.
	 */
	fun onRunning(
		context: SimulationContext?,
		runner: SimulationRunner?
	) {
		// Capture now so the STOPPED transition logs metrics for the run that just
		// ended, not whatever context happens to be current when STOPPED fires.
		wiredMeasuringAdapter = context?.scope?.publishedOrNull<MeasuringPlanAdapter>()
		// SP2c.22 (#845): capture DispatcherRunRecorder at RUNNING so the STOPPED
		// transition can call finish() and logFinalSummary() for this specific run.
		wiredRunRecorder = context?.scope?.getOrNull<DispatcherRunRecorder>()
		// Issue #847 round 4 (R4-5): capture the scope alongside the recorder so
		// STOPPED can also persist the run snapshot. Until round 4 the GUI called
		// finish() but nothing ever wrote the result, and nothing fed onTick or
		// onActionOutcome — so what it froze and discarded was an all-zero snapshot.
		wiredRunScope = context?.scope
		// Issue #1328: the run JSON names the example or XML file this run started from.
		wiredRunName = startedRunName
		startedRunName = null
		// Wire DispatcherControlPanel with DispatcherModeState from the active context (Issue #561)
		wireDispatcherControlPanel(runner)
		// Issue #675: start the dashboard on this run's metrics service (read only through
		// its cross-thread-safe latestSnapshot / latestOpenContentions).
		context?.scope?.let { runScope ->
			runScope.getOrNull<MetricsCollectionService>()?.let {
				val trainWait = runScope.getOrNull<MetricsConfig>()?.isEnabled(MetricGroup.TRAIN_WAIT) ?: true
				dashboardPanel.start(it, trainWait)
			}
		}
	}

	/**
	 * Detaches this run's wiring from the panel: the decision sink, the SEMI_AUTO approver and the
	 * panel's mode state. Called by the STOPPED transition first (a failure here can never skip the
	 * safety-motivated detach calls) and by editing mode, which may switch without a prior STOPPED
	 * (e.g. a context swap while the simulation is not running).
	 */
	fun detachFromStaleRun() {
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
	}

	/**
	 * One last dashboard poll, then stop (Issue #675). It reads the run record after any stop and
	 * retries a missing one briefly, since a manual stop reports STOPPED before the simulation
	 * thread delivers RunEnded (Issue #1326). The cause only picks the label when no record comes:
	 * "stopped manually" for a manual stop, else a dash.
	 */
	fun stopDashboard(manual: Boolean) {
		dashboardPanel.stop(manual = manual)
	}

	/**
	 * Finishes the run record of the run that just ended ([runEndCause] captured before the EDT
	 * hand-off), releases the cached Koog agent, and returns the persisted end cause so the caller
	 * can update the STARVED indicator; `null` when no scope was captured (a context set outside the
	 * RUNNING transition): the fallback finishes the recorder alone so the run is still finished and
	 * summarised.
	 *
	 * Placed after the safety-motivated detach calls so a failure here can never skip them.
	 */
	fun finishRun(runEndCause: RunEndCause): RunEndCause? {
		// Log the dispatcher's final PlannerMetricsSnapshot for the run that just
		// ended (captured at RUNNING time). Null for every example except
		// shuntingLoopAI (see ExampleRegistry.createShuntingLoopAIGuiExample).
		wiredMeasuringAdapter?.logFinalSummary()
		wiredMeasuringAdapter = null
		// SP2c.22 (#845): finish the run recorder and log its final summary.
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
		val persistedEndCause: RunEndCause? =
			if (runScope != null) {
				val persisted = DispatcherRunSummaries.finishAndPersist(runScope, runEndCause, wiredRunName)
				persisted.endCause
			} else {
				// No scope captured: fall back to the recorder alone so the run is still
				// finished and summarised.
				wiredRunRecorder?.finish(runEndCause)
				wiredRunRecorder?.logFinalSummary()
				null
			}
		// #1096 review: release the cached Koog agent AFTER persistence, through
		// DispatcherRunLifecycle (falls back to a bare KoogAgentPlanAdapter,
		// swallows exceptions) and off the EDT — a throwing release can no
		// longer lose the run JSON or the STARVED verdict (#930), and the EDT
		// never blocks on the agent's runBlocking close bridge (Issue #1072).
		DispatcherRunLifecycle.releaseKoogAgentInBackground(runScope)
		wiredRunRecorder = null
		wiredRunScope = null
		wiredRunName = null
		return persistedEndCause
	}

	/**
	 * Wires the [DispatcherControlPanel] with the active context's [DispatcherModeState] (Issue
	 * #561), the decision-listener hub (SP2b.6) and the SEMI_AUTO approver (Issue #806).
	 */
	private fun wireDispatcherControlPanel(runner: SimulationRunner?) {
		// Cast to DefaultSimulationContext is necessary to access the Koin scope.
		// SimulationContext interface does not expose the scope (by design);
		// only DefaultSimulationContext provides Koin DI bindings like DispatcherModeState.
		// This is acceptable because the dispatcher-agent module is only used with
		// DefaultSimulationContext implementations created by DefaultSimulationContextFactory.
		val simContext = runner?.simulationContext
		val context = simContext as? DefaultSimulationContext
		if (context == null) {
			logger.debug {
				"Context type ${simContext?.let { it::class.simpleName }} is not DefaultSimulationContext; " +
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
						dialogOwner,
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
						SemiAutoApprovalDialog.promptOnEdt(dialogOwner, decision)
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
	 * Registers the conflict-detected listener to drive [ConflictResolutionPanel] (Goal 9 SP5).
	 * The listener is delivered on the simulation thread; UI updates are dispatched to the EDT.
	 * Resolver + preference stores are retrieved from the context's Koin scope so every
	 * collaborator shares the same scoped instances (Goal 9 SC3 + SC4 wiring).
	 */
	fun wireConflictResolution(context: SimulationContext) {
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
	}

	/** Drops the operator-Apply callback so it can no longer reference this run's stores. */
	fun unwireConflictResolution() {
		conflictResolutionPanel.onResolutionApplied = null
	}
}
