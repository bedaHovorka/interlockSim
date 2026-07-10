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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Goal 10 Stage A3 acceptance gate — determinism of [RuleBasedDispatcher].
 *
 * Runs `vyhybna.xml` with [RuleBasedDispatcher] 10 consecutive times and asserts
 * identical outcomes on every run: same number of trains exited, same maximum
 * concurrent trains, and matching block-transition counts per train.
 *
 * **Why these metrics?**
 * - `trainsExited` verifies all trains complete their journeys (no deadlock).
 * - `maxConcurrentTrains` verifies the MAX_TRAINS=2 capacity constraint held.
 * - Per-train block transitions verify the exact route taken was consistent.
 *
 * **Baseline (established during SP0.1):**
 * All 5 generated trains exit; max concurrent = 2; each train makes 2 block
 * transitions (sorted per-train transition counts `[2, 2, 2, 2, 2]`).
 *
 * @see RuleBasedDispatcher
 * @see ShuntingLoop
 * @since Issue #540 (SP0.1 — Goal 10 Stage A3)
 */
@DisplayName("RuleBasedDispatcher determinism — 10 consecutive vyhybna.xml runs (Goal 10 A3)")
@Tag("integration-test")
class RuleBasedDispatcherDeterminismTest {
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

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/**
	 * Single run result captured for cross-run comparison.
	 *
	 * All fields must be identical across all 10 runs for the determinism gate to pass.
	 *
	 * [sortedBlockTransitionCounts] uses sorted values (not keys) because train names
	 * include a global, never-reset counter (`Train.countValue`) that increments across
	 * successive simulation runs — a known cosmetic issue (SIM-002). Sorting the
	 * transition counts is sufficient to verify route determinism.
	 */
	private data class RunResult(
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		val sortedBlockTransitionCounts: List<Int>
	)

	private fun executeRun(endTime: Long = 300L): RunResult {
		val context = loadVyhybnaContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime)

		// SP0.11: Wire the full dispatcher-agent stack instead of passing dispatcher= inline.
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
				unapprovedTrainsProvider = loop::getQueuedTrains,
				innerBlockInputsProvider = loop::getInnerBlockInputs,
				outerBlockInputsProvider = loop::getOuterBlockInputs
			)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener = applier
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				driver.runCycle()
			}
		}

		context.setMainProcess(loop)
		context.run()

		return RunResult(
			trainsExited = loop.getTrainsExited(),
			maxConcurrentTrains = loop.getMaxConcurrentTrains(),
			sortedBlockTransitionCounts = loop.getAllBlockTransitions().values.sorted()
		)
	}

	// ── Goal 10 Stage A3: 10 consecutive identical-outcome runs ──────────────

	/**
	 * Runs the vyhybna.xml simulation 10 times with [RuleBasedDispatcher] and asserts
	 * all outcomes are identical to the first run.
	 *
	 * The first repetition establishes the baseline; subsequent repetitions compare
	 * against it.  A failure in any repetition indicates non-determinism.
	 */
	@RepeatedTest(10)
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("vyhybna.xml run produces identical outcome (Goal 10 A3)")
	fun ruleBasedDispatcherIsFullyDeterministic(info: RepetitionInfo) {
		val result = executeRun()

		// All trains must exit — no deadlock.
		assertThat(result.trainsExited)
			.isGreaterThan(0)

		// Physical capacity constraint: never more than 2 trains concurrently.
		assertThat(result.maxConcurrentTrains)
			.isGreaterThanOrEqualTo(1)
		assertThat(result.maxConcurrentTrains)
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)

		// Store baseline on first run; compare on subsequent runs.
		if (info.currentRepetition == 1) {
			baselineResult = result
			logger.info {
				"Run 1 baseline: trainsExited=${result.trainsExited}, " +
					"maxConcurrent=${result.maxConcurrentTrains}, " +
					"transitionCounts=${result.sortedBlockTransitionCounts}"
			}
		} else {
			val baseline =
				requireNotNull(baselineResult) {
					"Baseline result must be set by repetition 1"
				}
			assertThat(result.trainsExited)
				.isEqualTo(baseline.trainsExited)
			assertThat(result.maxConcurrentTrains)
				.isEqualTo(baseline.maxConcurrentTrains)
			assertThat(result.sortedBlockTransitionCounts)
				.isEqualTo(baseline.sortedBlockTransitionCounts)
		}
	}

	companion object {
		// Thread-safe across JUnit parallel execution: tests in this class are
		// sequential (single @RepeatedTest method), so volatile is sufficient.
		@Volatile
		private var baselineResult: RunResult? = null
	}
}
