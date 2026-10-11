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
 * **Contract (Issue #1179, Goal 9B demand 3):** every spec asks for an `(entry, exit)` pair that
 * has a switch-legal route on Praha ([MultiTrainStressSpecs]), so all 20 trains enter and exit and
 * no [MultiTrainLoop.EntryFailure] is recorded. Until #1179 the scenario rotated over the product
 * of the north entries and the south exits; 13 of those 20 pairs had no switch-legal route and
 * were refused with `NO_ROUTE` on the first attempt, so at most 7 trains ever ran. The fast
 * `MultiTrainStressSpecsTest` guard keeps the pairs legal without running this scenario.
 *
 * Reusing a legal pair serializes the trains that share it, so the scenario needs a long
 * simulated horizon: [TWENTY_TRAIN_END_TIME] and a matching [MAX_ENTRY_ATTEMPTS] (one attempt per
 * two simulated seconds) give every train room to obtain its entry route and complete its run.
 */
@Tag("heavy-test")
@DisplayName("MultiTrainLoop — 20-train Praha stress (heavy, manual only)")
class MultiTrainScaleStressTest : KoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		private const val TWENTY_TRAIN_END_TIME: Long = 3600L
		private const val TRAINS: Int = MultiTrainStressSpecs.TRAINS

		private const val MAX_CONCURRENT_TRAINS: Int = 20

		/**
		 * Entry attempts per train before the run records a structured failure. One attempt per
		 * two simulated seconds, so the cap covers the whole [TWENTY_TRAIN_END_TIME] horizon:
		 * a train that waits for a route reused by an earlier train is never given up early.
		 */
		private const val MAX_ENTRY_ATTEMPTS: Int = 1800
		private const val STRESS_RUNS: Int = 10
		private const val MIN_REAL_TIME_RATIO: Double = 1.0
		private const val TIMEOUT_SECONDS: Long = 1200L
	}

	@Test
	@Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("twentyTrainStress: all 20 trains enter and exit, no entry failure, 10 runs")
	fun twentyTrainStress() {
		val ratios = mutableListOf<Double>()
		repeat(STRESS_RUNS) { runIndex -> ratios.add(runStressRun(runIndex)) }

		logger.info {
			"20-train Praha stress aggregate: runs=$STRESS_RUNS, minRatio=${ratios.minOrNull()}, " +
				"meanRatio=${ratios.average()}, maxRatio=${ratios.maxOrNull()}"
		}
	}

	private fun runStressRun(runIndex: Int): Double {
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
				trainSpecs = MultiTrainStressSpecs.twentyTrainSpecs(),
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS,
				maxEntryAttempts = MAX_ENTRY_ATTEMPTS
			)
		ctx.setMainProcess(loop)

		val startMs = System.currentTimeMillis()
		ctx.run()
		val wallSeconds = (System.currentTimeMillis() - startMs) / 1000.0
		val realTimeRatio = TWENTY_TRAIN_END_TIME / wallSeconds

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
		return realTimeRatio
	}
}
