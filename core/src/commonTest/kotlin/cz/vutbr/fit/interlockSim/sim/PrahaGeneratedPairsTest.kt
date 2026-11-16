/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1376: the Praha arms are generated only switch-legal endpoint pairs.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * The Praha Station variant (`praha-hlavni-nadrazi-station.xml`) has cross-side InOut pairs with a
 * topological path but no switch-legal route (Issue #1376): a train generated for such a pair can
 * never reach its destination. With [SwitchLegalPairs] as the [Generator.isPairAcceptable] hook,
 * every generated pair has a route ([cz.vutbr.fit.interlockSim.context.RouteFinder.isRouteAvailable],
 * as in [MultiTrainStressSpecsTest]).
 *
 * Measured on the variant (11 InOuts: 5 north, orientation `false`; 6 south, orientation `true`):
 * of the 30 north-to-south pairs [NORTH_TO_SOUTH_LEGAL] are switch-legal, of the 30 south-to-north
 * pairs [SOUTH_TO_NORTH_LEGAL].
 */
class PrahaGeneratedPairsTest : CommonKoinTestBase() {
	private companion object {
		val SEEDS: LongRange = 0L..9L

		/** The Praha arms' train count, `ExampleRegistry.PRAHA_MAX_TRAINS`. */
		const val TRAINS: Int = 8

		const val NORTH_TO_SOUTH_LEGAL: Int = 15
		const val SOUTH_TO_NORTH_LEGAL: Int = 15
	}

	/** A [Generator] with an optional hook that records each generated pair instead of placing the train. */
	private class RecordingGenerator(
		env: SimulationEnvironment,
		seed: Long,
		private val accept: ((DynamicInOut, DynamicInOut) -> Boolean)?
	) : Generator(env, seed = seed, maxTrains = TRAINS) {
		val pairs = mutableListOf<Pair<DynamicInOut, DynamicInOut>>()

		override fun isPairAcceptable(
			from: DynamicInOut,
			to: DynamicInOut
		): Boolean = accept?.invoke(from, to) ?: super.isPairAcceptable(from, to)

		override fun placeTrain(train: Train) {
			pairs += train.originInOut to checkNotNull(env.getInOuts().firstOrNull { it.name == train.timetableDestinationName })
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
			if (generator.pairs.size >= TRAINS) {
				generator.terminate()
				env.stop()
				return
			}
			hold(1.0)
		}
	}

	private fun load(): DefaultSimulationContext {
		val ctx =
			CommonTestFixtures
				.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get())
				.tracked()
		ctx.getInOuts()
		return ctx
	}

	private fun hasRoute(
		ctx: DefaultSimulationContext,
		pair: Pair<DynamicInOut, DynamicInOut>
	): Boolean = ctx.getRouteFinder().isRouteAvailable(pair.first.staticRef, pair.second.staticRef, ctx)

	/** The pairs [TRAINS] generated trains get for [seed], with [SwitchLegalPairs] as hook or accept-all. */
	private fun generatedPairs(
		seed: Long,
		switchLegalOnly: Boolean
	): List<String> {
		val ctx = load()
		val legal = SwitchLegalPairs(ctx)
		val generator = RecordingGenerator(ctx, seed, if (switchLegalOnly) legal::accepts else null)
		ctx.setMainProcess(Driver(ctx, generator))
		ctx.run()
		assertThat(generator.pairs, name = "pairs generated for seed $seed").hasSize(TRAINS)
		return generator.pairs.filterNot { hasRoute(ctx, it) }.map { "seed $seed: ${it.first.name} -> ${it.second.name}" }
	}

	@Test
	fun everyPairGeneratedWithTheSwitchLegalHookHasARoute() {
		val withoutRoute = SEEDS.flatMap { generatedPairs(it, switchLegalOnly = true) }

		assertThat(withoutRoute, name = "generated pairs without a switch-legal route").isEmpty()
	}

	@Test
	fun withoutTheHookTheSameSeedsGeneratePairsWithoutARoute() {
		val withoutRoute = SEEDS.flatMap { generatedPairs(it, switchLegalOnly = false) }

		assertThat(withoutRoute, name = "accept-all pairs without a switch-legal route").isNotEmpty()
	}

	@Test
	fun eachDirectionHasSwitchLegalPairs() {
		val ctx = load()
		val legal = SwitchLegalPairs(ctx)
		val inOuts = ctx.getInOuts().toList()
		val north = inOuts.filter { !it.getOrientation() }
		val south = inOuts.filter { it.getOrientation() }

		val northToSouth = north.sumOf { from -> south.count { to -> legal.accepts(from, to) } }
		val southToNorth = south.sumOf { from -> north.count { to -> legal.accepts(from, to) } }

		assertThat(north, name = "north InOuts").hasSize(5)
		assertThat(south, name = "south InOuts").hasSize(6)
		assertThat(northToSouth, name = "switch-legal north-to-south pairs").isEqualTo(NORTH_TO_SOUTH_LEGAL)
		assertThat(southToNorth, name = "switch-legal south-to-north pairs").isEqualTo(SOUTH_TO_NORTH_LEGAL)
	}
}
