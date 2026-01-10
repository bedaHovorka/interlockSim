# Kotlin-Idiomatic Collection Libraries Research (Extended)

**Research Date**: January 10, 2026  
**Context**: Finding Kotlin-idiomatic collection libraries with multimap support beyond traditional Java libraries

## Summary

This document extends the original research to focus on **Kotlin-native and Kotlin-idiomatic** collection libraries. While Google Guava is excellent, the user requested more Kotlin-native alternatives.

## Kotlin-Native Libraries

### 1. Kotlinx Collections Immutable ⭐ (Official JetBrains)

**Maven Coordinates**: `org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7`

**Overview**:
- Official library from JetBrains
- Persistent (immutable) collections with efficient structural sharing
- Kotlin-first API design with extension functions
- Actively maintained by Kotlin team

**Collections Provided**:
- `PersistentList` - Immutable list
- `PersistentSet` - Immutable set  
- `PersistentMap` - Immutable map
- ❌ **No built-in Multimap support**

**Workaround for Multimap**:
```kotlin
import kotlinx.collections.immutable.*

typealias ImmutableMultimap<K, V> = PersistentMap<K, PersistentSet<V>>

fun <K, V> immutableMultimapOf(): ImmutableMultimap<K, V> = 
    persistentMapOf()

fun <K, V> ImmutableMultimap<K, V>.put(key: K, value: V): ImmutableMultimap<K, V> {
    val values = this[key] ?: persistentSetOf()
    return this.put(key, values.add(value))
}
```

**Advantages**:
- ✅ Official Kotlin library
- ✅ Kotlin-idiomatic API
- ✅ Immutability by default
- ✅ Efficient persistent data structures
- ✅ No Java interop overhead

**Considerations**:
- ❌ No direct multimap implementation
- ⚠️ Immutable only (not suitable for TreeMultiMap use case which is mutable)
- Requires building multimap abstraction yourself

---

### 2. Arrow-kt (Arrow Core)

**Maven Coordinates**: `io.arrow-kt:arrow-core:1.2.4`

**Overview**:
- Comprehensive functional programming library for Kotlin
- Provides functional data types and utilities
- Strong type safety with extensive use of generics
- Large ecosystem with multiple modules

**Collections**:
- `Nel` (NonEmptyList) - List that always has at least one element
- `Option`, `Either` - Functional types for handling nullability and errors
- ❌ **No direct multimap implementation**

**Functional Approach**:
```kotlin
import arrow.core.*

// Can use Map<K, Nel<V>> for multimap semantics
typealias FunctionalMultimap<K, V> = Map<K, Nel<V>>

// Or use standard collections with Arrow's functional extensions
```

**Advantages**:
- ✅ Kotlin-idiomatic functional programming
- ✅ Strong type safety
- ✅ Active community and development
- ✅ Comprehensive documentation

**Considerations**:
- ❌ No built-in multimap
- ⚠️ Functional programming paradigm (learning curve)
- ⚠️ Heavy dependency for just multimap needs
- Not designed for traditional collection operations

---

### 3. Kotlin Standard Library Extensions

**No External Dependency** - Pure Kotlin stdlib

**Kotlin-Idiomatic Approach**:
```kotlin
// Extension function approach - most idiomatic
fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.putMulti(key: K, value: V) {
    getOrPut(key) { mutableSetOf() }.add(value)
}

fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.getMulti(key: K): Set<V> = 
    get(key) ?: emptySet()

fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.valuesMulti(): Collection<V> = 
    values.flatten()

// Usage
val multimap = TreeMap<Double, MutableSet<Tranporter>>()
multimap.putMulti(distance, tranporter)
val allValues = multimap.valuesMulti()
```

**Advantages**:
- ✅ Zero dependencies
- ✅ Most Kotlin-idiomatic approach
- ✅ Full control over implementation
- ✅ Extension functions feel native
- ✅ Can add sorting for values with TreeSet

**Considerations**:
- Need to write and maintain extensions
- Less feature-rich than dedicated libraries

---

### 4. Splitties

**Maven Coordinates**: `com.louiscad.splitties:splitties-collections:3.0.0`

