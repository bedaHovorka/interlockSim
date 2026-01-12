# XMLContextFactory Singleton Consistency Analysis

## Problem Statement

Review feedback from [PR #63 discussion #2680878970](https://github.com/bedaHovorka/interlockSim/pull/63#discussion_r2680878970) raised a concern about inconsistent XMLContextFactory access patterns:

> "Inconsistent XMLContextFactory access pattern. The removed code used getContextFactory() which returns XMLContextFactory.getInstance() (the singleton), but the new code injects editingContextFactory from Koin. Due to the issue in InterlockSimModule.kt line 60, these may be different instances. The getContextFactory() method at line 165 now returns the Koin-injected instance, breaking backward compatibility for any code that expects getInstance() and getContextFactory() to return the same object."

## Root Cause Analysis

The concern was that two different XMLContextFactory instances could exist:
1. The original singleton accessed via `XMLContextFactory.getInstance()`
2. A new instance created by Koin DI

This would break backward compatibility for code expecting these to be the same instance.

## Solution

The issue was addressed in commit [50f6147](https://github.com/bedaHovorka/interlockSim/commit/50f614711a546a0f99fa37b5d8736d468136b589) by ensuring Koin uses the existing singleton:

```kotlin
// InterlockSimModule.kt - xmlModule
val xmlModule: Module = module {
	// XMLContextFactory implements EditingContextFactory and SimulationContextFactory
	// Use the existing singleton instance to maintain backward compatibility
	// (Factory interfaces bound in contextModule)
	single<XMLContextFactory> { XMLContextFactory.getInstance() }  // ← Uses existing singleton
}
```

## Verification

All XMLContextFactory access patterns now return the **same singleton instance**:

### 1. Direct Singleton Access
```kotlin
val factory = XMLContextFactory.getInstance()
```
Returns: Original singleton instance

### 2. Koin XMLContextFactory Access
```kotlin
val factory = get<XMLContextFactory>(XMLContextFactory::class.java)
```
Returns: Same singleton instance (configured via `getInstance()`)

### 3. Koin EditingContextFactory Access
```kotlin
val factory = get<EditingContextFactory>(EditingContextFactory::class.java)
```
Returns: Same singleton instance (bound via `get<XMLContextFactory>()`)

### 4. Koin SimulationContextFactory Access
```kotlin
val factory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
```
Returns: Same singleton instance (bound via `get<XMLContextFactory>()`)

### 5. Main.getContextFactory() Access
```kotlin
val factory = Main.getInstance().getContextFactory()
```
Returns: Same singleton instance (injected from Koin as EditingContextFactory)

## Configuration Flow

```
XMLContextFactory.getInstance() (line 292-298 in XMLContextFactory.kt)
    ↓
    Creates singleton: private val instance = XMLContextFactory()
    ↓
InterlockSimModule.xmlModule (line 62)
    ↓
    single<XMLContextFactory> { XMLContextFactory.getInstance() }
    ↓
InterlockSimModule.contextModule (lines 82-83)
    ↓
    single<EditingContextFactory> { get<XMLContextFactory>() }
    single<SimulationContextFactory> { get<XMLContextFactory>() }
    ↓
Main.kt (line 51)
    ↓
    private val editingContextFactory: EditingContextFactory by inject(...)
    ↓
Main.getContextFactory() (line 165)
    ↓
    fun getContextFactory(): ContextFactory = editingContextFactory
```

## Test Coverage

A comprehensive test suite has been added in `KoinSingletonConsistencyTest.kt` to verify:

1. ✅ Koin XMLContextFactory is same instance as singleton getInstance()
2. ✅ Koin EditingContextFactory is same instance as XMLContextFactory singleton
3. ✅ Koin SimulationContextFactory is same instance as XMLContextFactory singleton
4. ✅ Main.getContextFactory() returns same instance as XMLContextFactory singleton
5. ✅ All factory access patterns return the same singleton instance

All tests use `assertThat(x).isSameInstanceAs(y)` to verify reference equality, not just value equality.

## Backward Compatibility Guarantee

**GUARANTEED:** All code, whether using:
- Direct `XMLContextFactory.getInstance()` calls
- Koin dependency injection
- `Main.getInstance().getContextFactory()`

...will receive the **exact same XMLContextFactory singleton instance**.

No breaking changes to existing APIs or behavior.

## Related Commits

- **ccfb900**: Initial Koin integration (had the issue)
- **50f6147**: Fix XMLContextFactory singleton inconsistency (resolved the issue)
- **44bb1ae**: Add comprehensive singleton consistency tests (this PR)

## Conclusion

The XMLContextFactory singleton consistency concern has been **fully addressed**:
1. ✅ Configuration uses `getInstance()` to maintain singleton
2. ✅ All factory interfaces bind to the same singleton
3. ✅ Comprehensive test coverage validates consistency
4. ✅ Backward compatibility is maintained
5. ✅ No breaking changes introduced
