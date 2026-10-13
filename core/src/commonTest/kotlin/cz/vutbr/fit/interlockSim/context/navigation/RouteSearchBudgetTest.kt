/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP4 (#1151): node budget of the switch-constrained route search.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.pathfinding.DefaultAutomaticPathFindingService
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * An enumeration that would expand more search nodes than its budget stops and answers a
 * structured [RouteSearch.BudgetExceeded] instead of running on (Issue #1151).
 */
class RouteSearchBudgetTest : CommonKoinTestBase() {
	private companion object {
		/** A budget far below the 13 nodes the vyhybna InOut-to-InOut search expands. */
		const val TINY_BUDGET: Int = 5
	}

	private fun context(xml: String): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(xml, get()).tracked()

	@Test
	fun overBudgetEnumerationReturnsStructuredFailureWithinTheBudget() {
		val ctx = context(NetworkResources.VYHYBNA_XML)
		val (from, to) = ctx.getInOuts().toList()
		val navigator = DefaultTopologyNavigator(ctx)

		val search = navigator.searchSwitchConstrainedPaths(from, to, maxDepth = 100, nodeBudget = TINY_BUDGET)

		assertThat(search).isInstanceOf<RouteSearch.BudgetExceeded>()
		search as RouteSearch.BudgetExceeded
		assertThat(search.nodeBudget, name = "budget").isEqualTo(TINY_BUDGET)
		assertThat(search.expandedNodes, name = "expanded nodes").isLessThanOrEqualTo(TINY_BUDGET)
		assertThat(search.start, name = "start").isSameInstanceAs(from)
		assertThat(search.target, name = "target").isSameInstanceAs(to)
	}

	@Test
	fun overBudgetPairHasNoRoutesAndIsNotSearchedAgain() {
		val ctx = context(NetworkResources.VYHYBNA_XML)
		val (from, to) = ctx.getInOuts().toList()
		val navigator = DefaultTopologyNavigator(ctx, cacheRoutes = true, routeNodeBudget = TINY_BUDGET)
		val pathFinding = DefaultAutomaticPathFindingService(navigator)

		assertThat(pathFinding.findAllPaths(from, to), name = "routes over budget").isEmpty()
		assertThat(pathFinding.findAllPaths(from, to), name = "routes over budget, again").isEmpty()
		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(1)
	}

	@Test
	fun everyFixtureInOutPairCompletesWithinTheDefaultBudget() {
		// Tripwire: the budget must never cut a route of the shipped fixtures. Measured largest
		// enumeration is 236 expanded nodes (Praha), far below the default budget.
		for (xml in listOf(
			NetworkResources.VYHYBNA_XML,
			NetworkResources.CERVENY_UJEZD_XML,
			NetworkResources.PRAHA_HLAVNI_NADRAZI_XML
		)) {
			val ctx = context(xml)
			val navigator = DefaultTopologyNavigator(ctx)
			val inOuts = ctx.getInOuts().toList()
			for (from in inOuts) {
				for (to in inOuts) {
					if (from === to) continue
					val search =
						navigator.searchSwitchConstrainedPaths(
							from,
							to,
							maxDepth = 100,
							nodeBudget = DefaultTopologyNavigator.ROUTE_SEARCH_NODE_BUDGET
						)
					assertThat(search, name = "search ${from.name} -> ${to.name}")
						.isInstanceOf<RouteSearch.Completed>()
				}
			}
		}
	}
}
