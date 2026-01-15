# Phase 1 Completion: SimpleTrack Refactoring - Breaking Changes Documentation

**Issue:** bedaHovorka/interlockSim#100.2  
**Date:** 2026-01-15  
**Status:** Phase 1 Complete - Breaking Changes Documented

## Summary

Phase 1 has successfully refactored `SimpleTrack` to remove dynamic state, achieving clean separation between static (immutable) and dynamic (mutable) track properties through interface segregation.

## What Changed

### 1. New Interfaces Created

**StaticTrack** (`src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/StaticTrack.kt`)
- Defines immutable track properties: `length()`, `ends()`, `maxSpeed()`, `getSecondEnd()`
- Used for editing-time configuration that doesn't change during simulation
- Extends `PathElement`

**DynamicTrackBehavior** (`src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBehavior.kt`)
- Defines mutable track operations: `enter()`, `leave()`, `setUpPath()`, `isSetUpPath()`, `cancelPathSetup()`, `getState()`, `isFreeFrom()`, `getTrackOccupant()`
- Used for simulation-time state management
- Independent interface for clean separation

### 2. Interface Hierarchy Updated

**Track** interface now extends both `StaticTrack` and `DynamicTrackBehavior`
- Maintains backward compatibility for code that needs both aspects
- Enables interface segregation where only static or dynamic is needed

**TrackFacility** simplified
- No longer directly declares `getState()` (inherited from `DynamicTrackBehavior`)
- Still defines the `State` enum (FREE, RESERVED, OCCUPIED)

**TrackSection** simplified
- No longer directly declares `enter()`, `leave()`, `getTrackOccupant()` (inherited from `DynamicTrackBehavior`)
- Only declares `getTrackBlock()`

### 3. SimpleTrack Refactored

**Before:** Implemented `TrackSection` and `TrackFacility` with dynamic state
```kotlin
abstract class SimpleTrack : AbstractTrack(), TrackSection, TrackFacility {
    private var `in`: TrackOccupant? = null
    private var from: PathSeparator? = null
    private var state = TrackFacility.State.FREE
    // ... dynamic methods ...
}
```

**After:** Implements only `StaticTrack` with immutable configuration
```kotlin
abstract class SimpleTrack : AbstractTrack(), StaticTrack {
    // Only static fields remain:
    private val speeds: IdentityHashMap<PathSeparator, Double>
    private val ends: Array<PathSeparator>
    // Only static methods: length(), ends(), maxSpeed()
}
```

### 4. SimpleTrackBlock Updated

**SimpleTrackBlock** now has temporary stub implementations:
- Implements `TrackBlock`, `TrackSection`, `TrackFacility` (for interface compatibility)
- All dynamic methods throw `UnsupportedOperationException` with clear Phase 1 message
- Inherits only static properties from `SimpleTrack`
- **Phase 2 responsibility:** Replace stubs with DynamicTrack integration

### 5. Test Files Updated

**SimpleTrackStateTest** - Now uses `DynamicTrack` wrapper
```kotlin
val staticTrack = SimpleTrackBlock(end1, end2, 100.0, 80.0)
val track = DynamicTrack(staticTrack)
// Tests access state via: track.state, track.enter(), etc.
```

**SimpleTrackEnterLeaveTest** - Now uses `DynamicTrack` wrapper
- Same pattern as StateTest
- Demonstrates proper separation: static config + dynamic wrapper

### 6. Exception Handling Updated

**TrackOperationException** now accepts `StaticTrack`
- Exceptions only need track identity/configuration, not dynamic state
- Enables exceptions to work with both pure static tracks and full Track instances

## Breaking Changes

### Compilation Breaks (Intentional)

**Test Compilation:** 107 errors in test files
- Tests try to use `SimpleTrack` where `Track` is expected
- Tests call dynamic methods (`enter()`, `setUpPath()`, etc.) on `SimpleTrack` directly

**Affected Test Files:**
1. `PathTrackIntegrationTest.kt` - Direct dynamic method calls on tracks
2. `SimulationExceptionTest.kt` - Passes `SimpleTrack` where `Track` expected
3. Various integration and simulation tests

**Root Cause:**
```kotlin
// OLD CODE (Phase 0): SimpleTrack implements Track with dynamic state
val track = SimpleTrackBlock(...)
track.setUpPath(sep)  // ✓ Works
track.enter(occupant) // ✓ Works

// NEW CODE (Phase 1): SimpleTrack implements only StaticTrack
val track = SimpleTrackBlock(...)
track.setUpPath(sep)  // ✗ Compilation error - not available
track.enter(occupant) // ✗ Compilation error - not available

// SOLUTION (Phase 2): Use DynamicTrack wrapper
val staticTrack = SimpleTrackBlock(...)
val dynamicTrack = DynamicTrack(staticTrack)
dynamicTrack.setUpPath(sep)  // ✓ Works
dynamicTrack.enter(occupant) // ✓ Works
```

