/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 *
 * Kotlin-idiomatic extensions for multimap functionality using TreeMap
 * Replaces custom TreeMultiMap class with standard library + extensions
 */
package cz.vutbr.fit.interlockSim.util

/**
 * Extension function to add a value to a multimap (TreeMap with Set values)
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.put()
 *
 * @param key the key to associate the value with
 * @param value the value to add to the set of values for this key
 */
fun <K : Comparable<K>, V> java.util.TreeMap<K, MutableSet<V>>.putMulti(
	key: K,
	value: V
) {
	getOrPut(key) { LinkedHashSet() }.add(value)
}

/**
 * Extension function to get all values from a multimap
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.values()
 *
 * Returns values in sorted key order (TreeMap guarantees this)
 *
 * @return collection of all values across all keys, in key-sorted order
 */
fun <K : Comparable<K>, V> java.util.TreeMap<K, MutableSet<V>>.valuesMulti(): Collection<V> {
	val result = mutableListOf<V>()
	for (set in values) {
		result.addAll(set)
	}
	return result
}

/**
 * Extension function to get values for a specific key from a multimap
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.get()
 *
 * @param key the key to look up
 * @return set of values for the key, or empty set if key not present
 */
fun <K : Comparable<K>, V> java.util.TreeMap<K, MutableSet<V>>.getMulti(key: K): Set<V> =
	get(key)?.toSet() ?: emptySet()
