/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.context.navigation

/**
 * Shortest distance from [start] to the first node that satisfies [isTarget], over routes of fewer
 * than [maxDepth] moves, or `null` when there is none.
 *
 * A Dijkstra search. A node is expanded, or accepted as the target, only while its depth (moves
 * from [start]) is below [maxDepth]; the depth is checked before the target, the same order as
 * [TopologyNavigator.findAllTopologicalPaths]. Nodes with the same [key] are one search state.
 * Per state the search keeps every arrival that no other arrival beats in both distance and depth
 * (a Pareto set): a shorter but deeper arrival must not hide a longer, shallower one that still
 * has depth left to reach the target. Move lengths must not be negative.
 *
 * @param movesOf the next nodes of a node, each with the length of the move to it
 * @since Issue #1148
 */
internal fun <N> boundedShortestDistance(
	start: N,
	isTarget: (N) -> Boolean,
	maxDepth: Int,
	key: (N) -> Any?,
	movesOf: (N) -> List<Pair<N, Double>>
): Double? {
	val arrivals = mutableMapOf(key(start) to mutableListOf(Arrival(0.0, 0)))
	val queue = mutableListOf(Triple(start, 0.0, 0))

	while (queue.isNotEmpty()) {
		val cheapest = queue.minBy { it.second }
		queue.remove(cheapest)
		val (node, cost, depth) = cheapest
		if (arrivals[key(node)]?.contains(Arrival(cost, depth)) != true) {
			continue // a later arrival beat this one in both distance and depth
		}
		if (depth >= maxDepth) {
			continue
		}
		if (isTarget(node)) {
			return cost
		}
		for ((next, length) in movesOf(node)) {
			val arrival = Arrival(cost + length, depth + 1)
			val known = arrivals.getOrPut(key(next)) { mutableListOf() }
			if (known.none { it.dominates(arrival) }) {
				known.removeAll { arrival.dominates(it) }
				known.add(arrival)
				queue.add(Triple(next, arrival.cost, arrival.depth))
			}
		}
	}
	return null
}

/** One way of reaching a search state: distance so far and number of moves. */
private data class Arrival(
	val cost: Double,
	val depth: Int
) {
	/** True when this arrival is at least as short and at least as shallow as [other]. */
	fun dominates(other: Arrival): Boolean = cost <= other.cost && depth <= other.depth
}
