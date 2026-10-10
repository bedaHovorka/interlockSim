/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1148: shortest topological distance without listing every path.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Pins [DefaultTopologyNavigator.findShortestTopologicalDistance] to the value it replaces in
 * `Train.validateTrainLength`: the minimum route length over [TopologyNavigator.findAllTopologicalPaths].
 *
 * The Dijkstra search must give the same value for every ordered InOut pair, including pairs
 * whose only routes reverse through a switch (switch-blind "phantom" routes) and pairs with no
 * route at all. The Praha fixture is too big to list in full here (its 110 pairs take 98.7 s of
 * JVM listing, see `docs/goal9b-demands/SP1-fail-fast-harness.md`); the full sweep equality was
 * checked once when the search was added, and [matchesPathListingOnTheCheapPrahaPairs] keeps the
 * 28 cheap pairs (at most 100 topological paths each, measured 2026-10-10) pinned permanently.
 */
class ShortestTopologicalDistanceTest : CommonKoinTestBase() {
	private companion object {
		/** Largest depth bound of the sweep; beyond it every fixture route fits. */
		const val DEPTH_SWEEP_MAX: Int = 12

		/**
		 * The ordered Praha pairs whose switch-blind listing enumerates at most 100 paths, measured
		 * on 2026-10-10. Listing them is fast, so this class pins their equality to the search even
		 * though the full Praha sweep stays out of reach for a unit test.
		 */
		val CHEAP_PRAHA_PAIRS =
			listOf(
				"N-Lib-1" to "S-Vin-1",
				"N-Lib-1" to "S-Vin-2",
				"N-Lib-2" to "S-Vin-1",
				"N-Lib-2" to "S-Vin-2",
				"N-Vys-1" to "S-Vrs-1",
				"N-Vys-1" to "S-Vrs-2",
				"N-Vys-1" to "S-Vrs-3",
				"N-Vys-2" to "N-Bypass",
				"N-Vys-2" to "S-Bypass",
				"N-Bypass" to "N-Vys-2",
				"N-Bypass" to "S-Bypass",
				"S-Vin-1" to "N-Lib-1",
				"S-Vin-1" to "N-Lib-2",
				"S-Vin-1" to "S-Vin-2",
				"S-Vin-2" to "N-Lib-1",
				"S-Vin-2" to "N-Lib-2",
				"S-Vin-2" to "S-Vin-1",
				"S-Vrs-1" to "N-Vys-1",
				"S-Vrs-1" to "S-Vrs-2",
				"S-Vrs-1" to "S-Vrs-3",
				"S-Vrs-2" to "N-Vys-1",
				"S-Vrs-2" to "S-Vrs-1",
				"S-Vrs-2" to "S-Vrs-3",
				"S-Vrs-3" to "N-Vys-1",
				"S-Vrs-3" to "S-Vrs-1",
				"S-Vrs-3" to "S-Vrs-2",
				"S-Bypass" to "N-Vys-2",
				"S-Bypass" to "N-Bypass"
			)
	}

	private fun assertSameAsPathListing(
		xml: String,
		maxDepth: Int = 100
	) {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		val inOuts = ctx.getInOuts().toList()
		assertThat(inOuts, name = "InOuts").isNotEmpty()
		val navigator = ctx.getRoutingServices().getTopologyNavigator()
		for (start in inOuts) {
			for (target in inOuts.filter { it !== start }) {
				val listed =
					navigator.findAllTopologicalPaths(start, target, maxDepth).minOfOrNull { path ->
						path.sumOf { section -> section.length() }
					}
				assertThat(
					navigator.findShortestTopologicalDistance(start, target, maxDepth),
					name = "shortest distance ${start.name} -> ${target.name} (maxDepth $maxDepth)"
				).isEqualTo(listed)
			}
		}
	}

	@Test
	fun matchesPathListingOnTheShuntingLoop() = assertSameAsPathListing(NetworkResources.VYHYBNA_XML)

	@Test
	fun matchesPathListingOnCervenyUjezd() = assertSameAsPathListing(NetworkResources.CERVENY_UJEZD_XML)

	@Test
	fun matchesPathListingWhenTheOnlyRoutesReverseThroughASwitch() =
		assertSameAsPathListing(NetworkResources.SWITCH_BASIC_XML)

	@Test
	fun matchesPathListingOnTwoParallelTracks() = assertSameAsPathListing(NetworkResources.TWO_TRACKS_PARALLEL_XML)

	@Test
	fun matchesPathListingOnTheCheapPrahaPairs() {
		val ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, get()).tracked()
		val inOuts = ctx.getInOuts().toList().associateBy { it.name }
		val navigator = ctx.getRoutingServices().getTopologyNavigator()
		for ((inName, outName) in CHEAP_PRAHA_PAIRS) {
			val start = requireNotNull(inOuts[inName]) { "Praha fixture has no InOut '$inName'" }
			val target = requireNotNull(inOuts[outName]) { "Praha fixture has no InOut '$outName'" }
			val paths = navigator.findAllTopologicalPaths(start, target)
			val listed = paths.minOfOrNull { path -> path.sumOf { section -> section.length() } }
			assertThat(
				navigator.findShortestTopologicalDistance(start, target),
				name = "shortest distance $inName -> $outName"
			).isEqualTo(listed)
		}
	}

	@Test
	fun respectsEveryDepthBoundLikePathListing() {
		// Small bounds cut routes off part-way; a search that kept only the shortest arrival per
		// state would lose a shallower, longer arrival that still has depth left (PR #1175 review).
		for (maxDepth in 0..DEPTH_SWEEP_MAX) {
			assertSameAsPathListing(NetworkResources.VYHYBNA_XML, maxDepth)
			assertSameAsPathListing(NetworkResources.CERVENY_UJEZD_XML, maxDepth)
			assertSameAsPathListing(NetworkResources.SWITCH_BASIC_XML, maxDepth)
		}
	}

	@Test
	fun noRouteGivesNull() {
		val ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.TWO_TRACKS_PARALLEL_XML, get()).tracked()
		val navigator = ctx.getRoutingServices().getTopologyNavigator()
		val pairsWithoutRoute =
			ctx
				.getInOuts()
				.toList()
				.let { inOuts ->
					inOuts.flatMap { a -> inOuts.filter { it !== a }.map { b -> a to b } }
				}.filter { (a, b) -> navigator.findAllTopologicalPaths(a, b).isEmpty() }
		assertThat(pairsWithoutRoute, name = "pairs without a route in two-tracks-parallel").isNotEmpty()
		for ((a, b) in pairsWithoutRoute) {
			assertThat(navigator.findShortestTopologicalDistance(a, b), name = "${a.name} -> ${b.name}").isNull()
		}
	}
}
