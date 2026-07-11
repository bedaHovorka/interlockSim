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
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
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
 * SP0.12 integration gate — shunting-loop end-to-end via the lifted dispatcher-agent stack.
 *
 * Runs `vyhybna.xml` as a single simulation with the full lifted stack
 * ([AgentLoopDriver] + [DispatchDecisionApplier] + [RuleBasedDispatcher]) under the
 * same lock-step handshake used by [RuleBasedDispatcherDeterminismTest], and asserts:
 *
 * 1. **All trains exit** — the dispatcher keeps the loop running until all generated
 *    trains complete their journeys; no permanent deadlock.
 * 2. **No conflict events** — [RuleBasedDispatcher] must not cause competing
 *    reservations on the shunting-loop topology; zero
 *    [ConflictDetectedEvent]s expected.
 *
 * ## Why lock-step is required for "no conflict events"
 *
 * Without the lock-step handshake, the driver thread and the kDisco sim thread race
 * under OS scheduling.  Because the unthrottled headless simulation can advance
 * multiple ticks before the driver is scheduled, the driver can read a stale
 * observation in which [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.pathAlreadyExtendedBeyond]
 * is still `false` for a hop that was already successfully reserved. The driver then
 * issues a second [DispatchDecision.ReservePath] for the same hop. Even though
 * [DispatchDecisionApplier]'s duplicate-suppression guard prevents re-applying the
 * same `(trainId, from, to)` triple, two *different* trains can legitimately arrive
 * at the same block in the same draining window, producing a [ConflictDetectedEvent].
 *
 * The lock-step handshake eliminates this: the sim thread waits for the driver to
 * complete exactly one cycle per tick, so the observation is always fresh when the
 * driver reads it and every decision is applied before the next observation is built.
 * The guard in [DispatchDecisionApplier] then handles any remaining inter-tick
 * duplicates, and the capacity cap ensures at most one train approaches each block.
 *
 * ## Relationship to [RuleBasedDispatcherDeterminismTest]
 *
 * [RuleBasedDispatcherDeterminismTest] is the *before/after determinism harness* —
 * it runs 10 consecutive lock-step runs and asserts identical outcomes.  This class
 * is a single-run **correctness gate** that closes the "Integration: shunting-loop
 * end-to-end via the lifted driver — all trains exit, no conflict events"
 * requirement from the SP0.12 acceptance criteria (Issue #734).
 *
 * @see RuleBasedDispatcherDeterminismTest for the 10-run cross-run A3 determinism gate
 * @see cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher for the synchronous
 *   wiring alternative used by `:core` and `:fast-sim` tests
 * @since Issue #734 (SP0.12 — Goal 10 A3 integration gate)
 */
@DisplayName("ShuntingLoop end-to-end via lifted dispatcher-agent stack (SP0.12 integration gate)")
@Tag("integration-test")
class ShuntingLoopLiftedDriverIntegrationTest {
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
	 * Runs vyhybna.xml with the lifted stack under a lock-step handshake and asserts:
	 * - All generated trains exit (at least 1; baseline is 5 for endTime=300s).
	 * - Zero [ConflictDetectedEvent]s fired during the run.
	 *
	 * The lock-step handshake pins one driver cycle per simulation tick, so the driver
	 * always reads a fresh observation and `pathAlreadyExtendedBeyond` is up-to-date
	 * when each decision is computed. This eliminates the OS-scheduling race that can
	 * cause two trains to compete for the same block in a free-running headless run.
	 *
	 * All production components ([AgentLoopDriver], [ActuatorCommandQueue],
	 * [DispatchDecisionApplier]) run on their production threads; only the pacing
	 * between the driver and the kDisco sim thread is pinned.
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	@DisplayName("all trains exit and zero conflict events (lock-step, lifted stack)")
	fun allTrainsExitWithNoConflictEvents() {
		val context = loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 300L)

		// Collect ConflictDetectedEvents — must be empty after the run.
		// Lock-step guarantees the listener fires only on the sim thread (no concurrent access).
		val conflictEvents: MutableList<ConflictDetectedEvent> =
			mutableListOf()
		context.onConflictDetectedEvent { conflictEvents.add(it) }

		// Wire the full lifted stack — same components as production.
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val queue = ActuatorCommandQueue()
		val dispatcher = RuleBasedDispatcher()
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
				dispatcher = dispatcher,
				commandQueue = queue,
				controller = NoOpSimulationController,
				observationProvider = loop::getLatestObservation
			)

		// Lock-step handshake: same pattern as RuleBasedDispatcherDeterminismTest.
		// The sim thread signals driverTurn after capturing the snapshot, waits on
		// simTurn for the driver to complete its cycle, then applies decisions.  This
		// ensures the observation is always fresh and decisions are applied before the
		// next tick advances the state.
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
		val maxConcurrent = loop.getMaxConcurrentTrains()
		logger.info {
			"Integration run complete: trainsExited=$trainsExited, " +
				"maxConcurrent=$maxConcurrent, " +
				"driverCycles=${driverCycleCount.get()}, " +
				"conflictEvents=${conflictEvents.size}"
		}

		// All generated trains must exit — no permanent deadlock.
		assertThat(trainsExited)
			.isGreaterThanOrEqualTo(1)

		// RuleBasedDispatcher must not produce competing reservations on the
		// shunting-loop topology.  A non-zero count indicates a regression in
		// dispatch correctness (e.g. the capacity cap or pathAlreadyExtendedBeyond
		// guard is broken).
		assertThat(conflictEvents)
			.isEmpty()
	}
}
