# TreeMultiMap Removal - Technical Summary

**Issue**: Remove custom TreeMultiMap implementation in favor of Kotlin/Java standard library
**PR**: #21 discussion resolution
**Date**: January 2026

## Problem

The codebase contained a custom `TreeMultiMap<K, V>` implementation that was:
- Deprecated with a suggestion to use standard library
- Only used in one location: `DefaultContext.findTrackLineParts()`
- Maintained 73 lines of custom code + 386 lines of test code
- Duplicating functionality available in standard Java/Kotlin libraries

## Solution

Replaced `TreeMultiMap<K, V>` with standard library `TreeMap<K, MutableSet<V>>`.

### API Mapping

| TreeMultiMap API | Standard Library Equivalent |
|------------------|----------------------------|
| `TreeMultiMap<K, V>()` | `TreeMap<K, MutableSet<V>>()` |
| `put(key, value)` | `map.getOrPut(key) { LinkedHashSet() }.add(value)` |
| `get(key): Set<V>?` | `map[key]` (returns `MutableSet<V>?`) |
| `values(): Collection<V>` | `map.values.flatten()` or nested iteration |

### Behavior Preservation

The replacement maintains identical behavior:
- **Sorted keys**: TreeMap maintains natural ordering of keys
- **Multiple values per key**: MutableSet stores multiple values
- **Insertion order per key**: LinkedHashSet preserves insertion order within each key's values
- **Unmodifiable return**: Not required in the actual usage (internal only)

### Code Changes

**Before** (`DefaultContext.kt`, lines 200-227):
```kotlin
val treeMM = TreeMultiMap<Double, Tranporter>()

// Population
treeMM.put(distance, Tranporter(p1, p2, s1, s2))

// Iteration
for (t in treeMM.values()) {
    val tryJoin = tryJoin(t, key1, key2, trackBlock)
    if (tryJoin != null) return tryJoin
}
```

**After**:
```kotlin
val distanceMap = TreeMap<Double, MutableSet<Tranporter>>()

// Population
distanceMap.getOrPut(distance) { LinkedHashSet() }.add(Tranporter(p1, p2, s1, s2))

// Iteration (nested to handle Set<Set<V>>)
for (transporterSet in distanceMap.values) {
    for (t in transporterSet) {
        val tryJoin = tryJoin(t, key1, key2, trackBlock)
        if (tryJoin != null) return tryJoin
    }
}
```

## Files Changed

### Removed Files
- `src/main/kotlin/cz/vutbr/fit/interlockSim/util/TreeMultiMap.kt` (-73 lines)
- `src/test/kotlin/cz/vutbr/fit/interlockSim/util/TreeMultiMapTest.kt` (-386 lines)

### Modified Files
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultContext.kt` (+14 lines, -6 lines)
- `README.md` (updated test counts)
- `CLAUDE.md` (removed TreeMultiMap references)
- `JAVA21-MIGRATION-SUMMARY.md` (removed TreeMultiMapTest)
- `KOTLIN-MIGRATION-STATUS.md` (removed TreeMultiMapTest)

## Impact

### Positive
- **-459 net lines of code** (473 removed, 14 added)
- **Removed 25 test cases** (now unnecessary)
- **Simplified dependencies** (no custom collection types)
- **More idiomatic Kotlin** (using `getOrPut()` standard library function)
- **Better semantics** (explicit `TreeMap` and `MutableSet` types)
- **Reduced maintenance burden** (one less custom class to maintain)

### Risk Assessment
- **Low risk**: Single usage point, behavior-preserving refactoring
- **Test coverage**: Existing tests for `DefaultContext` cover the usage
- **CI verification**: GitHub Actions will run full test suite including integration tests

## Testing

### Existing Coverage
The modified code in `DefaultContext.findTrackLineParts()` is covered by:
- `DefaultContextTest` (8 tests)
- `BresenhamJoinTest` (indirect coverage of join operations)
- Integration tests (full simulation runs)

### Verification Strategy
1. Unit tests verify DefaultContext behavior unchanged
2. Integration tests verify full simulation functionality
3. Smoke test (shunting loop simulation) confirms no regression

## Future Considerations

This removal completes the deprecation cleanup for TreeMultiMap. The only remaining deprecated internal class is `Doubleton`, which is used more extensively and requires a separate design review.

## References

- Original issue: https://github.com/bedaHovorka/interlockSim/issues/[number]
- PR discussion: https://github.com/bedaHovorka/interlockSim/pull/21
- Kotlin standard library: https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/tree-map.html
