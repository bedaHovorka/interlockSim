/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

/**
 * A wait-for graph over trains (Issue #1154): an edge `waiter -> holder` says that `waiter` cannot
 * proceed until `holder` releases something.
 *
 * Two views (Issue #1270). The edge view ([addEdge], [edges], [findCycle]) is the AND model: a
 * cycle means a deadlock only when each waiter needs every holder it points at. The route view
 * ([addRoute], [findKnot]) is exact for OR waits: a waiter needs only ONE of its candidate routes,
 * and a route needs ALL its blockers gone. A deadlock there is a knot, see [findKnot]. [addRoute]
 * feeds the edge view too (the union of a waiter's routes).
 *
 * Self-edges are ignored (a train does not wait for itself).
 */
internal class WaitForGraph {
	/**
	 * A deadlocked set of waiters (Issue #1270).
	 *
	 * @property members all deadlocked waiters (possibly several disjoint knots), also those that
	 *   only wait for one
	 * @property cycle one cycle among them, in wait order (each waits for the next, the last for
	 *   the first): trains that hold each other; with several disjoint knots it lies in one only
	 */
	data class Knot(
		val members: Set<String>,
		val cycle: List<String>
	)

	private val edges: MutableMap<String, MutableSet<String>> = linkedMapOf()

	/** Each waiter's candidate routes, each as the trains that block it (the waiter itself removed). */
	private val routes: MutableMap<String, MutableList<Set<String>>> = linkedMapOf()

	/** Records that [waiter] waits for [holder]. */
	fun addEdge(
		waiter: String,
		holder: String
	) {
		if (waiter == holder) {
			return
		}
		edges.getOrPut(waiter) { linkedSetOf() }.add(holder)
	}

	/**
	 * Records one candidate route of [waiter] (Issue #1270), blocked by [blockers]: the route frees
	 * only when all of them have released. A route with no blocker other than [waiter] itself is
	 * free, so its waiter can never be part of a knot. Each blocker also becomes an edge.
	 *
	 * Precondition: the routes recorded for a waiter must be ALL of its alternatives. A partial set
	 * over-reports in [findKnot], because a missing route cannot free its waiter.
	 */
	fun addRoute(
		waiter: String,
		blockers: Collection<String>
	) {
		val others = blockers.filterTo(linkedSetOf()) { it != waiter }
		routes.getOrPut(waiter) { mutableListOf() }.add(others)
		others.forEach { addEdge(waiter, it) }
	}

	/**
	 * Finds the knot of the route view (Issue #1270), or `null` when there is none: the largest set
	 * of waiters in which every route of every member is blocked by at least one member. Such a
	 * route never frees, because no member can move to release it, whatever trains outside the set
	 * do. A waiter with a route blocked by nobody, or only by trains outside the set, can proceed one
	 * day, and so can the waiters that wait for it on some route. Only waiters with a recorded route
	 * can be members: a waiter with no record proves nothing.
	 *
	 * Precondition: the routes recorded for a waiter must be ALL of its alternatives. A partial set
	 * over-reports (a missing route cannot free its waiter), so it can fabricate a knot.
	 *
	 * Computed as a greatest fixed point: start from all waiters, drop every waiter that has a route
	 * with no blocker left in the set, repeat until nothing changes.
	 */
	fun findKnot(): Knot? {
		val members: MutableSet<String> = routes.keys.toCollection(linkedSetOf())
		do {
			val freed = members.filter { waiter -> routes.getValue(waiter).any { route -> route.none { it in members } } }
			members.removeAll(freed)
		} while (freed.isNotEmpty())
		if (members.isEmpty()) {
			return null
		}
		// Every member has a route, and each route a blocker in the set: an edge inside the set from
		// every member, so the restricted graph has a cycle.
		val cycle = findCycle(within = members) ?: return null
		return Knot(members.toSet(), cycle)
	}

	/** Every edge, as each waiter's holders (Issue #1269); a snapshot, not a live view. */
	fun edges(): Map<String, Set<String>> = edges.mapValues { it.value.toSet() }

	/**
	 * Finds one cycle, or `null` when the graph is acyclic.
	 *
	 * @param within when given, only these nodes and the edges between them are searched
	 * @return the trains of the cycle in wait order (each waits for the next, the last for the
	 *   first); a tail leading into the cycle is not part of the result
	 */
	fun findCycle(within: Set<String>? = null): List<String>? {
		val done: MutableSet<String> = hashSetOf()
		for (start in edges.keys.filter { within == null || it in within }) {
			val cycle = visit(start, done, mutableListOf(), within)
			if (cycle != null) {
				return cycle
			}
		}
		return null
	}

	private fun visit(
		node: String,
		done: MutableSet<String>,
		path: MutableList<String>,
		within: Set<String>?
	): List<String>? {
		val onPath = path.indexOf(node)
		if (onPath >= 0) {
			return path.subList(onPath, path.size).toList()
		}
		if (node in done) {
			return null
		}
		path.add(node)
		for (next in edges[node].orEmpty().filter { within == null || it in within }) {
			val cycle = visit(next, done, path, within)
			if (cycle != null) {
				return cycle
			}
		}
		path.removeAt(path.size - 1)
		done.add(node)
		return null
	}
}
