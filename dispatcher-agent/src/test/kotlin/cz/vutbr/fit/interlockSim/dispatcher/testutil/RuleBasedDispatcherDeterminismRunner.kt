/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidator
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop
import cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared `vyhybna.xml` + [RuleBasedDispatcher] run harness for the Goal 10 Stage A3
 * determinism gates (P10) — used by both
 * [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedDispatcherDeterminismTest] (10
 * consecutive runs, part of `integrationTest`) and
 * [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedDispatcherDeterminismHeavyTest] (1000
 * consecutive runs, manual-only `heavyTest`).
 *
 * **SP2c.5 (Issue #828) — P10 gate:** As of Issue #828, this runner uses
 * [DispatchTickLoop] + [RuleBasedEmissionStrategy] instead of the superseded
 * [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] + [cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter].
 * Both implementations drive the same [RuleBasedDispatcher] through the same
 * [DispatchDecisionApplier], so the simulation outcome is preserved.
 *
 * Centralising the wiring here means the two gates cannot silently drift apart in how
 * they drive the dispatcher — important given the wiring below encodes two subtle,
 * evidence-found ordering requirements (see inline comments): where
 * [cz.vutbr.fit.interlockSim.dispatcher.SnapshotSignal.signal] must fire relative to
 * [ShuntingLoop.snapshotCaptureHook] and [ShuntingLoop.controlStepListener], and that
 * the lock-step barrier below must only be released when [DispatchTickLoop.runTick]
 * actually did work (returned a non-null [cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord]).
 *
 * @since Issue #540 (SP0.1 — Goal 10 Stage A3); extracted from
 *   `RuleBasedDispatcherDeterminismTest` in Issue #746 (SP0.11c) so the heavy-test
 *   variant added for that issue's verification bar shares identical wiring.
 *   Updated in Issue #828 (SP2c.5) to route through [DispatchTickLoop].
 */
class RuleBasedDispatcherDeterminismRunner {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	/**
	 * Single run result captured for cross-run comparison.
	 *
	 * All fields must be identical across all runs for the determinism gate to pass.
	 *
	 * [sortedBlockTransitionCounts] uses sorted values (not keys) because train names
	 * include a global, never-reset counter (`Train.countValue`) that increments
	 * across successive simulation runs — a known cosmetic issue (SIM-002). Sorting
	 * the transition counts is sufficient to verify route determinism.
	 *
	 * **Note (SP2c.5 #828):** [sortedBlockTransitionCounts] will be all-zero or empty in
	 * this runner. [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier.applyRequestRoute]
	 * (which handles [cz.vutbr.fit.interlockSim.sim.DispatchDecision.RequestRoute], the decision
	 * type emitted by [DispatchTickLoop]) does not call [ShuntingLoop.incrementBlockTransition],
	 * unlike the superseded [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier.applyReservePath].
	 * The P10 gate verifies cross-run consistency (all 10 runs match), not an external baseline,
	 * so equal empty/zero counts still satisfy the gate.
	 *
	 * [conflictEventCount] must be 0 in every run: [RuleBasedDispatcher] uses the
	 * capacity cap and path-extension flags to avoid issuing competing reservations,
	 * so the shunting-loop topology should never trigger a
	 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent].
	 */
	data class RunResult(
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		val sortedBlockTransitionCounts: List<Int>,
		val conflictEventCount: Int
	)

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	fun executeRun(endTime: Long = 300L): RunResult {
		val context = loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime)

		// Track ConflictDetectedEvents: RuleBasedDispatcher must produce zero conflicts
		// on the deterministic shunting-loop topology (SP0.12 A3 gate).
		val conflictCount = AtomicInteger(0)
		context.onConflictDetectedEvent { conflictCount.incrementAndGet() }

		// SP0.11: Wire the full dispatcher-agent stack.
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = actuatorPort,
				onApproveTrain = loop::approveQueuedTrain,
				onBlockTransition = loop::incrementBlockTransition,
				onFailedReservation = loop::incrementFailedReservation
			)

		// SP0.11c (Issue #746): production-style signal-based pacing — see SnapshotSignal's KDoc.
		// DispatchTickLoop.runTick() blocks on the signal instead of the old Thread.sleep(1) poll.
		// The simTime-equality guard from AgentLoopDriver is intentionally NOT ported (Issue #746):
		// the signal already guarantees freshness; porting the guard would cause missed ticks.
		val driverSignal = DefaultSnapshotSignal()

		// SP2c.5 (Issue #828): build the DispatchTickLoop + RuleBasedEmissionStrategy stack.
		// The PathReservationRegistry is resolved from the context's Koin scope so the projector
		// and the actuator port share the same registry instance — the projector sees active
		// reservations made by the applier.
		val dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
		val pathReservationRegistry = context.scope.get<PathReservationRegistry>()
		val projector =
			DispatcherObservationProjector(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = dispatchLoopSensorPort,
				pathReservationRegistry = pathReservationRegistry,
				environment = context
			)
		val topology = StationTopologySerializer.describe(context)
		val validEndpointNames = (topology.inOuts + topology.signals.map { it.name }).toSet()
		val blockIds = topology.blocks.map { it.name }.toSet()
		val validator = ActionValidator(validEndpointNames = validEndpointNames, blockIds = blockIds)
		val annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator())
		val dispatcher = RuleBasedDispatcher()
		val emission = RuleBasedEmissionStrategy(dispatcher)
		val tickLoop =
			DispatchTickLoop(
				observations = projector,
				annotator = annotator,
				renderer = ObservationRenderer { "" },
				emission = emission,
				validator = validator,
				queue = queue,
				ring = TickRingBuffer(),
				workingMemory = WorkingMemory.EMPTY,
				budget = NoTimeoutBudget,
				fallbackGuard = TerminalFallbackGuard(),
				controller = NoOpSimulationController,
				snapshotSignal = driverSignal
			)

		// Decisions-applied barrier (independent of the #746 pacing fix above): this gate
		// also asserts conflictEventCount == 0 (see RunResult KDoc), which — per
		// ShuntingLoopLiftedDriverIntegrationTest's documented analysis — requires every
		// decision to be applied (and pathAlreadyExtendedBeyond etc. updated) before the
		// NEXT snapshot is captured, or two different trains can race for the same block.
		// That is a separate, out-of-scope-for-#746 concern (a decide-time-vs-apply-time
		// staleness race in the reservation layer, not a pacing race), so this minimal
		// ordering barrier is kept even though the driver's OWN pacing no longer needs
		// Thread.sleep or a stale-tick skip to stay correct.
		val decisionsApplied = Semaphore(0)

		// The composite hook: keep the perception-port snapshot fresh first, then let
		// the projector capture on the same sim thread (same pattern as wireDispatcherAgent).
		loop.snapshotCaptureHook = {
			perceptionPort.captureSnapshot()
			projector.captureOnSimThread()
		}
		// driverSignal.signal() fires from controlStepListener, NOT snapshotCaptureHook:
		// ShuntingLoop.iteration() calls snapshotCaptureHook() BEFORE it publishes the
		// per-tick TickObservation (read by dispatchLoopSensorPort above) but calls
		// controlStepListener AFTER. Signalling any earlier lets the driver wake and read
		// a stale TickObservation from the previous tick — reproduced as a spurious
		// conflictEventCount during development of this fix. See the identical comment
		// in ExampleRegistry.wireDispatcherAgent, which has the same ordering constraint.
		loop.controlStepListener =
			ControlStepListener {
				driverSignal.signal()
				decisionsApplied.acquireUninterruptibly()
				applier.onControlStep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				// Only release the barrier when runTick() actually did work (returned a non-null
				// TickRecord, meaning it posted a decision batch for THIS tick's signal). Releasing
				// unconditionally — including on a SnapshotSignal.await() timeout, where nothing
				// was sensed, decided, or posted — lets the sim thread's acquireUninterruptibly()
				// proceed to applier.onControlStep() before the real decision is posted, which
				// reintroduced the decide-time-vs-apply-time staleness conflict race this barrier
				// exists to prevent.
				if (tickLoop.runTick() != null) {
					decisionsApplied.release()
				}
			}
		}

		context.setMainProcess(loop)
		context.run()

		return RunResult(
			trainsExited = loop.getTrainsExited(),
			maxConcurrentTrains = loop.getMaxConcurrentTrains(),
			sortedBlockTransitionCounts = loop.getAllBlockTransitions().values.sorted(),
			conflictEventCount = conflictCount.get()
		)
	}
}
