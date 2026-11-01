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
 * proceed until `holder` releases something. A cycle is a deadlock: no release can ever happen.
 *
 * Self-edges are ignored (a train does not wait for itself).
 */
internal class WaitForGraph {
	private val edges: MutableMap<String, MutableSet<String>> = linkedMapOf()

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

	/** Every edge, as each waiter's holders (Issue #1269); a snapshot, not a live view. */
	fun edges(): Map<String, Set<String>> = edges.mapValues { it.value.toSet() }

	/**
	 * Finds one cycle, or `null` when the graph is acyclic.
	 *
	 * @return the trains of the cycle in wait order (each waits for the next, the last for the
	 *   first); a tail leading into the cycle is not part of the result
	 */
	fun findCycle(): List<String>? {
		val done: MutableSet<String> = hashSetOf()
		for (start in edges.keys) {
			val cycle = visit(start, done, mutableListOf())
			if (cycle != null) {
				return cycle
			}
		}
		return null
	}

	private fun visit(
		node: String,
		done: MutableSet<String>,
		path: MutableList<String>
	): List<String>? {
		val onPath = path.indexOf(node)
		if (onPath >= 0) {
			return path.subList(onPath, path.size).toList()
		}
		if (node in done) {
			return null
		}
		path.add(node)
		for (next in edges[node].orEmpty()) {
			val cycle = visit(next, done, path)
			if (cycle != null) {
				return cycle
			}
		}
		path.removeAt(path.size - 1)
		done.add(node)
		return null
	}
}
