/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP7 (#591) / Goal 1B SP1 (#1148): 5-train correctness on the Praha fixture.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Scale validation for Goal 1 multi-train simulation: five trains on the station-scale
 * Praha fixture (`praha-hlavni-nadrazi.xml`), on every KMP target (JVM `test` and
 * `:core:linuxX64Test`).
 *
 * The five routes are pairwise block-disjoint, so every train obtains its entry route on its
 * first attempt. The attempt cap is small anyway: if a route ever stops being free, the run
 * fails fast with a structured [MultiTrainLoop.EntryFailure] instead of retrying an expensive
 * route search for the rest of the run (Issue #1148).
 *
 * The 20-train stress scenario lives in the JVM-only `MultiTrainScaleStressTest` (tagged
 * `heavy-test`, manual only), because it needs a JUnit `@Timeout`.
 */
class MultiTrainScaleValidationTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		private const val FIVE_TRAIN_END_TIME: Long = 400L
		private const val HEADWAY_SECONDS: Double = 5.0
		private const val TRAIN_LENGTH: Double = 40.0
		private const val MAX_CONCURRENT_TRAINS: Int = 20

		/** Entry attempts per train before the run records a structured failure. */
		private const val MAX_ENTRY_ATTEMPTS: Int = 10

		/** Goal 1B B1 wall-time budget for this scenario on every target. */
		private const val WALL_BUDGET_SECONDS: Double = 60.0

		private const val TRAINS: Int = 5
	}

	@Test
	fun fiveTrainCompleteness() {
		val specs =
			listOf(
				MultiTrainLoop.TrainSpec("N-Lib-1", "S-Vin-1", 0.0, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Lib-2", "S-Vin-2", HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-1", "S-Vrs-1", 2 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-2", "S-Vrs-2", 3 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Bypass", "S-Bypass", 4 * HEADWAY_SECONDS, TRAIN_LENGTH)
			)
		val ctx =
			CommonTestFixtures
				.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, get())
				.tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = FIVE_TRAIN_END_TIME,
				trainSpecs = specs,
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS,
				maxEntryAttempts = MAX_ENTRY_ATTEMPTS
			)
		ctx.setMainProcess(loop)

		val startMs = currentTimeMillisKMP()
		ctx.run()
		val wallSeconds = (currentTimeMillisKMP() - startMs) / 1000.0

		logger.info {
			"5-train Praha correctness: " +
				"entered=${loop.getTrainsEntered()}, exited=${loop.getTrainsExited()}, " +
				"maxConcurrent=${loop.getMaxConcurrentTrains()}, occupied=${loop.getOccupiedResourceCount()}, " +
				"entryFailures=${loop.getEntryFailures()}, wall=${wallSeconds}s"
		}
		assertThat(loop.getEntryFailures(), name = "entry failures").isEmpty()
		assertThat(loop.getTrainsEntered(), name = "trains entered").isEqualTo(TRAINS)
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAINS)
		assertThat(loop.getOccupiedResourceCount(), name = "occupied resources").isZero()
		assertThat(wallSeconds, name = "wall seconds").isLessThanOrEqualTo(WALL_BUDGET_SECONDS)
	}
}