**Overview**:
- Collection of small, useful Kotlin utilities
- Modular design - only include what you need
- Focus on Android but works for JVM

**Collections Module**:
- Various list and collection utilities
- ❌ **No multimap implementation**

**Advantages**:
- ✅ Kotlin-native
- ✅ Small, focused modules
- ✅ Good for general utilities

**Considerations**:
- ❌ No multimap support
- More focused on Android use cases

---

### 5. Guava with Kotlin Extensions (kotlin-guava)

**Note**: The user mentioned kwava is old. There isn't currently a well-maintained Kotlin wrapper for Guava.

**Current State**:
- JLLeitschuh/kwava - Last updated 2019 (inactive)
- Most projects use Guava directly with Kotlin

**Direct Guava Usage in Kotlin**:
```kotlin
import com.google.common.collect.TreeMultimap

// Actually quite Kotlin-friendly already
val multimap = TreeMultimap.create<Double, Tranporter>()
multimap.put(distance, tranporter)

// Can add extension functions for more idiomatic API
fun <K : Comparable<K>, V> TreeMultimap<K, V>.putAll(key: K, values: Iterable<V>) {
    values.forEach { put(key, it) }
}
```

**Advantages**:
- ✅ Guava works well with Kotlin as-is
- ✅ Can add your own extension functions
- ✅ Battle-tested implementation

**Considerations**:
- Not "Kotlin-native" but very usable
- Java API style but functional

---

## Comparison for TreeMultimap Use Case

| Library | Native Multimap | Kotlin-Idiomatic | Sorted Keys | Sorted Values | Dependency Size | Maintenance |
|---------|----------------|------------------|-------------|---------------|-----------------|-------------|
| **Kotlinx Immutable** | ❌ (build it) | ⭐⭐⭐⭐⭐ | ⚠️ Custom | ⚠️ Custom | ~200KB | ✅ JetBrains |
| **Arrow-kt** | ❌ | ⭐⭐⭐⭐ | ❌ | ❌ | ~5MB | ✅ Active |
| **Kotlin Stdlib** | ❌ (build it) | ⭐⭐⭐⭐⭐ | ✅ TreeMap | ✅ TreeSet | 0KB | ✅ Kotlin |
| **Splitties** | ❌ | ⭐⭐⭐⭐ | ❌ | ❌ | ~50KB | ✅ Active |
| **Guava** | ✅ | ⭐⭐⭐ | ✅ | ✅ | ~3MB | ✅ Google |
| **Current TreeMultiMap** | ✅ | ⭐⭐⭐ | ✅ | ⚠️ LinkedHashSet | 0KB | You |

## Recommendations for Kotlin-Idiomatic Approach

### Option 1: Kotlin Stdlib with Extensions (Recommended for Kotlin-native) ⭐

**Most Kotlin-idiomatic without external dependencies**

Create a `MultimapExtensions.kt` file:

```kotlin
package cz.vutbr.fit.interlockSim.util

import java.util.TreeMap
import java.util.TreeSet

// Extension functions for TreeMap to act as sorted multimap
fun <K : Comparable<K>, V : Comparable<V>> sortedMultimapOf(): TreeMap<K, TreeSet<V>> = 
    TreeMap()

fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.putMulti(key: K, value: V) {
    getOrPut(key) { LinkedHashSet() }.add(value)
}

fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.getMulti(key: K): Set<V> = 
    get(key)?.toSet() ?: emptySet()

fun <K : Comparable<K>, V> TreeMap<K, MutableSet<V>>.valuesMulti(): List<V> = 
    entries.sortedBy { it.key }.flatMap { it.value }

// Usage in DefaultContext
private fun findTrackLineParts(
    key1: Point,
    key2: Point,
    trackBlock: TrackBlock
): Map<Point, TrackBlockPart>? {
    val distanceMap = TreeMap<Double, MutableSet<Tranporter>>()
    
    // ... populate ...
    distanceMap.putMulti(distance, Tranporter(p1, p2, s1, s2))
    
    // Iterate
    for (t in distanceMap.valuesMulti()) {
        val tryJoin = tryJoin(t, key1, key2, trackBlock)
        if (tryJoin != null) return tryJoin
    }
    return null
}
```

