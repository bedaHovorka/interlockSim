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

/**
 * Base for Graphs
 * @param <N> node
 * @param <E> edge
 *
 */
abstract class AbstractUnorientedGraph<N, E> : UnorientedGraph<N, E> {
	protected abstract fun implementationContainer(): Any?

	override fun containsEdge(edge: E): Boolean = values().contains(edge)

	override fun toString(): String {
		val o = implementationContainer()
		return if (o == null) super.toString() else o.toString()
	}

	override fun isEmpty(): Boolean = size() == 0
}
