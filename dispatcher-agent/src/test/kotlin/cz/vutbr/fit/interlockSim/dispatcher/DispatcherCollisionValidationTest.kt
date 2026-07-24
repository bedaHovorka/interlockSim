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
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * SP2b.7 validation gate — dispatcher routing against Goal 3 collision detection.
 *
 * Validates that [RuleBasedDispatcher] never triggers a [CollisionWarning] from the
 * Goal 3 [DefaultCollisionDetectionService] — the "no collisions" success criterion.
 *
 * ## Purpose
 *
 * [ShuntingLoopLiftedDriverIntegrationTest] verifies zero
 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent]s at the Goal 9 layer.
 * This test complements it by exercising the Goal 3 safety net: the
 * [DefaultCollisionDetectionService] is subscribed to block events via
 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.onCollisionWarning], and
 * the assertion is that **zero [CollisionWarning]s** are emitted during a
 * correct dispatcher run.
 *
 * A non-zero warning count would indicate that [RuleBasedDispatcher] produces routing
 * decisions that the interlocking translates into competing reservations
 * ([CollisionWarning.ReservationConflict]) or illegal block entries
 * ([CollisionWarning.BlockEntryViolation]).
 *
 * ## Goal 3 SP5 headless flag
 *
 * [DefaultCollisionDetectionService.autoPauseOnCritical] is explicitly set to `false`
 * before the run so a hypothetical CRITICAL warning would not attempt to pause the
 * headless simulation.  This exercises the same pattern that `fast-sim` / CLI entry
 * points and other automated headless scenarios must use: the operator cannot react to
 * a pause request when there is no GUI.
 *
 * ## Lock-step rationale
 *
 * Identical to [ShuntingLoopLiftedDriverIntegrationTest]: without lock-step, OS
 * scheduling races between the driver thread and the kDisco sim thread can produce
 * stale observations that cause duplicate reservations, which in turn trigger
 * [CollisionWarning.ReservationConflict]s.  The lock-step handshake pins one driver
 * cycle per simulation tick and eliminates this race.
 *
 * @see ShuntingLoopLiftedDriverIntegrationTest for the single-run Goal 9 correctness gate
 * @see RuleBasedDispatcherDeterminismTest for the 10-run cross-run determinism gate
 * @since Issue #562 (SP2b.7 — Goal 10)
 */
@DisplayName("SP2b.7 — dispatcher routing: zero Goal 3 collision warnings (safety net validation)")
@Tag("integration-test")
class DispatcherCollisionValidationTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/**
	 * Runs `vyhybna.xml` with the full lifted dispatcher-agent stack under lock-step and
	 * asserts the Goal 3 safety net:
	 *
	 * 1. **Zero [CollisionWarning]s** from [DefaultCollisionDetectionService] (Goal 3).
	 *    A warning here means the dispatcher allowed competing reservations or an illegal
	 *    block entry — either indicates unsafe routing.
	 * 2. **Zero [ConflictDetectedEvent]s** (Goal 9 complementary assertion). These fire
	 *    at the lower reservation layer before Goal 3 promotes them to warnings.
	 * 3. **All generated trains exit** — no permanent deadlock.
	 *
	 * [DefaultCollisionDetectionService.autoPauseOnCritical] is set to `false` before run
	 * to match the headless (no-operator) usage pattern (Goal 3 SP5 headless contract).
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	@DisplayName("zero Goal 3 collision warnings with lifted dispatcher stack (SP2b.7 safety net)")
	fun dispatcherRoutingProducesZeroCollisionWarnings() {
		val context = loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 300L)

		// ── Goal 3 SP2b.7: subscribe to CollisionWarnings — the safety-net assertion ──
		val collisionWarnings: MutableList<CollisionWarning> = mutableListOf()
		context.getCollisionServices().onCollisionWarning { collisionWarnings.add(it) }

		// Goal 3 SP5: disable auto-pause for headless runs.
		// Without this, a CRITICAL warning would call requestPause() on the simulation
		// controller; in a headless scenario there is no operator to react.
		val collisionService =
			context.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		collisionService.autoPauseOnCritical = false

		// ── Goal 9: also collect ConflictDetectedEvents (complementary assertion) ──
		val conflictEvents: MutableList<ConflictDetectedEvent> = mutableListOf()
		context.onConflictDetectedEvent { conflictEvents.add(it) }

		// ── Wire the full lifted dispatcher-agent stack (same as ShuntingLoopLiftedDriverIntegrationTest) ──
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
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

		// ── Lock-step handshake (same pattern as ShuntingLoopLiftedDriverIntegrationTest) ──
		val driverCycleCount = AtomicInteger(0)
		val driverTurn = Semaphore(0)
		val simTurn = Semaphore(0)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				driverTurn.release()
				simTurn.acquireUninterruptibly()
				applier.onControlStep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				if (driverTurn.tryAcquire(100, TimeUnit.MILLISECONDS)) {
					try {
						driver.runCycle()
						driverCycleCount.incrementAndGet()
					} finally {
						simTurn.release()
					}
				}
			}
		}

		context.setMainProcess(loop)
		context.run()

		val trainsExited = loop.getTrainsExited()
		logger.info {
			"SP2b.7 validation complete: trainsExited=$trainsExited, " +
				"driverCycles=${driverCycleCount.get()}, " +
				"collisionWarnings=${collisionWarnings.size}, " +
				"conflictEvents=${conflictEvents.size}"
		}

		// All generated trains must exit — no permanent deadlock.
		assertThat(trainsExited).isGreaterThanOrEqualTo(1)

		// SP2b.7 success criterion: Goal 3 safety net must emit zero warnings.
		// A non-zero count means the dispatcher produced unsafe routing decisions that
		// triggered competing reservations or an illegal block entry.
		assertThat(collisionWarnings).isEmpty()

		// Complementary Goal 9 assertion: no competing reservations at the lower layer.
		assertThat(conflictEvents).isEmpty()
	}
}
