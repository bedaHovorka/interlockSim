/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 *
 * Kotlin-idiomatic extensions for multimap functionality using MutableMap
 * Replaces custom TreeMultiMap class with standard library + extensions
 */
package cz.vutbr.fit.interlockSim.util

/**
 * Extension function to add a value to a multimap (MutableMap with Set values)
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.put()
 *
 * @param key the key to associate the value with
 * @param value the value to add to the set of values for this key
 */
fun <K : Comparable<K>, V> MutableMap<K, MutableSet<V>>.putMulti(
	key: K,
	value: V
) {
	getOrPut(key) { LinkedHashSet() }.add(value)
}

/**
 * Extension function to get all values from a multimap
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.values()
 *
 * Returns values in sorted key order (explicitly sorted since MutableMap doesn't guarantee order)
 *
 * @return collection of all values across all keys, in key-sorted order
 */
fun <K : Comparable<K>, V> MutableMap<K, MutableSet<V>>.valuesMulti(): Collection<V> =
	entries.sortedBy { it.key }.flatMap { it.value }

/**
 * Extension function to get values for a specific key from a multimap
 * This is a Kotlin-idiomatic replacement for TreeMultiMap.get()
 *
 * @param key the key to look up
 * @return set of values for the key, or empty set if key not present
 */
fun <K : Comparable<K>, V> MutableMap<K, MutableSet<V>>.getMulti(key: K): Set<V> =
	get(key)?.toSet() ?: emptySet()
