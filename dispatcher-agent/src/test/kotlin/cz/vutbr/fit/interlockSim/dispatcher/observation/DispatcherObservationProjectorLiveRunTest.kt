/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.testutil.newShuntingLoopContext
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Live-simulation coverage for [DispatcherObservationProjector] (SP2c.1, #824) that the
 * fake-port unit tests in [DispatcherObservationProjectorTest] cannot exercise:
 *
 * 1. Koin resolves [DispatcherObservationProjector]/[DispatcherObservationSource] from a real
 *    per-context scope (mirrors [cz.vutbr.fit.interlockSim.dispatcher.di.DispatcherAgentPortBindingTest]).
 * 2. The composite `snapshotCaptureHook` pattern #824 describes for `wireDispatcherAgent`
 *    (`{ perceptionPort.captureSnapshot(); projector.captureOnSimThread() }`) works against a
 *    genuinely running kDisco simulation thread, satisfying the debug-only thread-identity guard.
 * 3. A "golden tick" recorded from one real `vyhybna.xml` run: once
 *    [cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry] has a live path
 *    reservation, the resulting [DispatcherObservation.reservations] entry is well-formed
 *    (non-blank endpoint names, non-empty route-ordered block ids) and
 *    [DispatcherObservation.switches] reports both `vA`/`vB` from the real grid walk.
 *
 * Uses the same lock-step driver/sim-thread handshake as
 * [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedDispatcherDeterminismTest] and
 * [cz.vutbr.fit.interlockSim.dispatcher.di.DispatcherAgentPortBindingTest] so the captured tick is
 * never racy.
 *
 * @since Issue #824 (SP2c.1 — Goal 10 autonomous dispatcher control-loop redesign)
 */
@DisplayName("DispatcherObservationProjector — live vyhybna.xml run (#824)")
@Tag("integration-test")
class DispatcherObservationProjectorLiveRunTest {
	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext = newShuntingLoopContext()

	@Test
	@DisplayName("scope resolves DispatcherObservationProjector and DispatcherObservationSource")
	fun scopeResolvesObservationBindings() {
		loadShuntingLoopContext().use { context ->
			val projector = context.scope.get<DispatcherObservationProjector>()
			val source = context.scope.get<DispatcherObservationSource>()

			assertThat(projector).isInstanceOf<DispatcherObservationProjector>()
			assertThat(source).isInstanceOf<DispatcherObservationProjector>()
			assertThat(source.latest()).isNotNull()
		}
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName(
		"a live run publishes a golden tick: real vA/vB switches, and a well-formed reservation " +
			"once a train holds a path"
	)
	fun liveRunPublishesGoldenTick() {
		loadShuntingLoopContext().use { context ->
			context.getInOuts()

			val loop = ShuntingLoop(context, endTime = 300L)

			val perceptionPort = context.scope.get<NetworkPerceptionPort>()
			val actuatorPort = context.scope.get<NetworkActuatorPort>()
			val projector = context.scope.get<DispatcherObservationProjector>()

			val queue = ActuatorCommandQueue()
			val dispatcher = RuleBasedDispatcher()
			val planner = RuleBasedPlanAdapter(dispatcher)
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = actuatorPort,
					onApproveTrain = loop::approveQueuedTrain,
					onBlockTransition = loop::incrementBlockTransition,
					onFailedReservation = loop::incrementFailedReservation
				)
			val driver =
				AgentLoopDriver(
					perceptionPort = perceptionPort,
					planner = planner,
					commandQueue = queue,
					controller = NoOpSimulationController,
					dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
				)

			val driverTurn = Semaphore(0)
			val simTurn = Semaphore(0)
			val goldenTick = AtomicReference<DispatcherObservation>()

			// The composite hook #824 describes for wireDispatcherAgent: keep the perception-port
			// snapshot fresh, then let the projector capture on the same (sim) thread.
			loop.snapshotCaptureHook = {
				perceptionPort.captureSnapshot()
				projector.captureOnSimThread()
			}
			loop.controlStepListener =
				ControlStepListener {
					driverTurn.release()
					simTurn.acquireUninterruptibly()
					applier.onControlStep()
					if (goldenTick.get() == null) {
						val observation = projector.latest()
						if (observation.reservations.isNotEmpty()) {
							goldenTick.set(observation)
						}
					}
				}
			loop.agentDriverAction = {
				while (loop.isSimActive()) {
					if (driverTurn.tryAcquire(100, TimeUnit.MILLISECONDS)) {
						try {
							driver.runCycle()
						} finally {
							simTurn.release()
						}
					}
				}
			}

			context.setMainProcess(loop)
			context.run()

			val observation = goldenTick.get()
			assertThat(observation).isNotNull()
			requireNotNull(observation)

			// Real grid walk: both named switches from vyhybna.xml, unaffected by which train
			// triggered the golden tick.
			assertThat(observation.switches.map { it.switchName }).containsExactly("vA", "vB")

			// The first reservation recorded is well-formed: a named target, a route-ordered,
			// non-empty list of block ids.
			val reservation = observation.reservations.first()
			assertThat(reservation.targetName).isNotEmpty()
			assertThat(reservation.blockIds).isNotEmpty()

			// Sorted by trainId (#824 hard sorting requirement).
			assertThat(observation.reservations.map { it.trainId })
				.containsExactly(
					*observation.reservations
						.map { it.trainId }
						.sorted()
						.toTypedArray()
				)

			assertThat(observation.tick).isGreaterThan(0L)
			assertThat(observation.capacity).isGreaterThanOrEqualTo(1)
			assertThat(observation.digest().isNotBlank()).isTrue()
		}
	}
}
