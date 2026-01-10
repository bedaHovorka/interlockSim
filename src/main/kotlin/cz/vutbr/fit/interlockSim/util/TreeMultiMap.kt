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

import java.util.Collections
import java.util.LinkedHashSet
import java.util.TreeMap

/**
 * very simple ADT Multimap prototype
 *
 * @param <K> key
 * @param <V> value
 *
 * @deprecated should be replaced by some standard library implementation in Kotlin
 */
@Deprecated(
	message = "Should be replaced by some standard library implementation in Kotlin",
	replaceWith = ReplaceWith("Map<K, Set<V>>")
)
class TreeMultiMap<K : Comparable<K>, V> {
	private val map: TreeMap<K, MutableSet<V>> = TreeMap()

	/**
	 * put element to multimap
	 * @param key
	 * @param value
	 */
	fun put(
		key: K,
		value: V
	) {
		var valueSet = map[key]
		if (valueSet == null) {
			valueSet = LinkedHashSet()
			map[key] = valueSet
		}
		valueSet.add(value)
	}

	/**
	 * get elements from multimap
	 * @param key
	 * @return set of elements
	 */
	fun get(key: K): Set<V>? {
		// EXTENSION jak to ma byt spravne...
		// Maintain Java behavior: throws NPE if key not present
		return Collections.unmodifiableSet(map[key])
	}

	override fun toString(): String = map.toString()

	/**
	 * Values in map - reading access to multimap
	 * @return values
	 */
	fun values(): Collection<V> {
		// EXTENSION jak to ma byt spravne...
		val coll = mutableListOf<V>()
		for (set in map.values) {
			coll.addAll(set)
		}
		return coll
	}
}
