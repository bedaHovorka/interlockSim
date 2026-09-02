/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.actionValidatorFor
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Issue #1025: reclaiming a stale route tail must never kill the simulation thread.
 *
 * ## What this pins
 *
 * `OrphanReservationSweeper.evaluateOccupyingTrain` reclaims the un-travelled RESERVED tail of a
 * train that stands on the rest of its own route, through `RegistryPartialRouteReleaser`. That
 * releaser drives every governing semaphore to STOP first (#893 task A3) and only then moves each
 * tail block RESERVED → FREE. It does **not** touch the train's `PathInfo`: per-block release
 * keeps `trainToPathInfo` by explicit design (`PathReservationRegistry.unregisterBlock`), and
 * `DefaultTrainNavigationService` never reads block state.
 *
 * The concern behind #1025 is that a train could therefore still be routed into a block that was
 * freed under it, and `DynamicTrackBlock.enter` asserts RESERVED → OCCUPIED. Entering a FREE block
 * raises `SimulationException[FATAL]` on the kDisco simulation thread, which no caller catches.
 *
 * ## Why the threshold is 2 s and not the shipped 60 s
 *
 * The reclaim is what is under test, not the staleness policy. At the shipped
 * `DEFAULT_STALE_AFTER_SIM_SECONDS` a 300 s run reclaims nothing, and the test would assert
 * against a code path that never ran. At 2 s the reclaim fires within the first few control
 * steps, which is exactly the situation to be safe in.
 *
 * The assertion on the reclaim count is what keeps this test honest: without it, a future change
 * that stopped reclaiming anything at all would leave the test passing for the wrong reason.
 *
 * ## Standing measurement
 *
 * This configuration reclaims tails from two trains and the run completes. Sweeps at every
 * threshold from 2 s to 60 s, `cancel_route` on a running train at 60 different control steps,
 * and `cancel_route` landing inside `Train.kt`'s `hold(1.0)` window at five fractional offsets —
 * 215 runs in total — all completed without the FATAL. The crash reported in #1025 is therefore
 * **not** reproduced by releasing a block under a train on its own, and its trigger is still open.
 */
@DisplayName("Issue #1025 — reclaiming a stale tail must not kill the simulation")
@Tag("integration-test")
class Issue1025StaleTailReleaseTest : DispatcherKoinTestBase() {
	private companion object {
		const val SIM_END_TIME = 300L

		/** Aggressive on purpose — see the KDoc. */
		const val AGGRESSIVE_STALE_SECONDS = 2.0
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	fun `a reclaimed stale tail leaves the simulation thread alive`() {
		val context = TestFixtures.newShuntingSimulationContext(XMLContextFactory(), DefaultSimulationProcessFactory())
		try {
			context.getInOuts()
			val loop = ShuntingLoop(context, SIM_END_TIME)

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

			// Uses the same production OrphanReservationSweeper + RegistryPartialRouteReleaser
			// wiring as ExampleRegistry.wireDispatcherAgent, with only the staleness threshold
			// overridden for this test harness.
			val sweeper =
				OrphanReservationSweeper(
					perceptionPort = perceptionPort,
					dispatchLoopSensorPort = sensorPort,
					actuatorPort = actuatorPort,
					staleAfterSimSeconds = AGGRESSIVE_STALE_SECONDS,
					partialReleaser =
						RegistryPartialRouteReleaser(
							registry = registry,
							pathReservationService = context.getRoutingServices().getPathReservationService()
						)
				)

			val decisionsApplied = Semaphore(0)
			loop.snapshotCaptureHook = { projector.captureOnSimThread() }
			loop.controlStepListener =
				ControlStepListener {
					driverSignal.signal()
					decisionsApplied.acquireUninterruptibly()
					applier.onControlStep()
					// After the applier, so a route requested this tick is not judged stale before
					// it has had a single tick to be travelled.
					sweeper.sweep()
				}
			loop.agentDriverAction = {
				while (loop.isSimActive()) {
					if (tickLoop.runTick() != null) {
						decisionsApplied.release()
					}
				}
			}

			context.setMainProcess(loop)

			// The run itself is the assertion for thread survival: a FATAL on the simulation
			// thread propagates out of run() and fails the test.
			context.run()

			assertThat(sweeper.partialReleaseCount, name = "un-travelled tails actually reclaimed")
				.isGreaterThan(0)
		} finally {
			context.close()
		}
	}
}
