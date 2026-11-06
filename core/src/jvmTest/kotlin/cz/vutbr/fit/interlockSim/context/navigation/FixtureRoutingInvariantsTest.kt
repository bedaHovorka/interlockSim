/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP6.2 (#1166): routing invariants that hold on any fixture.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.component.get
import java.util.stream.Stream

/**
 * Three routing invariants that no fixture may break, written once and run over `vyhybna.xml`,
 * `cerveny_ujezd.xml` and `praha-hlavni-nadrazi.xml` (Issue #1166, SP6.2):
 *
 * 1. a reserved route is contiguous from its origin to its target;
 * 2. releasing a route leaves no block or switch ownership and no non-FREE block behind;
 * 3. a denied request changes nothing in the registry or on the track.
 *
 * Every case names an `(entry, exit)` pair with a switch-legal route (measured with a throwaway
 * sweep over all ordered InOut pairs: vyhybna 2, cerveny_ujezd 8, Praha 30). Praha adds four curated
 * pairs: the shortest (`N-Bypass -> S-Bypass`), the many-switch pair of the B5 test
 * (`N-Lib-1 -> S-Vin-1`),
 * a pair with the most routes (`N-Vys-2 -> S-Vrs-3`, 16 per the SP4 doc) and its reverse, whose start
 * has the largest enumeration (`S-Vrs-3`, 236 nodes). The no-route pair `N-Bypass -> S-Vrs-3` is
 * covered by [PrahaBoundedRouteSearchTest].
 */
class FixtureRoutingInvariantsTest : CommonKoinTestBase() {
	/** A fixture, its XML and one switch-legal InOut pair on it. */
	data class Case(
		val label: String,
		val xml: () -> String,
		val entry: String,
		val exit: String
	) {
		override fun toString(): String = "$label $entry -> $exit"
	}

	/** Everything the registry and the track say about ownership, comparable by equality. */
	private data class OwnershipSnapshot(
		val blockOwners: Map<DynamicTrackBlock, String?>,
		val blockStates: Map<DynamicTrackBlock, TrackFacility.State>,
		val switchOwners: Map<DynamicRailSwitch, String?>
	)

	private class Fixture(
		val ctx: DefaultSimulationContext,
		val registry: PathReservationRegistry,
		val service: PathReservationService,
		val entry: DynamicInOut,
		val exit: DynamicInOut,
		val inOuts: List<DynamicInOut>
	) {
		val blocks: List<DynamicTrackBlock> =
			ctx.getGraph().values().filterIsInstance<DynamicTrackBlock>()
		val switches: List<DynamicRailSwitch> = ctx.cellsOfType<DynamicRailSwitch>()

		fun snapshot(): OwnershipSnapshot =
			OwnershipSnapshot(
				blockOwners = blocks.associateWith { registry.getOwner(it) },
				blockStates = blocks.associateWith { it.getState() },
				switchOwners = switches.associateWith { registry.getSwitchOwner(it) }
			)
	}

	private fun load(case: Case): Fixture {
		val ctx = CommonTestFixtures.parseSimulationContext(case.xml(), get()).tracked()
		val inOuts =
			ctx
				.getInOuts()
				.map { ctx.toDynamic(it) }
				.filterIsInstance<DynamicInOut>()
				.associateBy { it.name }
		return Fixture(
			ctx,
			ctx.scope.get(),
			ctx.getRoutingServices().getPathReservationService(),
			inOuts.getValue(case.entry),
			inOuts.getValue(case.exit),
			inOuts.values.toList()
		)
	}

	private fun endsOf(block: DynamicTrackBlock): Set<DynamicPathSeparator> =
		block.ends().filterIsInstance<DynamicPathSeparator>().toSet()

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	fun `a reserved route is contiguous from the origin to the target`(case: Case) {
		val f = load(case)

		val result = f.service.reservePath(TRAIN, f.entry, f.exit)

		assertThat(result, name = "reservation of $case")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val route = (result as PathReservationService.ReservationResult.Success).reservedBlocks
		assertThat(route, name = "reserved blocks").isNotEmpty()
		assertThat(route.distinct().size, name = "distinct reserved blocks").isEqualTo(route.size)
		assertThat(f.entry in endsOf(route.first()), name = "origin bounds the first block").isTrue()
		assertThat(f.exit in endsOf(route.last()), name = "target bounds the last block").isTrue()
		route.zipWithNext().forEach { (a, b) ->
			assertThat(endsOf(a).intersect(endsOf(b)).isNotEmpty(), name = "$a and $b share a separator").isTrue()
		}
		assertThat(f.registry.getBlocks(TRAIN), name = "registry blocks").containsExactly(*route.toTypedArray())
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	fun `releasing a route leaves no ownership behind`(case: Case) {
		val f = load(case)
		val untouched = f.snapshot()
		assertThat(f.service.reservePath(TRAIN, f.entry, f.exit))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(f.snapshot(), name = "snapshot while reserved").isNotEqualTo(untouched)

		val released = f.service.releasePath(TRAIN)

		assertThat(released, name = "released blocks").isNotEmpty()
		assertThat(f.registry.getBlocks(TRAIN), name = "registry blocks").isEmpty()
		assertThat(f.registry.getSwitches(TRAIN), name = "registry switches").isEmpty()
		assertThat(f.snapshot(), name = "snapshot after release").isEqualTo(untouched)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	fun `a denied request leaves the registry and the track unchanged`(case: Case) {
		val f = load(case)
		assertThat(f.service.reservePath(TRAIN, f.entry, f.exit))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val before = f.snapshot()

		// The same pair is always denied. Pairs that share the entry or the exit start or end on free
		// blocks but may cross the held route in between; those that do are denied as well.
		val sameDenied = f.service.reservePath(OTHER_TRAIN, f.entry, f.exit)
		assertThat(sameDenied is PathReservationService.ReservationResult.Success, name = "same pair granted").isFalse()
		assertThat(f.snapshot(), name = "snapshot after the same-pair denial").isEqualTo(before)
		var denials = 1
		val crossing =
			f.inOuts.filter { it !== f.entry }.map { f.entry to it } +
				f.inOuts.filter { it !== f.exit }.map { it to f.exit }
		crossing.forEach { (from, to) ->
			val result = f.service.reservePath(OTHER_TRAIN, from, to)
			if (result is PathReservationService.ReservationResult.Success) {
				f.service.releasePath(OTHER_TRAIN)
			} else {
				denials++
				assertThat(f.registry.getBlocks(OTHER_TRAIN), name = "blocks after denial ${from.name} -> ${to.name}")
					.isEmpty()
				assertThat(f.snapshot(), name = "snapshot after denial ${from.name} -> ${to.name}").isEqualTo(before)
			}
		}
		assertThat(denials, name = "denied requests").isGreaterThan(1)
	}

	companion object {
		private const val TRAIN = "train1"
		private const val OTHER_TRAIN = "train2"

		/** Praha curated pairs, shared with [PrahaCuratedPairsBoundedSearchTest]. */
		val PRAHA_CURATED_PAIRS: List<Pair<String, String>> =
			listOf(
				"N-Bypass" to "S-Bypass",
				"N-Lib-1" to "S-Vin-1",
				"N-Vys-2" to "S-Vrs-3",
				"S-Vrs-3" to "N-Vys-2"
			)

		@JvmStatic
		fun cases(): Stream<Arguments> {
			val praha =
				PRAHA_CURATED_PAIRS.map { (entry, exit) ->
					Case("praha", { NetworkResources.PRAHA_HLAVNI_NADRAZI_XML }, entry, exit)
				}
			val all =
				listOf(
					Case("vyhybna", { NetworkResources.VYHYBNA_XML }, "A", "B"),
					Case("cerveny_ujezd", { NetworkResources.CERVENY_UJEZD_XML }, "west_entry_1", "east_exit_1")
				) + praha
			return all.stream().map { Arguments.of(it) }
		}
	}
}
