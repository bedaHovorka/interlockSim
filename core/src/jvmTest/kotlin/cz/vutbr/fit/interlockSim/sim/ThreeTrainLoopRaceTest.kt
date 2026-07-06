/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP6: 1000-iteration deterministic race test for three-train scenario (Issue #589).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@Tag("integration-test")
@DisplayName("ThreeTrainLoop — 50-iteration deterministic race test (Goal 1 SP6 #589)")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreeTrainLoopRaceTest : KoinTestBase() {
	private data class RunResult(
		val wallMs: Long,
		val trainsEntered: Int,
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		val occupiedResources: Int
	)

	private companion object {
		private val logger = KotlinLogging.logger {}

		/** Number of consecutive runs required to validate stability. */
		const val EXPECTED_RUNS: Int = 50

		/** Simulation end time for each run. */
		const val END_TIME: Long = 600L

		/** Maximum acceptable wall-clock spread between fastest and slowest run (ms). */
		const val WALL_SPREAD_MS: Long = 2000L

		/** Maximum acceptable coefficient of variation for runtime stability. */
		const val MAX_CV: Double = 0.5

		/** Minimum observed peak concurrency to confirm trains actually contended. */
		const val MIN_CONCURRENT_TRAINS: Int = 2

		/**
		 * Number of initial runs discarded as JIT warmup before computing runtime
		 * statistics. Rep 1 runs several times slower than steady state due to JIT
		 * cold start, which would otherwise inflate the wall-clock spread.
		 */
		const val WARMUP_RUNS: Int = 5

		/** Thread-safe collector for per-run results across @RepeatedTest invocations. */
		private val results: ConcurrentLinkedQueue<RunResult> = ConcurrentLinkedQueue()
	}

	private val factory: SimulationContextFactory by inject()

	@BeforeAll
	fun clearResults() {
		results.clear()
	}

	/**
	 * Single race run repeated 50 times.
	 *
	 * Acceptance criteria per run:
	 * - All 3 trains enter and exit.
	 * - No kDisco Resource tokens remain occupied.
	 * - Peak concurrent trains reaches at least 2 (contention/queuing occurred).
	 * - Run completes within the per-run timeout → no deadlock.
	 *
	 * For full 1000-iteration stress testing use [ThreeTrainLoopRaceHeavyTest]
	 * (tagged @Tag("heavy-test")).
	 */
	@Order(1)
	@RepeatedTest(50)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("ThreeTrainLoop run completes cleanly")
	fun eachRunCompletesCleanly() {
		val startNs = System.nanoTime()
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				factory.createContext(stream) as DefaultSimulationContext
			}
		context.use { ctx ->
			// Pre-warm InOut wrappers (matches TwoTrainLoopTest pattern); run()
			// would initialize them internally anyway, so this is harmless.
			ctx.getInOuts()
			val process = ThreeTrainLoop(ctx, endTime = END_TIME)
			ctx.setMainProcess(process)
			ctx.run()
			val wallMs = (System.nanoTime() - startNs) / 1_000_000

			// Record the result before asserting so a failing rep still contributes to
			// the aggregate. Otherwise one per-run failure also trips the count check
			// with a misleading "recorded run count" message instead of the real defect.
			results.add(
				RunResult(
					wallMs = wallMs,
					trainsEntered = process.getTrainsEntered(),
					trainsExited = process.getTrainsExited(),
					maxConcurrentTrains = process.getMaxConcurrentTrains(),
					occupiedResources = process.getOccupiedResourceCount()
				)
			)

			assertThat(process.getTrainsEntered(), name = "trains entered").isEqualTo(3)
			assertThat(process.getTrainsExited(), name = "trains exited").isEqualTo(3)
			assertThat(process.getOccupiedResourceCount(), name = "occupied resources").isZero()
			assertThat(process.getMaxConcurrentTrains(), name = "peak concurrent trains")
				.isGreaterThanOrEqualTo(MIN_CONCURRENT_TRAINS)
		}
	}

	/**
	 * Aggregate validation after all 50 repeated runs.
	 *
	 * Acceptance criteria:
	 * - Exactly 50 results recorded.
	 * - Coefficient of variation of steady-state wall-clock runtimes stays below 0.5.
	 * - Max-min steady-state wall-clock spread stays below 2000 ms.
	 *
	 * Note: with real-time sync disabled the simulation runs as fast as the CPU
	 * allows, so wall-clock variance is dominated by JIT/GC/scheduler noise
	 * rather than simulation work. The first [WARMUP_RUNS] reps are discarded to
	 * remove JIT cold start. These thresholds are environment-dependent and may
	 * need relaxing on heavily loaded shared CI runners; treat CV as the primary
	 * signal and the spread as secondary.
	 */
	@Order(2)
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("50-run aggregate: statistics and runtime stability")
	fun aggregate50RunStatistics() {
		assertThat(results.size, name = "recorded run count").isEqualTo(EXPECTED_RUNS)

		val steady = results.drop(WARMUP_RUNS).map { it.wallMs }
		val minMs = steady.min()
		val maxMs = steady.max()
		val mean = steady.average()
		val stdDev = sqrt(steady.map { (it - mean) * (it - mean) }.average())
		val cv = if (mean > 0.0) stdDev / mean else 0.0

		logger.info {
			"ThreeTrainLoop 50-run race complete: " +
				"runs=$EXPECTED_RUNS (warmup=$WARMUP_RUNS), " +
				"min=${minMs}ms, max=${maxMs}ms, mean=${mean}ms, " +
				"stdDev=${stdDev}ms, CV=$cv"
		}

		assertThat(cv, name = "coefficient of variation").isLessThan(MAX_CV)
		assertThat(maxMs - minMs, name = "wall-clock spread").isLessThan(WALL_SPREAD_MS)
	}
}
