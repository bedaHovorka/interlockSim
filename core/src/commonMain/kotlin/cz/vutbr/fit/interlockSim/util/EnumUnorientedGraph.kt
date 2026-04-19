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

/**
 * for enum pairs mapping to other
 * @param <N> node
 * @param <E> edge
 */
class EnumUnorientedGraph<N, E> : AbstractUnorientedGraph<N, E>() where N : Enum<N> {
	private val map: MutableMap<N, MutableMap<N, E>> = HashMap()

	override fun implementationContainer(): Any = map

	override fun contains(
		node1: N,
		node2: N
	): Boolean {
		val (first, second) = nodesInOrder(node1, node2)
		val submap = map[first]
		if (submap == null) return false
		return submap.containsKey(second)
	}

	override fun get(
		first: N,
		second: N
	): E? {
		val (n1, n2) = nodesInOrder(first, second)
		return map[n1]?.get(n2)
	}

	override fun get(node: N): Collection<E> = throw NotImplementedException()

	/**
	 * @param node
	 * @return node around in graph
	 */
	fun getJoinedNodesAndEdges(node: N?): Map<N, E> {
		val enumMap = HashMap<N, E>()
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
		return enumMap
	}

	override fun nodeSet(): Set<N> = throw NotImplementedException()

	override fun put(
		first: N,
		second: N,
		value: E
	) {
		val (n1, n2) = nodesInOrder(first, second)
		val submap = getSubmapWithCreation(n1)
		submap[n2] = value
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

	private fun nodesInOrder(
		n1: N,
		n2: N
	): Pair<N, N> {
		requireValidState(n1 != n2) { "Cannot create edge between same node: $n1" }
		return if (n1 <= n2) Pair(n1, n2) else Pair(n2, n1)
	}

	private fun getSubmapWithCreation(key: N): MutableMap<N, E> = map.getOrPut(key) { HashMap() }
}
