# Phase 7: Path System Dynamic References - Design Notes

## Summary
Updated the path system to store dynamic cell references during simulation instead of static references.

## Changes Made

### 1. pathToNextSemaphore (DefaultSimulationContext.kt)
**Before:**
- Input separator could be static or dynamic
- Added separators directly to path without conversion
- Used `staticToDynamicMap` for mid-path conversions

**After:**
- Input separator converted to dynamic at start using `toDynamic(sep)`
- All separators added to path are guaranteed to be dynamic
- Simplified conversion logic by using `toDynamic()` method consistently

**Code Location:** Lines 777-819

### 2. toDynamic() Method Documentation
Updated documentation to clarify Phase 7 usage in path construction.

**Code Location:** Lines 601-626

### 3. New Test: PathDynamicReferencesTest
Comprehensive integration test validating that paths created in simulation context contain only dynamic references.

**Test Cases:**
- pathToNextSemaphore returns dynamic separators
- Handles dynamic input separator (idempotent)
- Converts static input to dynamic
- Path iteration returns dynamic separators

**Code Location:** src/test/kotlin/.../paths/PathDynamicReferencesTest.kt

## Question: "Remove toDynamic?"

### Answer: No, but usage is now minimized

**Why toDynamic() is still needed:**

1. **Static/Dynamic Bridge**: Track operations (like `getSecondEnd()`) work with static references because:
   - Tracks store static PathSeparator references at their ends
   - Identity comparison (===) requires static references
   - Example: `next.getSecondEnd(staticSeparator)` returns static PathSeparator

2. **Pattern in Code**:
   ```kotlin
   // Extract static for track operation
   val staticSeparator = CellUtilities.assertNodeCell(separator)
   val staticResult = next.getSecondEnd(staticSeparator)
   // Convert result back to dynamic for path storage
   separator = toDynamic(staticResult)
   ```

3. **Current Uses of toDynamic()**:
   - **pathToNextSemaphore**: Convert static results from `getSecondEnd()` (necessary)
   - **Train.kt**: Convert static results from track navigation (necessary)
   - **ShuntingLoop**: Convert pre-constructed static paths (special case)
   - **AbstractPath**: Convert tracks to DynamicTrack wrappers (necessary)

**What Changed:**
- Paths now store **only dynamic** references (no static separators in paths)
- Path construction converts to dynamic **at creation time** (not during traversal)
- AbstractPath operations receive dynamic separators (no conversion needed there)

**Benefits:**
- ✅ Paths are consistent - all separators are dynamic
- ✅ No runtime type checking needed when traversing paths
- ✅ Clear separation: paths = dynamic, tracks = static (wrapped on-demand)
- ✅ toDynamic() is idempotent (calling on dynamic returns same instance)

## Type Parameter Decision

**Question:** Should Path interface have type parameter `Path<C : Cell>`?

**Answer:** No

**Rationale:**
1. Path stores heterogeneous elements (PathSeparator + Track)
2. Type parameter would require Path<PathElement> or Path<*>
3. Runtime verification via `is DynamicPathSeparator` is clearer
4. Kotlin's smart casts work well with interface checks
5. No benefit to type safety since we store mixed element types

## Testing Status

**Unit Tests:** Created PathDynamicReferencesTest (4 test cases)
**Integration Tests:** Requires jDisco dependency (unavailable in current environment)
**Manual Review:** Code changes are minimal and follow existing patterns

## Remaining Work (if any)

1. Run full test suite when jDisco dependency is available
2. Verify ShuntingLoop example still works (uses convertPathToDynamic)
3. Consider adding helper method: `getSecondEndDynamic()` to eliminate conversion pattern

## Notes for Future Phases

**Phase 8-9**: When dynamic grid is stored separately, `toDynamic()` can potentially use grid lookup instead of map lookup. The conversion will still be necessary at the static/dynamic boundary.

**Potential Optimization**: Add `getSecondEndDynamic(separator: DynamicPathSeparator)` helper method to TrackSection to encapsulate the static extraction + conversion pattern. This would hide the conversion from calling code.
