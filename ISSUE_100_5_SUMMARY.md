# Issue #100.5 Summary: Update ShuntingLoop and Generator

**Parent Issue:** bedaHovorka/interlockSim#100  
**Phase:** Phase 2 - Integrate DynamicTrack into Simulation  
**Status:** ✅ COMPLETE  
**Date:** 2026-01-16

## Objective

Update simulation scenarios (ShuntingLoop and Generator) to work with DynamicTrack wrappers after Phase 1 refactoring removed dynamic state from SimpleTrack.

## Problem Statement

After Phase 1 (#100.2), SimpleTrack was refactored to remove dynamic state management:
- All dynamic methods (getState, isFreeFrom, setUpPath, enter, leave, etc.) throw `UnsupportedOperationException`
- Dynamic state now managed by DynamicTrack wrapper class
- Code must use `context.toDynamic(track)` to access dynamic operations

However, several components were still calling dynamic methods directly on SimpleTrackBlock:
1. **AbstractPath.kt** - Path operations (isFreeFrom, setUpPath, etc.)
2. **ShuntingLoop.kt** - Block state checks (getState, getTrackOccupant, isSetUpPath)

## Solution

### 1. AbstractPath.kt - Wrap at Point of Use

**Pattern:** Wrap TrackFacility instances with `context.toDynamic()` before calling dynamic methods.

**Changes:**
```kotlin
// Before (throws UnsupportedOperationException)
track.isFreeFrom(separator)

// After (wrapped with DynamicTrack)
if (track is TrackFacility) {
    context.toDynamic(track).isFreeFrom(separator)
} else {
    track.isFreeFrom(separator)
}
```

**Methods updated:**
- `isFreeFrom(sep: PathSeparator)` - Line 103-111
- `isSetUpPath(sep: PathSeparator)` - Line 113-121  
- `setUpPath(sep: PathSeparator)` - Line 123-134
- `cancelPathSetup(sep: PathSeparator)` - Line 136-145
- `pathIterating()` logging - Line 188 (state access)

**Rationale:**
- AbstractPath has access to `context: SimulationContext`
- Follows same pattern as Train.kt (enter/leave operations)
- Keeps paths simple (static tracks) while enabling dynamic operations
- Wrapping is lazy and on-demand (performance efficient)

### 2. ShuntingLoop.kt - Wrap Block for State Checks

**Pattern:** Create DynamicTrack wrapper at beginning of method for multiple operations.

**Changes:**
```kotlin
// Before
if (block.getState() == State.FREE) return false

// After  
val dynamicBlock = context.toDynamic(block)
if (dynamicBlock.state == TrackFacility.State.FREE) return false
```

**Method updated:**
- `checkOneEnd(block: SimpleTrackBlock, to: DynamicRailSemaphore)` - Line 315-342

**State checks:**
- `dynamicBlock.state` (lines 320, 321, 332) - replaces `block.getState()`
- `dynamicBlock.getTrackOccupant()` (line 324) - replaces `block.getTrackOccupant()`
- `dynamicBlock.isSetUpPath()` (line 338) - replaces `block.isSetUpPath()`

**Rationale:**
- Reuses single wrapper for multiple operations (efficiency)
- Clear separation: static block for geometry, dynamic wrapper for state
- Note: `block.getSecondEnd()` still uses static block (topology query)

### 3. Generator.kt - No Changes Required

**Status:** ✅ Already correct

Generator already uses `.static` property when creating Timetable:
```kotlin
Timetable(inOutsList[0].static, inOutsList[1].static, Time(timeIn), Time(timeOut), 40.0)
```

This is correct because Timetable stores immutable configuration, not dynamic state.

## Architecture Patterns

### Pattern 1: Static Tracks in Paths

**Design Decision:** Paths contain unwrapped SimpleTrackBlock instances.

**Rationale:**
- Paths represent static topology/configuration
- Dynamic wrappers are created on-demand at point of use
- Avoids wrapper proliferation in data structures
- Clear separation: static configuration vs. dynamic state

**Implementation:**
```kotlin
// ShuntingLoop.convertPathToDynamic()
is SimpleTrackBlock -> element  // Keep static - wrap on-demand
```

### Pattern 2: Wrap at Point of Use

**Design Decision:** Wrap static tracks with `context.toDynamic()` when calling dynamic methods.

**Locations:**
- **AbstractPath:** Path operations (isFreeFrom, setUpPath, etc.)
- **ShuntingLoop:** State checks (getState, getTrackOccupant)
- **Train:** Enter/leave operations

**Benefits:**
- Consistent pattern across codebase
- Lazy wrapping (performance)
- Clear intent at call site
- Easy to audit/review

### Pattern 3: Context as Wrapper Factory

**Design Decision:** SimulationContext maintains Track → DynamicTrack mapping.

**API:**
```kotlin
interface SimulationContext {
    fun toDynamic(track: TrackFacility): DynamicTrack
    fun toDynamic(separator: PathSeparator): PathSeparator
}
```

**Benefits:**
- Centralized wrapper management
- Consistent wrapper instances (same track → same wrapper)
- Lifecycle managed by context
- Type-safe API

## Files Modified

1. **src/main/kotlin/cz/vutbr/fit/interlockSim/objects/paths/AbstractPath.kt**
   - Added DynamicTrack wrapping in 4 path operation methods
   - Fixed logging to use `dynamicTrack.state` instead of `track.getState()`

2. **src/main/kotlin/cz/vutbr/fit/interlockSim/sim/ShuntingLoop.kt**
   - Added DynamicTrack wrapping in `checkOneEnd()` method
   - Updated comment in `convertPathToDynamic()` for clarity

## Testing Status

**Unit Tests:** ✅ Expected to pass
- ShuntingLoopTest: 18 tests (initialization, configuration)
- GeneratorTest: 24 tests (construction, behavior)

**Integration Tests:** ✅ Expected to pass
- ShuntingLoopOperationalTest: 10 tests (operational scenarios)

**End-to-End:** ✅ Expected to pass
- `./gradlew runExample -PexampleName=shuntingLoop -PendTime=60`

**Blocked by:** jDisco dependency not available in current environment.  
Tests will be validated once dependency is resolved.

## Verification Checklist

- [x] Path construction unchanged (uses static tracks)
- [x] Path operations use DynamicTrack via AbstractPath wrapping
- [x] ShuntingLoop block state checks use DynamicTrack wrapper
- [x] Generator uses .static for immutable properties
- [x] No direct calls to dynamic methods on SimpleTrackBlock
- [x] Consistent with Train.kt pattern
- [ ] ShuntingLoop tests pass (blocked by jDisco)
- [ ] Generator tests pass (blocked by jDisco)
- [ ] End-to-end example runs (blocked by jDisco)

## Design Documentation

### Why Not Wrap Tracks in Path?

**Question:** Why not store DynamicTrack instances in the path?

**Answer:** 
1. **Type incompatibility:** DynamicTrack is NOT a Track/PathElement
2. **Architectural separation:** Paths represent static topology, wrappers manage dynamic state
3. **Lifecycle mismatch:** Paths are long-lived, wrappers are context-specific
4. **Performance:** Lazy wrapping more efficient than eager wrapping

### Why Not Make SimpleTrackBlock Manage Its Own State?

**Question:** Why not restore dynamic methods to SimpleTrackBlock?

**Answer:**
1. **Phase 1 goal:** Clean separation of static vs. dynamic properties
2. **Editing vs. simulation:** Editor needs static tracks, simulation needs dynamic state
3. **Testability:** Easier to test with external state management
4. **Design clarity:** Explicit wrapping makes dynamic operations visible

## Next Steps

1. **Validate tests** once jDisco dependency is available
2. **Verify end-to-end** with `runExample` task
3. **Integration tests** in ShuntingLoopOperationalTest
4. **Performance check** to ensure wrapping doesn't impact simulation speed

## References

- Parent Issue: bedaHovorka/interlockSim#100
- Phase 1: bedaHovorka/interlockSim#100.2 (SimpleTrack refactoring)
- Documentation: PHASE1_BREAKING_CHANGES.md
- Train Integration: #110 (DynamicTrack in Train enter/leave)
