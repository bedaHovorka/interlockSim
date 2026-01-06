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

import java.util.Map
import java.util.Map.Entry
import java.util.Set

/**
 * for this program extended unoriented graph interface
 *
 * @param <N> nodes
 * @param <E> edges
 * @param <X> node to edge relation extensional object
 */
interface ExtendedUnorientedGraph<N, E, X> : UnorientedGraph<N, E> {
	/**
	 * Put element to ExtendedUnorientedGraph with additional info
	 * @param first
	 * @param firstExt
	 * @param second
	 * @param secondExt
	 * @param value
	 */
	fun put(
		first: N,
		firstExt: X,
		second: N,
		secondExt: X,
		value: E
	)

	/**
	 *
	 * @param node
	 * @param edge
	 * @return object mapped to relation between node and edge
	 */
	fun extensionalObject(
		node: N,
		edge: E
	): X

	/**
	 * @param node
	 * @return extensioanal objects mapped to edge, which joins with mode
	 */
	fun assignedEdges(node: N): Map<X, E>

	/**
	 * @see Map#entrySet()
	 * @return entry set
	 */
	fun entrySet(): Set<Map.Entry<Doubleton<N, X>, E>>

	/**
	 * Put the key if not present in graph
	 * @param first
	 * @param firstExt
	 * @param second
	 * @param secondExt
	 * @param edge
	 */
	fun putIfNotExists(
		first: N,
		firstExt: X,
		second: N,
		secondExt: X,
		edge: E
	)
}
