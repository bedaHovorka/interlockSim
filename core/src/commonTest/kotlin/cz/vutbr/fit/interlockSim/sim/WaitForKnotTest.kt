/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1270): the knot search of the deadlock watchdog.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * Pins the OR-wait semantics of [WaitForGraph.findKnot] (Issue #1270): a waiter needs only ONE of
 * its candidate routes, and a route needs ALL its blockers gone. A set of waiters is deadlocked (a
 * knot) when every route of every member is blocked by at least one member. A route blocked by
 * nobody, or only by trains outside the set, frees its waiter.
 *
 * `x` is a train that is not a waiter (a started train), so it is never part of a knot.
 */
class WaitForKnotTest {
	/** One waiter with its routes, each route given as the trains that block it. */
	private fun graph(vararg waiters: Pair<String, List<Set<String>>>): WaitForGraph =
		WaitForGraph().also { g ->
			waiters.forEach { (waiter, routes) -> routes.forEach { g.addRoute(waiter, it) } }
		}

	@Test
	fun waiterWithAFreeAlternativeRouteIsNotAKnot() {
		val graph = graph("a" to listOf(setOf("b"), emptySet()), "b" to listOf(setOf("a")))

		assertThat(graph.findCycle(), name = "cycle of the AND view (the false positive)").isNotNull()
		assertThat(graph.findKnot(), name = "knot").isNull()
	}

	@Test
	fun alternativeRouteHeldOnlyByARunningTrainIsNotAKnot() {
		val graph = graph("a" to listOf(setOf("b"), setOf("x")), "b" to listOf(setOf("a")))

		assertThat(graph.findKnot(), name = "knot").isNull()
	}

	@Test
	fun everyRouteBlockedInsideTheSetIsAKnot() {
		val knot = graph("a" to listOf(setOf("b"), setOf("b")), "b" to listOf(setOf("a"), setOf("a"))).findKnot()

		assertThat(knot?.members, name = "knot members").isNotNull().containsOnly("a", "b")
		assertThat(knot?.cycle.orEmpty().toSet(), name = "cycle inside the knot").containsOnly("a", "b")
	}

	@Test
	fun routeBlockedByAMemberAndARunningTrainStaysBlocked() {
		// The running train x releases one day, but b never does, so the route never frees.
		val knot = graph("a" to listOf(setOf("b", "x")), "b" to listOf(setOf("a"))).findKnot()

		assertThat(knot?.members, name = "knot members").isNotNull().containsOnly("a", "b")
	}

	@Test
	fun waiterBlockedOnlyByTheKnotJoinsItButNotItsCycle() {
		val knot =
			graph(
				"c" to listOf(setOf("a")),
				"a" to listOf(setOf("b")),
				"b" to listOf(setOf("a"))
			).findKnot()

		assertThat(knot?.members, name = "knot members").isNotNull().containsOnly("a", "b", "c")
		assertThat(knot?.cycle.orEmpty().toSet(), name = "cycle without the tail").containsOnly("a", "b")
	}

	@Test
	fun waiterWithAFreeRouteStaysOutOfAKnotItAlsoWaitsOn() {
		val knot =
			graph(
				"c" to listOf(setOf("a"), emptySet()),
				"a" to listOf(setOf("b")),
				"b" to listOf(setOf("a"))
			).findKnot()

		assertThat(knot?.members, name = "knot members").isNotNull().containsOnly("a", "b")
	}

	@Test
	fun routeBlockedOnlyByTheWaiterItselfIsFree() {
		assertThat(graph("a" to listOf(setOf("a"))).findKnot(), name = "knot of a self-block").isNull()
	}

	@Test
	fun routesAlsoFeedTheEdgeView() {
		val graph = graph("a" to listOf(setOf("b"), setOf("x", "a")))

		assertThat(graph.edges()["a"], name = "edges of a (the union, self dropped)")
			.isNotNull()
			.containsOnly("b", "x")
	}
}
