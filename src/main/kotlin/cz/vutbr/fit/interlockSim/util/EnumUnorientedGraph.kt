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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import java.util.Arrays
import java.util.Collection
import java.util.EnumMap
import java.util.Map
import java.util.Set

/**
 * for enum pairs mapping to other
 * @param <N> node
 * @param <E> edge
 */
class EnumUnorientedGraph<N, E>(
	private val clazz: Class<N>
) : AbstractUnorientedGraph<N, E>() where N : Enum<N> {
	private val map: MutableMap<N, MutableMap<N, E>> = EnumMap(clazz)

	/**
	 * @param clazz
	 */

	override fun implementationContainer(): Any = map

	override fun contains(
		node1: N,
		node2: N
	): Boolean {
		val ns = nodesInOrder(node1, node2)
		val submap = getSubmap(ns)
		if (submap == null) return false
		return submap.containsKey(ns[1])
	}

	override fun get(
		first: N,
		second: N
	): E? {
		val ns = nodesInOrder(first, second)
		val submap = getSubmap(ns)
		if (submap == null) return null
		return submap[ns[1]]
	}

	override fun get(node: N): Collection<E> = throw NotImplementedException()

	/**
	 * @param node
	 * @return node around in graph
	 */
	fun getJoinedNodesAndEdges(node: N?): Map<N, E> {
		val enumMap = EnumMap<N, E>(clazz)
		for (e in map.entries) {
			val key = e.key
			val value = e.value
			requireValidState(!value.containsKey(key)) {
				"Invalid graph state: self-loop detected for key $key in submap $value"
			}

			if (key == node) {
				enumMap.putAll(value)
			} else {
				for (e2 in value.entries) {
					val key2 = e2.key
					if (key2 == node) {
						requireValidState(!enumMap.containsKey(key)) { "Duplicate edge detected for key $key in result map $enumMap" }
						enumMap[key] = e2.value
					}
				}
			}
		}
		@Suppress("UNCHECKED_CAST")
		return enumMap as java.util.Map<N, E>
	}

	override fun nodeSet(): Set<N> = throw NotImplementedException()

	override fun put(
		first: N,
		second: N,
		value: E
	) {
		val ns = nodesInOrder(first, second)
		val submap = getSubmapWithCreation(ns)
		submap[ns[1]] = value
	}

	override fun remove(
		first: N,
		second: N
	): E? = throw NotImplementedException()

	override fun size(): Int {
		var count = 0
		for (submap in map.values) {
			count += submap.size
		}
		return count
	}

	override fun clear() {
		map.clear()
	}

	override fun remove(edge: E): Collection<N> = throw NotImplementedException()

	override fun removeAll(node: N): Collection<E> = throw NotImplementedException()

	override fun values(): Collection<E> = throw NotImplementedException()

	@Suppress("UNCHECKED_CAST", "UNCHECKED_CAST")
	private fun nodesInOrder(
		n1: N,
		n2: N
	): Array<N> {
		requireValidState(n1 != n2) { "Cannot create edge between same node: $n1" }
		@Suppress("UNCHECKED_CAST")
		val nodes =
			java.lang.reflect.Array
				.newInstance(n1.javaClass, 2) as Array<N>
		nodes[0] = n1
		nodes[1] = n2
		// Since N extends Enum<N>, we can safely cast to Array<Comparable>
		@Suppress("UNCHECKED_CAST")
		Arrays.sort(nodes as Array<Comparable<N>>)
		return nodes
	}

	private fun getSubmap(sortedNodes: Array<N>): MutableMap<N, E>? = map[sortedNodes[0]]

	private fun getSubmapWithCreation(sortedNodes: Array<N>): MutableMap<N, E> {
		var subMap = getSubmap(sortedNodes)
		if (subMap == null) {
			subMap = EnumMap(clazz)
			map[sortedNodes[0]] = subMap
		}
		return subMap
	}
}
