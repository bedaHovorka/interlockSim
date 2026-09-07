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
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.RegistryPartialRouteReleaser
import cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a [ShuntingLoop] under the rule-based [DispatchTickLoop] stack with the production
 * [OrphanReservationSweeper] + [RegistryPartialRouteReleaser] wired in at a caller-chosen
 * staleness threshold, and reports what happened (Issue #1025).
 *
 * ## What matches production, what differs
 *
 * The sweeper and the partial releaser are constructed exactly as
 * `ExampleRegistry.wireDispatcherAgent` constructs them, and they run at the same point of the
 * control step (after the applier, so a route requested this tick is not judged stale before it
 * has had a single tick to be travelled). The rest is a test stack: the ports and queue are
 * built by hand, the driver is a [DispatchTickLoop] under a lock-step barrier instead of the
 * production `AgentLoopDriver`/`AgentDriverLoop`, and the observation is captured for the
 * projector from `controlStepListener` — after `ShuntingLoop.iteration()` has published the
 * per-tick observation — never from `snapshotCaptureHook`, which runs before that publication.
 *
 * ## The barrier
 *
 * The sim thread signals the driver, then waits for it to post its decisions before draining the
 * queue. The wait is bounded ([BARRIER_TIMEOUT_SECONDS]) and never throws on the kDisco thread:
 * a timeout is recorded in [Outcome.barrierTimedOut], and a driver-side failure is recorded in
 * [Outcome.driverFailure] and releases the barrier, so a broken driver fails the test instead of
 * hanging it.
 */
object StaleTailReclaimHarness {
	private const val BARRIER_TIMEOUT_SECONDS = 30L

	/** What a completed run exposes to the test's assertions. */
	class Outcome internal constructor(
		val loop: ShuntingLoop,
		val partialReleaseCount: Int,
		val barrierTimedOut: Boolean,
		val driverFailure: Throwable?
	)

	/**
	 * Wires the stack onto a fresh [ShuntingLoop] over [context], runs the context to
	 * completion, and returns the [Outcome]. A FATAL on the simulation thread propagates out of
	 * `context.run()` and so out of this call.
	 *
	 * @param staleAfterSimSeconds the sweeper's staleness threshold; the shipped default reclaims
	 *   nothing inside a short run, so callers pass an aggressive value on purpose.
	 */
	fun run(
		context: DefaultSimulationContext,
		simEndTime: Long,
		staleAfterSimSeconds: Double
	): Outcome {
		context.getInOuts()
		val loop = ShuntingLoop(context, simEndTime)

		val perceptionPort = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
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

		val driverSignal = DefaultSnapshotSignal()
		val sensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
		val registry = context.scope.get<PathReservationRegistry>()
		val projector =
			DispatcherObservationProjector(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = sensorPort,
				pathReservationRegistry = registry,
				environment = context
			)
		val validator = actionValidatorFor(context)
		val tickLoop =
			DispatchTickLoop(
				observations = projector,
				annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator()),
				renderer = ObservationRenderer { "" },
				emission = RuleBasedEmissionStrategy(RuleBasedDispatcher()),
				validator = validator,
				queue = queue,
				ring = TickRingBuffer(),
				workingMemory = WorkingMemory.EMPTY,
				budget = NoTimeoutBudget,
				fallbackGuard = TerminalFallbackGuard(),
				controller = NoOpSimulationController,
				snapshotSignal = driverSignal
			)
		val sweeper =
			OrphanReservationSweeper(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = sensorPort,
				actuatorPort = actuatorPort,
				staleAfterSimSeconds = staleAfterSimSeconds,
				partialReleaser =
					RegistryPartialRouteReleaser(
						registry = registry,
						pathReservationService = context.getRoutingServices().getPathReservationService()
					)
			)

		val decisionsApplied = Semaphore(0)
		val barrierTimedOut = AtomicBoolean(false)
		val driverFailure = AtomicReference<Throwable?>(null)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				// The per-tick observation is published before this listener runs, so the
				// projector sees THIS tick, not the previous one.
				projector.captureOnSimThread()
				driverSignal.signal()
				if (!decisionsApplied.tryAcquire(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					barrierTimedOut.set(true)
				}
				applier.onControlStep()
				sweeper.sweep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				try {
					// A null record is the normal signal-timeout short-circuit: no decisions were
					// posted, so the sim thread must keep waiting for a real cycle.
					if (tickLoop.runTick() != null) {
						decisionsApplied.release()
					}
				} catch (t: Throwable) {
					driverFailure.compareAndSet(null, t)
					decisionsApplied.release()
				}
			}
		}

		context.setMainProcess(loop)
		context.run()

		return Outcome(
			loop = loop,
			partialReleaseCount = sweeper.partialReleaseCount,
			barrierTimedOut = barrierTimedOut.get(),
			driverFailure = driverFailure.get()
		)
	}
}