### Runtime Breaks (Expected)

**SimpleTrackBlock stubs throw at runtime:**
```kotlin
val track = SimpleTrackBlock(...)
track.setUpPath(sep) // Throws UnsupportedOperationException:
// "Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. 
//  Use DynamicTrack wrapper for path operations."
```

## What Works

### ✓ Compilation Success

1. **Main code compiles** - All source code in `src/main` compiles successfully
2. **Interface segregation works** - Clean separation between static and dynamic
3. **SimpleTrack tests compile** - Updated tests using DynamicTrack compile correctly

### ✓ Architecture Improvements

1. **Clean interfaces** - ISP (Interface Segregation Principle) applied
2. **Immutable configuration** - SimpleTrack is now purely static
3. **DynamicTrack wrapper ready** - Already exists and is used in updated tests

## Phase 2 Requirements

### Must Fix

1. **Test Compilation Errors (107 total)**
   - Update test files to use DynamicTrack wrapper
   - Pattern: Wrap SimpleTrackBlock with DynamicTrack for state operations
   - Example files: `PathTrackIntegrationTest.kt`, `SimulationExceptionTest.kt`

2. **Simulation Runtime Integration**
   - Simulation code currently expects `Track` with dynamic state
   - Must integrate DynamicTrack wrapper into simulation execution
   - Context must create DynamicTrack wrappers for SimpleTrackBlock instances

3. **SimpleTrackBlock Integration**
   - Remove stub implementations from SimpleTrackBlock
   - Integrate with DynamicTrack (either delegation or composition)
   - Decision needed: Should SimpleTrackBlock create its own DynamicTrack internally?

### Design Questions for Phase 2

1. **Wrapper Ownership:**
   - Should SimpleTrackBlock own a DynamicTrack instance internally?
   - Or should SimulationContext maintain a map of Track → DynamicTrack?

2. **Factory Pattern:**
   - Should there be a TrackFactory that creates properly wrapped tracks?
   - How should editing context (static only) vs simulation context (needs dynamic) be handled?

3. **Backward Compatibility:**
   - Should we maintain a compatibility layer for code that expects `Track` interface?
   - Or force migration to explicit static/dynamic separation everywhere?

## Files Modified (Phase 1)

### New Files Created
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/StaticTrack.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBehavior.kt`

### Files Modified
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/Track.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrackFacility.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrackSection.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/SimpleTrack.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/SimpleTrackBlock.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/AbstractTrack.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/objects/paths/AbstractPath.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/exceptions/TrackOperationException.kt`
- `src/test/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/SimpleTrackStateTest.kt`
- `src/test/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/SimpleTrackEnterLeaveTest.kt`

## Recommendations for Phase 2

### Immediate Actions

1. **Fix Test Compilation**
   - Start with `PathTrackIntegrationTest.kt` - wrap SimpleTrack instances
   - Then `SimulationExceptionTest.kt` - update exception assertions
   - Pattern to follow: See updated `SimpleTrackStateTest.kt`

2. **Context Integration**
   - SimulationContext should maintain DynamicTrack wrappers
   - Consider using a WeakHashMap<TrackFacility, DynamicTrack> for wrapper management
   - Ensure stable identity (same static track → same dynamic wrapper)

3. **Remove SimpleTrackBlock Stubs**
   - Once simulation integration is complete, remove temporary stubs
   - Either delegate to internal DynamicTrack or make SimpleTrackBlock itself dynamic

### Long-term Considerations

1. **DynamicTrack Evolution**
   - Current DynamicTrack is already well-designed (Phase 4 from issue #92)
   - May need minor adjustments for full Phase 2 integration
   - Consider adding factory methods for common patterns

2. **Documentation Updates**
   - Update CLAUDE.md with new interface hierarchy
   - Document the static/dynamic separation pattern
   - Add examples of proper track usage in both contexts

3. **Migration Guide**
   - Create guide for converting old Track usage to new pattern
   - Show examples of common patterns (reservation, entry, exit)
   - Explain when to use StaticTrack vs Track vs DynamicTrack

## Success Criteria for Phase 2

- [ ] All 107 test compilation errors fixed
- [ ] All tests pass (including integration tests)
- [ ] Simulation runs successfully with DynamicTrack integration
- [ ] SimpleTrackBlock stubs removed, proper implementation in place
- [ ] No runtime UnsupportedOperationException from track operations
- [ ] Documentation updated to reflect new architecture
- [ ] Performance impact assessed and documented

## Conclusion

Phase 1 has successfully achieved its primary goal: **Remove dynamic state from SimpleTrack to achieve clean separation.** 

The intentional breaking changes create clear boundaries that will force Phase 2 to properly integrate the DynamicTrack wrapper pattern throughout the codebase, resulting in a more maintainable and well-architected system.

**Risk Level:** HIGH (as specified in issue) - Many tests broken, simulation will fail at runtime  
**Next Step:** Phase 2 implementation to integrate DynamicTrack and fix all breaking changes