**Advantages**:
- ✅ Zero external dependencies
- ✅ Pure Kotlin extension functions (most idiomatic)
- ✅ Sorted keys guaranteed (TreeMap)
- ✅ Type-safe and explicit
- ✅ Easy to understand and maintain

---

### Option 2: Keep Current TreeMultiMap + Make it More Idiomatic

Enhance the existing class with Kotlin features:

```kotlin
@Deprecated(
    message = "Consider using TreeMap<K, MutableSet<V>> with extension functions",
    replaceWith = ReplaceWith("TreeMap<K, MutableSet<V>>")
)
class TreeMultiMap<K : Comparable<K>, V> {
    private val map: TreeMap<K, MutableSet<V>> = TreeMap()
    
    operator fun get(key: K): Set<V>? = map[key]?.toSet()
    
    operator fun set(key: K, value: V) = put(key, value)
    
    fun put(key: K, value: V) {
        map.getOrPut(key) { LinkedHashSet() }.add(value)
    }
    
    fun values(): Collection<V> = map.values.flatten()
    
    fun asMap(): Map<K, Set<V>> = map.mapValues { it.value.toSet() }
    
    override fun toString(): String = map.toString()
}

// Make it Kotlin-idiomatic with extensions
operator fun <K : Comparable<K>, V> TreeMultiMap<K, V>.contains(key: K): Boolean = 
    get(key) != null

inline fun <K : Comparable<K>, V> TreeMultiMap<K, V>.forEach(action: (V) -> Unit) {
    values().forEach(action)
}
```

---

### Option 3: Use Guava (If Kotlin-native is not strict requirement)

Guava is actually quite usable in Kotlin and can be made more idiomatic with extensions:

```kotlin
// GuavaExtensions.kt
import com.google.common.collect.TreeMultimap

fun <K : Comparable<K>, V : Comparable<V>> treeMultimapOf(): TreeMultimap<K, V> = 
    TreeMultimap.create()

operator fun <K, V> TreeMultimap<K, V>.contains(key: K): Boolean = 
    containsKey(key)

fun <K, V> TreeMultimap<K, V>.getAll(key: K): Set<V> = 
    get(key)

// Usage becomes more Kotlin-like
val multimap = treeMultimapOf<Double, Tranporter>()
multimap.put(distance, tranporter)
if (distance in multimap) {
    // ...
}
```

---

## Final Recommendation

For a **Kotlin-idiomatic** solution without heavy dependencies:

### 🏆 Use Kotlin Stdlib with Extension Functions

1. **Create `MultimapExtensions.kt`** with extension functions for TreeMap
2. **Replace TreeMultiMap usage** with `TreeMap<K, MutableSet<V>>` + extensions
3. **Keep tests** adapted to new API
4. **Benefits**:
   - Zero dependencies
   - Pure Kotlin
   - Explicit and type-safe
   - Easy to understand
   - Minimal code (~30 lines of extensions)

This is the most Kotlin-idiomatic approach that:
- Uses standard library
- Leverages Kotlin's extension function feature
- Maintains sorted behavior
- Requires no external dependencies
- Is easier to understand than custom class

## Other Collection Libraries Research

For completeness, here are other general Kotlin collection libraries that don't provide multimap:

1. **fastutil** - High-performance Java collections (not Kotlin-specific)
2. **Trove** - High-performance collections (older, not maintained)
3. **koloboke** - Java collections (not Kotlin-native)
4. **pcollections** - Persistent collections for Java (not Kotlin-native)

None of these provide Kotlin-idiomatic multimap implementations.

## Conclusion

**For Kotlin-idiomatic multimap without external dependencies**: Use `TreeMap<K, MutableSet<V>>` with Kotlin extension functions.

**If you want a well-tested library**: Guava is still the best choice, and it's actually quite usable in Kotlin.

**Current TreeMultiMap**: Works well, but could be enhanced with Kotlin operators and extension functions to be more idiomatic.
