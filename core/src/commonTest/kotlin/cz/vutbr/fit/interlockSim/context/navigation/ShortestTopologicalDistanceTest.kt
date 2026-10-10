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
 * route at all. The Praha fixture is left out here because listing its paths takes seconds per
 * pair; the same equality was checked on all its 110 pairs when the search was added.
 */
class ShortestTopologicalDistanceTest : CommonKoinTestBase() {
	private companion object {
		/** Largest depth bound of the sweep; beyond it every fixture route fits. */
		const val DEPTH_SWEEP_MAX: Int = 12
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
