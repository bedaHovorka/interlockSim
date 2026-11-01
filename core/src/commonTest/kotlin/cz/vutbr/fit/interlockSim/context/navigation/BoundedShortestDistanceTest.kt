/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1148: depth-bounded Dijkstra behind findShortestTopologicalDistance.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * Pins [boundedShortestDistance] on small hand-made graphs, where the case the railway fixtures
 * never reach can be built on purpose.
 */
class BoundedShortestDistanceTest {
	/**
	 * `A -10-> X -1-> T` (2 moves, length 11) and `A -1-> B -1-> C -1-> X -1-> T` (4 moves, length 4).
	 * The short way reaches `X` cheaper but deeper than the direct move.
	 */
	private val graph: Map<String, List<Pair<String, Double>>> =
		mapOf(
			"A" to listOf("X" to 10.0, "B" to 1.0),
			"B" to listOf("C" to 1.0),
			"C" to listOf("X" to 1.0),
			"X" to listOf("T" to 1.0)
		)

	private fun distance(maxDepth: Int): Double? =
		boundedShortestDistance(
			start = "A",
			isTarget = { it == "T" },
			maxDepth = maxDepth,
			key = { it }
		) { graph[it].orEmpty() }

	@Test
	fun withoutABindingBoundTheShortestRouteWins() {
		assertThat(distance(maxDepth = 100)).isEqualTo(4.0)
	}

	@Test
	fun aCheaperDeeperArrivalDoesNotHideAShallowerOne() {
		// With maxDepth 3 only the 2-move route fits. A search that kept one arrival per node would
		// keep X at length 3 and depth 3, drop X at length 10 and depth 1, and return null.
		assertThat(distance(maxDepth = 3)).isEqualTo(11.0)
	}

	@Test
	fun theDepthIsCheckedBeforeTheTargetLikeThePathListing() {
		// T is reached at depth 2; the path listing drops a node whose depth equals the bound.
		assertThat(distance(maxDepth = 2)).isNull()
	}

	@Test
	fun theStartItselfIsATargetAtDistanceZero() {
		assertThat(
			boundedShortestDistance(start = "A", isTarget = { it == "A" }, maxDepth = 1, key = { it }) {
				graph[it].orEmpty()
			}
		).isEqualTo(0.0)
	}
}
