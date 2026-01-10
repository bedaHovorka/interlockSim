# Multimap Library Research for Kotlin/Java

**Research Date**: January 10, 2026  
**Context**: Finding well-known collection libraries with TreeMultimap support for interlockSim project

## Summary

There are several well-established libraries that provide multimap implementations including TreeMultimap functionality. The most popular and production-ready option is **Google Guava**, which is widely used in enterprise Java/Kotlin projects.

## Recommended Libraries

### 1. Google Guava (Recommended) ⭐

**Maven Coordinates**: `com.google.guava:guava:33.0.0-jre` (Latest as of 2026)

**Overview**:
- Industry standard collection library from Google
- Used by millions of projects
- Excellent documentation and community support
- Stable API with long-term support

**Multimap Implementations**:
- `TreeMultimap<K, V>` - Keys sorted by natural order or comparator, values sorted
- `ArrayListMultimap<K, V>` - Allows duplicate values, insertion order
- `LinkedHashMultimap<K, V>` - Insertion order for both keys and values
- `HashMultimap<K, V>` - No ordering guarantees, best performance

**TreeMultimap Features**:
```kotlin
import com.google.common.collect.TreeMultimap

val multimap = TreeMultimap.create<Double, Tranporter>()
multimap.put(distance, tranporter)
val values: Collection<Tranporter> = multimap.values()
val keysSet: Set<Double> = multimap.keySet()
```

**Advantages**:
- ✅ Well-tested and battle-hardened
- ✅ Excellent documentation
- ✅ Active maintenance
- ✅ Many other useful collection utilities
- ✅ Thread-safe variants available (Multimaps.synchronizedMultimap)
- ✅ Immutable versions available

**Considerations**:
- Adds ~3MB dependency
- Requires Java 8+ (compatible with project's Java 21)

### 2. Apache Commons Collections

**Maven Coordinates**: `org.apache.commons:commons-collections4:4.4`

**Overview**:
- Part of Apache Commons suite
- Stable and widely used
- Good for general-purpose collections

**Multimap Implementation**:
- `MultiValuedMap<K, V>` interface
- `ArrayListValuedHashMap<K, V>` - List-backed multimap
- `HashSetValuedHashMap<K, V>` - Set-backed multimap
- Note: No direct TreeMultimap equivalent, but can use TreeMap as backing

**Advantages**:
- ✅ Lightweight
- ✅ Part of Apache ecosystem
- ✅ Good integration with other Apache libraries

**Considerations**:
- Less feature-rich than Guava
- Less intuitive API
- No built-in sorted multimap

### 3. Eclipse Collections

**Maven Coordinates**: `org.eclipse.collections:eclipse-collections:11.1.0`

**Overview**:
- High-performance collections framework
- Originally from Goldman Sachs
- Memory-efficient implementations

**Multimap Implementations**:
- `SortedSetMultimap` - Keys and values sorted
- `ListMultimap` - Multiple values per key
- `SetMultimap` - Unique values per key

**Advantages**:
- ✅ High performance
- ✅ Memory efficient
- ✅ Primitive collections support

**Considerations**:
- Less popular than Guava
- Steeper learning curve
- Different API conventions

### 4. Kotlin Standard Library (Current Approach)

**Overview**: Using `TreeMap<K, MutableSet<V>>` directly

**Advantages**:
- ✅ No additional dependencies
- ✅ Direct control over implementation
- ✅ Kotlin-idiomatic with `getOrPut()`

**Considerations**:
- More verbose API
- No built-in helper methods
- Need to implement utilities yourself

## Comparison Matrix

| Feature | Guava TreeMultimap | Apache Commons | Eclipse Collections | Stdlib TreeMap |
|---------|-------------------|----------------|---------------------|----------------|
| Sorted Keys | ✅ | ⚠️ Manual | ✅ | ✅ |
| Sorted Values | ✅ | ❌ | ✅ | ⚠️ Via LinkedHashSet |
| API Simplicity | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| Documentation | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Dependency Size | ~3MB | ~750KB | ~2MB | 0KB |
| Popularity | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | N/A |
| Active Maintenance | ✅ | ✅ | ✅ | ✅ |

## Code Examples

### Current Implementation (TreeMultiMap)
```kotlin
val treeMM = TreeMultiMap<Double, Tranporter>()
treeMM.put(distance, tranporter)
for (t in treeMM.values()) {
    // process
}
```

### Using Guava TreeMultimap
```kotlin
import com.google.common.collect.TreeMultimap

val multimap = TreeMultimap.create<Double, Tranporter>()
multimap.put(distance, tranporter)
for (t in multimap.values()) {
    // process - same API!
}
```

### Using Kotlin Stdlib (Proposed Previous Approach)
```kotlin
val distanceMap = TreeMap<Double, MutableSet<Tranporter>>()
distanceMap.getOrPut(distance) { LinkedHashSet() }.add(tranporter)
for (transporterSet in distanceMap.values) {
    for (t in transporterSet) {
        // process
    }
}
```

## Recommendation for interlockSim

For this project, I recommend **Google Guava** for the following reasons:

1. **Minimal Code Changes**: The API is very similar to the current TreeMultiMap
2. **Industry Standard**: Widely used and trusted in production systems
3. **Additional Utilities**: Provides other useful collection utilities that could benefit the project
4. **Similar Use Cases**: Arrays2DMap and other custom collections could also benefit from Guava utilities

### Migration Path

1. Add Guava dependency to `build.gradle.kts`:
   ```kotlin
   implementation("com.google.guava:guava:33.0.0-jre")
   ```

2. Replace TreeMultiMap import:
   ```kotlin
   import com.google.common.collect.TreeMultimap
   ```

3. Update instantiation (minimal change):
   ```kotlin
   val treeMM = TreeMultimap.create<Double, Tranporter>()
   ```

4. Keep existing test cases - they should work with minimal modifications

### Alternative: Keep Current Implementation

If avoiding dependencies is a priority:
- The current `TreeMultiMap` class works well
- It's simple and maintainable
- Only 73 lines of code
- Well-tested (25 test cases)
- Consider it as a lightweight "utility" rather than a full feature

## Additional Considerations for Other Collections

### Array2DMap
Could potentially use:
- Guava's `Table<R, C, V>` interface (perfect for 2D mappings)
- Example: `HashBasedTable.create()` or `TreeBasedTable.create()`

### General Pattern
For custom collections, consider:
1. Is the complexity worth a dependency?
2. Is the custom implementation well-tested?
3. Would a library provide additional value?

## Conclusion

For TreeMultimap specifically, **Google Guava** is the best external option. However, given that:
- The current TreeMultiMap is simple and works well
- It's already tested
- The project uses it in only one location
- No other Guava features are needed yet

**Recommendation**: Keep the current TreeMultiMap implementation until there's a broader need for collection utilities, then consider migrating to Guava as a holistic improvement.

## References

- [Google Guava GitHub](https://github.com/google/guava)
- [Guava Multimap Documentation](https://github.com/google/guava/wiki/NewCollectionTypesExplained#multimap)
- [Apache Commons Collections](https://commons.apache.org/proper/commons-collections/)
- [Eclipse Collections](https://www.eclipse.org/collections/)
