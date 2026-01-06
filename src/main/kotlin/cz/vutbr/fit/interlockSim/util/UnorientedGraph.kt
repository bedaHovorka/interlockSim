/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import java.util.Collection
import java.util.Set

/**
 * ADT Graph interface
 *
 * @param <N> nodes
 * @param <E> edges
 */
interface UnorientedGraph<N, E> {
	/**
	 * Put element to ExtendedUnorientedGraph
	 * @param first
	 * @param second
	 * @param value
	 */
	fun put(
		first: N,
		second: N,
		value: E
	)

	/**
	 * get edge from graph
	 * @param first
	 * @param second
	 * @return edge
	 */
	operator fun get(
		first: N,
		second: N
	): E?

	/**
	 * @param node
	 * @return all edges, which joins with node
	 */
	fun get(node: N): Collection<E>

	/**
	 * Remove edge from graph
	 * @param first
	 * @param second
	 * @return edge
	 */
	fun remove(
		first: N,
		second: N
	): E?

	/**
	 * Remove node and all edges, which join node with others
	 * @param node
	 * @return all edges which were removed
	 */
	fun removeAll(node: N): Collection<E>

	/**
	 * Remove edge from graph
	 * @param edge
	 * @return nodes which were joined with this edge
	 */
	fun remove(edge: E): Collection<N>

	/**
	 * @return all nodes, which is in graph
	 */
	fun nodeSet(): Set<N>

	/**
	 * @return {@link Map#isEmpty()}
	 */
	fun isEmpty(): Boolean

	/**
	 * @see Map#values()
	 * @return all edges in graph
	 */
	fun values(): Collection<E>

	/**
	 * @return number of edges
	 */
	fun size(): Int

	/**
	 * Remove all edges from graph
	 */
	fun clear()

	/**
	 * @param node1
	 * @param node2
	 * @return true If graph contains edge between nodes
	 */
	fun contains(
		node1: N,
		node2: N
	): Boolean

	/**
	 * @param edge
	 * @return true If graph contains edge
	 */
	fun containsEdge(edge: E): Boolean
}
