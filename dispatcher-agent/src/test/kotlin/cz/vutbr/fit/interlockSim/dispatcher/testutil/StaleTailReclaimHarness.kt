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

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.PartialRouteReleaser
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
import cz.vutbr.fit.interlockSim.testutil.withMessage
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
		val driverFailure: Throwable?,
		/** Exceptions a simulation process let escape — a FATAL kills the process, not the run. */
		val uncaught: List<Throwable>
	)

	/**
	 * Wires the stack onto a fresh [ShuntingLoop] over [context], runs the context to
	 * completion, and returns the [Outcome]. A FATAL inside a train process does NOT propagate out
	 * of `context.run()` — the process dies and the run goes on — so it is recorded through
	 * [UncaughtSimulationExceptions] and reported in [Outcome.uncaught].
	 *
	 * @param staleAfterSimSeconds the sweeper's staleness threshold; the shipped default reclaims
	 *   nothing inside a short run, so callers pass an aggressive value on purpose.
	 * @param partialReleaser optional override for the sweeper's partial releaser (Issue #1025
	 *   tests): pass a recording decorator around the production [RegistryPartialRouteReleaser] to
	 *   observe the release calls. `null` wires the production releaser, exactly as
	 *   `ExampleRegistry.wireDispatcherAgent` does.
	 */
	fun run(
		context: DefaultSimulationContext,
		simEndTime: Long,
		staleAfterSimSeconds: Double,
		partialReleaser: PartialRouteReleaser? = null
	): Outcome {
		context.getInOuts()
		val loop = ShuntingLoop(context, simEndTime)

		val stack = wireStack(context, loop, staleAfterSimSeconds, partialReleaser)
		val barrier = installControlStep(loop, stack)

		context.setMainProcess(loop)
		val run = UncaughtSimulationExceptions.record { context.run() }

		return Outcome(
			loop = loop,
			partialReleaseCount = stack.sweeper.partialReleaseCount,
			barrierTimedOut = barrier.timedOut.get(),
			driverFailure = barrier.failure.get(),
			uncaught = run.uncaught
		)
	}

	/** The hand-wired dispatcher stack: everything the control step and the driver thread share. */
	private class WiredStack(
		val perceptionPort: DefaultNetworkPerceptionPort,
		val projector: DispatcherObservationProjector,
		val applier: DispatchDecisionApplier,
		val sweeper: OrphanReservationSweeper,
		val tickLoop: DispatchTickLoop,
		val driverSignal: DefaultSnapshotSignal
	)

	/** The barrier's observable failure state, read into [Outcome] after the run. */
	private class BarrierState(
		val timedOut: AtomicBoolean,
		val failure: AtomicReference<Throwable?>
	)

	/**
	 * Builds the test dispatcher stack by hand: ports, queue, applier, projector, rule-based tick
	 * loop, and the production sweeper + partial releaser constructed exactly as
	 * `ExampleRegistry.wireDispatcherAgent` constructs them.
	 */
	private fun wireStack(
		context: DefaultSimulationContext,
		loop: ShuntingLoop,
		staleAfterSimSeconds: Double,
		partialReleaser: PartialRouteReleaser?
	): WiredStack {
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
					partialReleaser
						?: RegistryPartialRouteReleaser(
							registry = registry,
							pathReservationService = context.getRoutingServices().getPathReservationService()
						)
			)
		return WiredStack(perceptionPort, projector, applier, sweeper, tickLoop, driverSignal)
	}

	/**
	 * Installs the lock-step barrier onto [loop]: the sim thread publishes this tick's
	 * observation, signals the driver, and waits for its posted decisions before the applier and
	 * the sweeper run; the driver thread posts a tick and releases the barrier. Waits are bounded
	 * by [BARRIER_TIMEOUT_SECONDS] and never throw on the kDisco thread — a timeout lands in
	 * [BarrierState.timedOut], a driver-side failure in [BarrierState.failure] and releases the
	 * barrier so the run cannot hang on it.
	 */
	private fun installControlStep(
		loop: ShuntingLoop,
		stack: WiredStack
	): BarrierState {
		val decisionsApplied = Semaphore(0)
		val timedOut = AtomicBoolean(false)
		val failure = AtomicReference<Throwable?>(null)

		loop.snapshotCaptureHook = stack.perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				// The per-tick observation is published before this listener runs, so the
				// projector sees THIS tick, not the previous one.
				stack.projector.captureOnSimThread()
				stack.driverSignal.signal()
				if (!decisionsApplied.tryAcquire(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					timedOut.set(true)
				}
				stack.applier.onControlStep()
				stack.sweeper.sweep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				try {
					// A null record is the normal signal-timeout short-circuit: no decisions were
					// posted, so the sim thread must keep waiting for a real cycle.
					if (stack.tickLoop.runTick() != null) {
						decisionsApplied.release()
					}
				} catch (t: Throwable) {
					failure.compareAndSet(null, t)
					decisionsApplied.release()
				}
			}
		}
		return BarrierState(timedOut, failure)
	}
}

/**
 * The four invariants every healthy [StaleTailReclaimHarness.Outcome] must satisfy (Issue #1025):
 * no train process died (a FATAL kills the process, not the run), at least one tail was actually
 * reclaimed, and neither side of the lock-step barrier failed. The stale-tail light/heavy pair and
 * the deferred-tail test assert these on every run through this one helper, so they cannot drift
 * apart — the assertion half of keeping the heavy soak in step with its light test.
 */
fun StaleTailReclaimHarness.Outcome.assertHealthyReclaim() {
	assertThat(
		uncaught,
		name = "exceptions that escaped a train process (a FATAL kills the process, not the run)"
	).isEmpty()
	assertThat(partialReleaseCount, name = "un-travelled tails actually reclaimed")
		.isGreaterThan(0)
	assertThat(barrierTimedOut)
		.withMessage("the sim thread must never wait out the driver barrier")
		.isFalse()
	assertThat(driverFailure)
		.withMessage("the driver thread must complete every cycle without throwing")
		.isNull()
}
