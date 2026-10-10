/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP7 (#591) / Goal 1B SP1 (#1148): 20-train stress on the Praha fixture.
 * Heavy variant — manual only, see CLAUDE.md "heavyTest" section.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 20-train stress scenario for Goal 1 multi-train simulation on the station-scale Praha
 * fixture (`praha-hlavni-nadrazi.xml`).
 *
 * Split out of the common `MultiTrainScaleValidationTest` (Issue #1148): JUnit `@Tag` and
 * `@Timeout` exist only on the JVM. Tagged `heavy-test`, so `test`, `integrationTest` and CI
 * never run it; launch it deliberately with:
 * ```
 * ./gradlew :core:heavyTest
 * ```
 *
 * Before Issue #1148 this scenario froze the simulation clock inside one dispatcher event and
 * ran into the 10-minute CI step timeout. The bounded entry reservation in [MultiTrainLoop]
 * removed the hot loop; the `@Timeout` turns any remaining slowness into a named failure
 * instead of a hang. `SEPARATE_THREAD` makes the timeout fire even while the CPU-bound
 * simulation never checks the interrupt flag.
 *
 * **Expected outcome today:** failure. Full entry-to-exit reservation bounds concurrency to the
 * number of block-disjoint routes on Praha, and every blocked entry attempt still pays one full
 * route search, so twenty trains cannot all exit by `endTime`. Goal 1B tracks the work that
 * makes this scenario pass.
 */
@Tag("heavy-test")
@DisplayName("MultiTrainLoop — 20-train Praha stress (heavy, manual only)")
class MultiTrainScaleStressTest : KoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		private const val TWENTY_TRAIN_END_TIME: Long = 1200L
		private const val HEADWAY_SECONDS: Double = 5.0
		private const val TRAIN_LENGTH: Double = 40.0
		private const val TRAINS: Int = 20
		private const val MAX_CONCURRENT_TRAINS: Int = 20

		/** Entry attempts per train before the run records a structured failure. */
		private const val MAX_ENTRY_ATTEMPTS: Int = 60
		private const val STRESS_RUNS: Int = 10
		private const val MIN_REAL_TIME_RATIO: Double = 1.0
		private const val TIMEOUT_SECONDS: Long = 120L

		private val NORTH_ENTRIES = listOf("N-Lib-1", "N-Lib-2", "N-Vys-1", "N-Vys-2", "N-Bypass")
		private val SOUTH_EXITS = listOf("S-Vin-1", "S-Vin-2", "S-Vrs-1", "S-Vrs-2", "S-Vrs-3", "S-Bypass")

		private fun twentyTrainSpecs(): List<MultiTrainLoop.TrainSpec> =
			List(TRAINS) { i ->
				MultiTrainLoop.TrainSpec(
					inName = NORTH_ENTRIES[i % NORTH_ENTRIES.size],
					outName = SOUTH_EXITS[i % SOUTH_EXITS.size],
					inTime = i * HEADWAY_SECONDS,
					length = TRAIN_LENGTH
				)
			}
	}

	@Test
	@Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("twentyTrainStress: 20 trains enter and exit Praha at real-time ratio >= 1, 10 runs")
	fun twentyTrainStress() {
		val ratios = mutableListOf<Double>()
		repeat(STRESS_RUNS) { runIndex ->
			val ctx =
				CommonTestFixtures
					.parseSimulationContext(
						NetworkResources.PRAHA_HLAVNI_NADRAZI_XML,
						getKoin().get<SimulationProcessFactory>()
					).tracked()
			ctx.getInOuts() // initialize dynamic wrappers
			val loop =
				MultiTrainLoop(
					ctx,
					endTime = TWENTY_TRAIN_END_TIME,
					trainSpecs = twentyTrainSpecs(),
					maxConcurrentTrains = MAX_CONCURRENT_TRAINS,
					maxEntryAttempts = MAX_ENTRY_ATTEMPTS
				)
			ctx.setMainProcess(loop)

			val startMs = System.currentTimeMillis()
			ctx.run()
			val wallSeconds = (System.currentTimeMillis() - startMs) / 1000.0
			val realTimeRatio = TWENTY_TRAIN_END_TIME / wallSeconds
			ratios.add(realTimeRatio)

			logger.info {
				"20-train Praha stress run ${runIndex + 1}/$STRESS_RUNS: " +
					"entered=${loop.getTrainsEntered()}, exited=${loop.getTrainsExited()}, " +
					"maxConcurrent=${loop.getMaxConcurrentTrains()}, occupied=${loop.getOccupiedResourceCount()}, " +
					"entryFailures=${loop.getEntryFailures().size}, wall=${wallSeconds}s, ratio=$realTimeRatio"
			}
			assertThat(loop.getEntryFailures(), name = "entry failures").isEmpty()
			assertThat(loop.getTrainsEntered(), name = "trains entered").isEqualTo(TRAINS)
			assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAINS)
			assertThat(loop.getOccupiedResourceCount(), name = "occupied resources").isZero()
			assertThat(realTimeRatio, name = "real-time ratio").isGreaterThanOrEqualTo(MIN_REAL_TIME_RATIO)
			ctx.close()
		}

		logger.info {
			"20-train Praha stress aggregate: runs=$STRESS_RUNS, minRatio=${ratios.minOrNull()}, " +
				"meanRatio=${ratios.average()}, maxRatio=${ratios.maxOrNull()}"
		}
	}
}
