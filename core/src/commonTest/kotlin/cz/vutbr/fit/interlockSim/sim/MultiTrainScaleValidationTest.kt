/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP7: 5-train correctness and 20-train performance scale validation (#591).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import kotlin.test.Test

/**
 * Scale validation for Goal 1 multi-train simulation.
 *
 * Runs the existing [MultiTrainLoop] on the existing Praha fixture to verify
 * 5-train correctness and 20-train performance on a topology large enough to
 * avoid trivial contention.
 */
class MultiTrainScaleValidationTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		private const val FIVE_TRAIN_END_TIME: Long = 400L
		private const val TWENTY_TRAIN_END_TIME: Long = 1200L
		private const val HEADWAY_SECONDS: Double = 5.0
		private const val TRAIN_LENGTH: Double = 40.0
		private const val MAX_CONCURRENT_TRAINS: Int = 20
		private const val STRESS_RUNS: Int = 10
		private const val MIN_REAL_TIME_RATIO: Double = 1.0

		private val NORTH_ENTRIES = listOf("N-Lib-1", "N-Lib-2", "N-Vys-1", "N-Vys-2", "N-Bypass")
		private val SOUTH_EXITS = listOf("S-Vin-1", "S-Vin-2", "S-Vrs-1", "S-Vrs-2", "S-Vrs-3", "S-Bypass")

		private fun twentyTrainSpecs(): List<MultiTrainLoop.TrainSpec> =
			List(20) { i ->
				MultiTrainLoop.TrainSpec(
					inName = NORTH_ENTRIES[i % NORTH_ENTRIES.size],
					outName = SOUTH_EXITS[i % SOUTH_EXITS.size],
					inTime = i * HEADWAY_SECONDS,
					length = TRAIN_LENGTH
				)
			}

		private fun fiveTrainSpecs(): List<MultiTrainLoop.TrainSpec> =
			listOf(
				MultiTrainLoop.TrainSpec("N-Lib-1", "S-Vin-1", 0.0, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Lib-2", "S-Vin-2", HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-1", "S-Vrs-1", 2 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-2", "S-Vrs-2", 3 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Bypass", "S-Bypass", 4 * HEADWAY_SECONDS, TRAIN_LENGTH)
			)
	}

	private val processFactory: SimulationProcessFactory by inject()

	private fun loadPrahaContext(): DefaultSimulationContext {
		val simCtx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.PRAHA_HLAVNI_NADRAZI_XML,
				processFactory
			)
		simCtx.getInOuts() // initialize dynamic wrappers
		return simCtx
	}

	private data class ScenarioResult(
		val wallSeconds: Double,
		val realTimeRatio: Double,
		val trainsEntered: Int,
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		val occupiedResources: Int
	)

	private fun runScenario(
		endTime: Long,
		specs: List<MultiTrainLoop.TrainSpec>
	): ScenarioResult {
		val context = loadPrahaContext()
		context.use { ctx ->
			val startMs = currentTimeMillisKMP()
			val process =
				MultiTrainLoop(
					ctx,
					endTime = endTime,
					trainSpecs = specs,
					maxConcurrentTrains = MAX_CONCURRENT_TRAINS
				)
			ctx.setMainProcess(process)
			ctx.run()
			val wallSeconds = (currentTimeMillisKMP() - startMs) / 1000.0
			val realTimeRatio = endTime / wallSeconds
			return ScenarioResult(
				wallSeconds = wallSeconds,
				realTimeRatio = realTimeRatio,
				trainsEntered = process.getTrainsEntered(),
				trainsExited = process.getTrainsExited(),
				maxConcurrentTrains = process.getMaxConcurrentTrains(),
				occupiedResources = process.getOccupiedResourceCount()
			)
		}
	}

	@Test
	fun fiveTrainCompleteness() {
		val result = runScenario(FIVE_TRAIN_END_TIME, fiveTrainSpecs())
		logger.info {
			"5-train Praha correctness: " +
				"entered=${result.trainsEntered}, exited=${result.trainsExited}, " +
				"maxConcurrent=${result.maxConcurrentTrains}, occupied=${result.occupiedResources}, " +
				"wall=${result.wallSeconds}s, ratio=${result.realTimeRatio}"
		}
		assertThat(result.trainsEntered, name = "trains entered").isEqualTo(5)
		assertThat(result.trainsExited, name = "trains exited").isEqualTo(5)
		assertThat(result.occupiedResources, name = "occupied resources").isZero()
	}

	@Test
	fun twentyTrainStress() {
		val results = mutableListOf<ScenarioResult>()
		repeat(STRESS_RUNS) { runIndex ->
			val result = runScenario(TWENTY_TRAIN_END_TIME, twentyTrainSpecs())
			results.add(result)
			logger.info {
				"20-train Praha stress run ${runIndex + 1}/$STRESS_RUNS: " +
					"entered=${result.trainsEntered}, exited=${result.trainsExited}, " +
					"maxConcurrent=${result.maxConcurrentTrains}, occupied=${result.occupiedResources}, " +
					"wall=${result.wallSeconds}s, ratio=${result.realTimeRatio}"
			}
			assertThat(result.trainsEntered, name = "trains entered").isEqualTo(20)
			assertThat(result.trainsExited, name = "trains exited").isEqualTo(20)
			assertThat(result.occupiedResources, name = "occupied resources").isZero()
			assertThat(result.realTimeRatio, name = "real-time ratio")
				.isGreaterThanOrEqualTo(MIN_REAL_TIME_RATIO)
		}

		val ratios = results.map { it.realTimeRatio }
		logger.info {
			"20-train Praha stress aggregate: " +
				"runs=$STRESS_RUNS, minRatio=${ratios.minOrNull()}, " +
				"meanRatio=${ratios.average()}, maxRatio=${ratios.maxOrNull()}"
		}
	}
}
