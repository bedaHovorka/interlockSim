/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1376: characterization of the generated timetables before the pair-selection seam.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Pins the first [TRAINS] timetables [Generator] draws on `vyhybna.xml` for seeds 0 and 1163
 * (Issue #1376): origin, destination, arrival and departure time, as exact literals.
 *
 * Written on the unchanged generator, before the pair-selection seam
 * ([Generator.isPairAcceptable]) existed, so it shows that with the default accept-all hook the
 * seam draws the same random numbers in the same order and picks the same pairs. The cross-side
 * case (four InOuts) is pinned end-to-end by [CervenyUjezdRunTest].
 *
 * [RecordingGenerator] records each generated train instead of activating it; a small driver
 * process stops the run once the generator has made its trains.
 */
class GeneratorTimetableCharacterizationTest : CommonKoinTestBase() {
	private companion object {
		private const val TRAINS: Int = 5
		private const val SEED_HISTORICAL: Long = 0L
		private const val SEED_SP3: Long = 1163L

		/** The two InOuts of `vyhybna.xml`. */
		private const val IN_A: String = "A"
		private const val IN_B: String = "B"

		/** Times are compared with this tolerance: the native target's libm may differ in the last bits. */
		private const val TIME_TOLERANCE: Double = 1e-9
	}

	/** One generated timetable. */
	private data class Drawn(
		val from: String,
		val to: String,
		val timeIn: Double,
		val timeOut: Double
	)

	/** A [Generator] that records each train it would place instead of activating it. */
	private class RecordingGenerator(
		env: SimulationEnvironment,
		seed: Long
	) : Generator(env, seed = seed, maxTrains = TRAINS) {
		val drawn = mutableListOf<Drawn>()

		override fun placeTrain(train: Train) {
			drawn +=
				Drawn(
					train.timetableOriginName,
					train.timetableDestinationName,
					train.scheduledDepartureTime,
					train.scheduledArrivalTime
				)
		}
	}

	/** Starts [generator] and stops the simulation once it has made [TRAINS] trains. */
	private class Driver(
		private val env: SimulationEnvironment,
		private val generator: RecordingGenerator
	) : LoopProcess() {
		override suspend fun startAction() {
			Process.activate(generator)
		}

		override suspend fun iteration() {
			hold(1.0)
		}

		override suspend fun interLoopSleep() {
			if (generator.drawn.size >= TRAINS) {
				generator.terminate()
				env.stop()
				return
			}
			hold(1.0)
		}
	}

	private fun drawTimetables(seed: Long): List<Drawn> {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.VYHYBNA_XML, get()).tracked()
		ctx.getInOuts()
		val generator = RecordingGenerator(ctx, seed)
		ctx.setMainProcess(Driver(ctx, generator))
		ctx.run()
		return generator.drawn.toList()
	}

	private fun assertDrawn(
		actual: List<Drawn>,
		expected: List<Drawn>
	) {
		assertThat(actual, name = "generated timetables").hasSize(expected.size)
		assertThat(actual.map { it.from to it.to }, name = "generated pairs")
			.containsExactly(*expected.map { it.from to it.to }.toTypedArray())
		actual.zip(expected).forEachIndexed { index, (got, want) ->
			assertThat(got.timeIn, name = "arrival time of train $index").isCloseTo(want.timeIn, TIME_TOLERANCE)
			assertThat(got.timeOut, name = "departure time of train $index").isCloseTo(want.timeOut, TIME_TOLERANCE)
		}
	}

	@Test
	fun seed1163DrawsThePinnedTimetables() {
		assertDrawn(
			drawTimetables(SEED_SP3),
			listOf(
				Drawn(IN_B, IN_A, 17.67464481689875, 32.18972943135027),
				Drawn(IN_B, IN_A, 28.052346068782743, 40.230614631672935),
				Drawn(IN_A, IN_B, 67.21987277707687, 88.87678287474034),
				Drawn(IN_A, IN_B, 84.50001586376331, 94.44772294165091),
				Drawn(IN_B, IN_A, 175.56229392945482, 190.85157891933082)
			)
		)
	}

	@Test
	fun seedZeroDrawsThePinnedTimetables() {
		assertDrawn(
			drawTimetables(SEED_HISTORICAL),
			listOf(
				Drawn(IN_B, IN_A, 20.730270361449314, 37.56886866379479),
				Drawn(IN_A, IN_B, 70.41563833111996, 76.99857703728325),
				Drawn(IN_B, IN_A, 101.8521674634733, 114.15985791798455),
				Drawn(IN_B, IN_A, 147.4452512123247, 162.6471214278352),
				Drawn(IN_A, IN_B, 146.1333802028958, 156.86575335111962)
			)
		)
	}
}
