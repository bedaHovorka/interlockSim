# Issue #291 Investigation Report: Shunting Loop Track Selection Bug

## Executive Summary

**Critical Finding**: Issue #291 (shunting loop track selection bug) is **NOT RESOLVED** on either branch tested.

- **feature/issue/291** (commit c64824c): ❌ Simulation **FAILS** with exception
- **origin/feature/issue/292** (commit dca530b): ⚠️ Simulation **COMPLETES** but behavior requires verification
- **Reference /tmp/working** (commit 18108fa): ✅ Known working baseline

**Status**: Both branches require further investigation before merging to develop.

**Date**: 2026-02-02
**Test Executor**: Claude Code
**Test Plan**: Comprehensive validation of Issue #291 fix against TopologyNavigator refactoring

---

## Problem Statement: Issue #291

### Original Bug Description

Trains incorrectly blocked with "path blocked" errors in ShuntingLoop simulation when navigating through partial paths:

1. **Scenario**: ShuntingLoop creates paths in segments (partial paths)
   - First segment: Entry semaphore (doA1/doB1) → shunting block (k1/k2)
   - Second segment: Shunting block (k1/k2) → Exit (via loop semaphore vA/vB)

2. **Problem**: Train navigates from doA1 into k2 block
   - Block k2 is RESERVED but from vA (loop semaphore), not from doA1 (entry semaphore)
   - Old strict validation: `reservedFrom (vA) != currentSeparator (doA1)` → **BLOCKED**
   - Train stops with "path blocked" error even though interlocking approved the path

3. **Expected Behavior**: Train should navigate through RESERVED blocks regardless of which separator reserved them (interlocking validates safety)

---

## Test Results Summary

### Branch 1: feature/issue/291 (Current Branch with Fix)

**Branch Details**:
- Commit: `c64824c` "Fix shunting loop track selection by relaxing block reservation validation (Issue #291)"
- Build Status: ✅ SUCCESS
- Test Count: 19/19 ShuntingLoopTest passing
- Diverged from issue/292 at: commit 12c56cc

**Simulation Result**: ❌ **FAILED**

**Error Log**:
```
09:57:37.400 [Thread-6] INFO - findReservedPathForTrain: block DynamicTrackBlock[staticRef=kB, state=RESERVED,
  occupant=null, from=Dynamic[B], trainId=null] is not reserved for train
  'cz.vutbr.fit.interlockSim.sim.Train$Front@340665e6' (owner: none), path not available

09:57:37.415 [Thread-6] INFO - Train 1: path permanently blocked after 5 retries - blocks reserved for other train

Exception in thread "Thread-6" SimulationException[FATAL]: Path to semaphore first element must match
  current position: null at time 16.0
	at cz.vutbr.fit.interlockSim.sim.Train$Front.separatorAction(Train.kt:720)
```

**Analysis**:
1. **Fix Location**: DefaultSimulationContext.getNextTrackSection() (lines 609-645)
   - Relaxed validation to allow RESERVED blocks (any separator)
   - Blocks only FREE blocks (never reserved)

2. **Conflict**: TrainNavigationService (added in Phase 3, commit 32c2836)
   - Has STRICTER validation: requires `owner == trainId` match
   - ShuntingLoop uses `trainId=null` during path setup
   - TrainNavigationService blocks train: "not reserved for this train"

3. **Root Cause**: Fix applied to wrong layer
   - DefaultSimulationContext relaxed validation (context layer)
   - TrainNavigationService strict validation (navigation service layer)
   - Two validation layers with conflicting rules → train blocked

**Conclusion**: The Issue #291 fix (commit c64824c) is **INCOMPLETE** and does not solve the problem.

---

### Branch 2: origin/feature/issue/292 (TopologyNavigator Migration)

**Branch Details**:
- Commit: `dca530b` "Phase 4: Migrate ShuntingLoop to TopologyNavigator (Issue #296)"
- Build Status: ✅ SUCCESS
- Test Count: 26/26 ShuntingLoopTest passing (7 new tests added)
- Includes: Phase 1-4 refactoring (Issues #293-296)

**Simulation Result**: ⚠️ **COMPLETES** (requires verification)

**Output**:
- Build: SUCCESS in 749ms
- Log: 265 lines of simulation activity
- Trains: 2 trains approved (Train #1 B→A, Train #2 A→B)
- Errors: No exceptions, no crashes
- Block navigation: Trains move through kB block

**Analysis**:
1. **Architecture Change**:
   - TopologyNavigator provides pure topological navigation (no state validation)
   - State validation moved to higher layers (TrainNavigationService, PathReservationService)
   - Separation of concerns: topology ≠ state validation

2. **Validation Approach**:
   - No validation in TopologyNavigator.getNextTrackSection()
   - TrainNavigationService checks trainId ownership
   - PathReservationService handles atomic path reservation

3. **Uncertainty**:
   - Simulation completes without crashes ✅
   - BUT: User confirms bug NOT resolved ⚠️
   - Requires deeper analysis of train behavior vs expected behavior

**Conclusion**: Issue/292 simulation runs but **behavior requires verification** against working baseline.

---

### Reference: /tmp/working (Working Tag Solution)

**Branch Details**:
- Commit: `18108fa` "Translate thesis from Czech to English"
- Status: Known working baseline
- Architecture: Pre-TopologyNavigator refactoring

**Simulation Result**: ✅ **SUCCESS**

**Output**:
- Build: SUCCESS in 2s
- Trains: 3 trains navigating successfully
- Completion: Full 60s simulation
- Behavior: Trains traverse kA, k1, k2, kB blocks through partial paths

**Architecture**:
- Pure topological navigation in DefaultContext.getNextTrackSection()
- No validation logic in core navigation method
- State validation happens elsewhere (path reservation, interlocking)

**Conclusion**: This is the **proven working baseline** for comparison.

---

## Code Analysis

### Issue #291 Fix (commit c64824c) - feature/issue/291

**File**: `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt`
**Lines**: 609-645

**Fix Implementation**:
```kotlin
// FIX for Issue #291: Relax block reservation validation to support partial paths
if (nextTrackBlock != null && current != null) {
    val blockState = nextTrackBlock.getState()
    val isReserved = blockState == TrackFacility.State.RESERVED
    val isOccupied = blockState == TrackFacility.State.OCCUPIED

    // Only block FREE blocks (not reserved by anyone)
    if (!isReserved && !isOccupied) {
        logger.info { "blocking navigation from $separator to FREE block" }
        return null  // Block entry - train will stop
    }
}
```

**Problem with Fix**:
1. ❌ Wrong layer: Applied to context navigation method, not navigation service
2. ❌ Conflicts with TrainNavigationService strict validation (Phase 3)
3. ❌ Doesn't address root architectural issue

**User Guidance**: "fix in new path discovery classes (not sim package)"
**Actual Fix Location**: DefaultSimulationContext (context package, but wrong approach)

---

### TrainNavigationService Strict Validation - feature/issue/291

**File**: `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultTrainNavigationService.kt`
**Lines**: 94-102

**Validation Logic**:
```kotlin
// Step 3: Validate ALL blocks are reserved for this train
for (block in blocks) {
    val owner = registry.getOwner(block)
    if (owner != trainId) {
        logger.info {
            "findReservedPathForTrain: block $block is not reserved for train '$trainId' " +
            "(owner: ${owner ?: "none"}), path not available"
        }
        return null  // Block not owned by this train, return null (train waits)
    }
}
```

**Issue**:
- Requires exact trainId ownership match
- ShuntingLoop creates paths with `trainId=null` during setup
- Train blocked even though block is RESERVED (just not for this specific trainId)

**Why This Conflicts**:
- DefaultSimulationContext: "Allow RESERVED blocks" (relaxed)
- TrainNavigationService: "Require trainId ownership" (strict)
- Two layers, conflicting rules → train blocked

---

### TopologyNavigator Approach - origin/feature/issue/292

**File**: `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultTopologyNavigator.kt`
**Lines**: 68-101

**Design Philosophy**:
```kotlin
/**
 * - PHASE 1 SCOPE: Pure topology traversal (no state validation)
 * - Navigation blocking for unreserved blocks
 *
 * This method provides ONLY topological navigation. State-aware navigation
 * will be handled by PathReservationService (Phase 2) and TrainNavigationService (Phase 3).
 */
override fun getNextTrackSection(
    separator: PathSeparator,
    current: TrackSection?
): TrackSection? {
    // Pure topology - no validation logic
    // Just find next section based on graph structure
    return result
}
```

**Architecture**:
- **Separation of concerns**: Topology navigation vs state validation
- **Pure topology**: TopologyNavigator has no state checks
- **State validation**: Handled by TrainNavigationService and PathReservationService
- **Matches working solution**: /tmp/working also uses pure topology navigation

**Uncertainty**:
- Architecture appears correct ✅
- Simulation completes without crashes ✅
- BUT: User reports bug not resolved ⚠️
- Requires verification of actual train behavior

---

### Working Solution Approach - /tmp/working

**File**: `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultContext.kt`

**Implementation**:
```kotlin
override fun getNextTrackSection(
    separator: PathSeparator,
    current: TrackSection?
): TrackSection? {
    // Pure topology navigation - no validation
    // Just find next section based on graph structure
    return result
}
```

**Key Insight**: Working solution has NO validation in core navigation method.

---

## Architecture Comparison

### Issue #291 Fix Architecture (Incorrect - Conflicting Layers)

```
Train.semaphoreAction()
  ↓
DefaultSimulationContext.getNextTrackSection()
  ├─ FIX: Allow RESERVED blocks (relaxed validation)
  ↓
TrainNavigationService.findReservedPathForTrain()
  ├─ Require trainId ownership (strict validation)
  ↓
  ❌ CONFLICT: Train blocked due to conflicting validation rules
```

**Problem**: Two validation layers with different rules.

---

### Issue #292 Architecture (Separation of Concerns)

```
Train.semaphoreAction()
  ↓
TrainNavigationService.findReservedPathForTrain()
  ├─ State validation layer (checks trainId ownership)
  ↓
TopologyNavigator.getNextTrackSection()
  ├─ Pure topology layer (no state validation)
  ↓
  ⚠️ BEHAVIOR UNCERTAIN: Requires verification against working baseline
```

**Design**: Clean separation, but actual behavior needs validation.

---

### Working Solution Architecture (Proven Baseline)

```
Train.semaphoreAction()
  ↓
[State validation in interlocking/path reservation]
  ↓
DefaultContext.getNextTrackSection()
  ├─ Pure topology navigation (no validation)
  ↓
  ✅ SUCCESS: Trains navigate through partial paths correctly
```

**Proven**: This architecture is known to work correctly.

---

## Key Findings

### 1. Issue #291 Fix is Incomplete

**Evidence**:
- Simulation fails with exception despite the fix
- TrainNavigationService blocks trains due to strict trainId validation
- Fix applied to wrong layer (context instead of navigation service)

**Reason**:
- Commit c64824c only fixed DefaultSimulationContext.getNextTrackSection()
- Did NOT fix TrainNavigationService.findReservedPathForTrain()
- Two validation layers with conflicting rules

### 2. Issue #292 Behavior is Uncertain

**Evidence**:
- Simulation completes without crashes or exceptions
- 26/26 tests pass (vs 19/19 on issue/291)
- 2 trains approved and moving

**BUT**:
- User confirms bug NOT resolved
- Requires comparison with working baseline behavior
- Need to verify trains actually navigate through partial paths correctly

### 3. Working Solution Uses Pure Topology

**Evidence**:
- /tmp/working has NO validation in getNextTrackSection()
- Issue/292 also uses pure topology approach
- Both use separation of concerns (topology vs state)

**Implication**:
- Architecture direction appears correct
- BUT: Something else must be wrong if bug not resolved

---

## Outstanding Questions for Future Investigation

### 1. What is the Expected Behavior?

**Questions**:
- How many trains should complete in 60s simulation?
- What is the correct sequence: Entry → k1/k2 → Exit?
- Should trains use partial paths or full paths?
- What does "working correctly" mean for ShuntingLoop?

**Actions**:
- Document exact expected behavior from /tmp/working baseline
- Create test criteria: train count, path segments, timing
- Define success metrics

### 2. What is Actually Failing on Issue/292?

**Questions**:
- Does issue/292 have same train count as /tmp/working?
- Do trains navigate through k1/k2 blocks correctly?
- Are partial paths being created and used?
- Is there a subtle behavioral difference?

**Actions**:
- Compare train movement logs: issue/292 vs /tmp/working
- Check block sequence: which blocks do trains enter/exit?
- Verify partial path usage: are paths created in segments?
- Look for timing differences or deadlocks

### 3. Where Should the Fix Be Applied?

**Possible Locations**:
- ❌ DefaultSimulationContext.getNextTrackSection() - wrong layer (already tried)
- ❌ Train.kt retry logic - wrong package (sim/ package)
- ⚠️ TrainNavigationService.findReservedPathForTrain() - strict trainId check?
- ⚠️ PathReservationService - path reservation logic?
- ⚠️ ShuntingLoop - partial path creation?

**Actions**:
- Identify where trainId=null causes problems
- Determine if trainId should be set earlier
- Check if partial paths need different validation approach

### 4. Is TrainId Ownership the Real Problem?

**Observation**:
- ShuntingLoop creates paths with `trainId=null`
- TrainNavigationService requires `owner == trainId` match
- Train blocked: "not reserved for this train"

**Questions**:
- Should ShuntingLoop set trainId during path creation?
- Should TrainNavigationService allow trainId=null for partial paths?
- Is the ownership model correct for partial path scenarios?

**Actions**:
- Review ShuntingLoop path creation logic
- Check how /tmp/working handles trainId
- Determine if ownership model needs adjustment

---

## Recommendations for Future Work

### Immediate Actions

1. **Do NOT merge feature/issue/291**
   - Simulation fails with exception
   - Fix is incomplete
   - Conflicting validation layers

2. **Do NOT merge origin/feature/issue/292 yet**
   - Behavior requires verification
   - User confirms bug not resolved
   - Need comparison with working baseline

3. **Document working baseline behavior**
   - Record expected train count, path sequences, timing
   - Create detailed behavioral specification
   - Establish test criteria for "correct behavior"

### Investigation Tasks

1. **Compare Simulation Logs**
   - Side-by-side comparison: issue/292 vs /tmp/working
   - Train movement through blocks (k1, k2, kA, kB)
   - Path creation and reservation sequences
   - Timing and deadlock analysis

2. **Analyze TrainId Ownership**
   - How does /tmp/working handle trainId?
   - When is trainId set in path creation?
   - Should TrainNavigationService allow trainId=null?

3. **Review Partial Path Logic**
   - How does ShuntingLoop create partial paths?
   - Is partial path creation correct on both branches?
   - Does TopologyNavigator handle partial paths differently?

4. **Test with Longer Simulation**
   - Run 300s simulation (5 minutes) instead of 60s
   - Check for deadlocks or stuck trains
   - Compare train counts and completion rates

### Testing Strategy

1. **Create Golden Output Test**
   - Use /tmp/working as golden baseline
   - Record complete simulation log
   - Create diff tool to compare branch outputs

2. **Add Detailed Assertions**
   - Assert train count at various times
   - Assert block occupancy sequences
   - Assert path creation patterns

3. **Test Specific Scenarios**
   - Test case: Entry → k1 → Exit (partial path via k1)
   - Test case: Entry → k2 → Exit (partial path via k2)
   - Test case: Multiple trains (deadlock scenarios)

---

## Files Examined

### feature/issue/291 Branch
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt` (lines 609-645)
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultTrainNavigationService.kt` (lines 94-102)
- `src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt` (retry logic)

### origin/feature/issue/292 Branch
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultTopologyNavigator.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultTrainNavigationService.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationService.kt`
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationRegistry.kt`

### /tmp/working Worktree
- `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultContext.kt`

---

## Commits Analyzed

1. **c64824c** (feature/issue/291): "Fix shunting loop track selection by relaxing block reservation validation"
   - Incomplete fix - only fixed one layer
   - Conflicts with TrainNavigationService

2. **32c2836** (both branches): "Phase 3: Implement Train Navigation Service (Issue #295)"
   - Introduced TrainNavigationService with strict trainId validation
   - Conflicts with Issue #291 fix approach

3. **dca530b** (origin/feature/issue/292): "Phase 4: Migrate ShuntingLoop to TopologyNavigator"
   - Completed TopologyNavigator refactoring
   - Pure topology navigation
   - Behavior uncertain

4. **18108fa** (/tmp/working): "Translate thesis from Czech to English"
   - Known working baseline
   - Pure topology navigation
   - No validation in core navigation

---

## Test Execution Summary

### Test Plan Phases Completed

✅ **Phase 1**: Baseline Validation (feature/issue/291)
- Confirmed on commit c64824c
- Build successful
- 19/19 ShuntingLoopTest passing
- ❌ Simulation failed with exception

✅ **Phase 2**: Branch Switch (origin/feature/issue/292)
- Reset to commit dca530b successful
- Verified TopologyNavigator migration present

✅ **Phase 3**: Issue/292 Testing
- Build successful
- 26/26 ShuntingLoopTest passing
- ⚠️ Simulation completes (behavior uncertain)

✅ **Phase 4**: Code Analysis
- Compared getNextTrackSection implementations
- Identified validation logic differences
- Traced architecture evolution

✅ **Phase 6**: Working Solution Analysis
- Confirmed pure topology navigation
- Validated as working baseline
- ✅ Simulation succeeds (3 trains)

⏭️ **Phase 5**: Apply Fix - **SKIPPED** (fix incomplete)
⏭️ **Phase 7**: Fix in Correct Location - **DEFERRED** (needs investigation)

---

## Conclusion

**Issue #291 is NOT RESOLVED on either branch.**

- **feature/issue/291**: Fix incomplete, simulation crashes
- **origin/feature/issue/292**: Simulation completes, behavior uncertain

**Both branches require further investigation before merging to develop.**

Next steps:
1. Compare detailed behavior: issue/292 vs /tmp/working
2. Identify what "bug not resolved" means specifically
3. Determine correct fix location and approach
4. Implement and verify complete solution

---

## Appendix: Test Logs

### feature/issue/291 Failure (Excerpt)

```
09:57:37.400 [Thread-6] INFO - findReservedPathForTrain: block DynamicTrackBlock[staticRef=kB,
  state=RESERVED, occupant=null, from=Dynamic[B], trainId=null] is not reserved for train
  'cz.vutbr.fit.interlockSim.sim.Train$Front@340665e6' (owner: none), path not available

09:57:37.401 [Thread-6] INFO - Train 1 cannot navigate from  - blocks not reserved for this train, halting
09:57:37.401 [Thread-6] INFO - 0.0 Train #1 STOP (path not reserved)
09:57:37.415 [Thread-6] INFO - Train 1: path permanently blocked after 5 retries
09:57:37.416 [Thread-6] INFO - 15.0 Train #1 WAIT (path reserved for other train)

Exception in thread "Thread-6" SimulationException[FATAL]: Path to semaphore first element
  must match current position: null at time 16.0
```

### origin/feature/issue/292 Success (Excerpt)

```
10:00:38.177 [Thread-4] INFO - 0.0 Train #1 approved B->A
10:00:38.277 [Thread-4] INFO - 52.0 Train #2 approved A->B
...
10:00:38.202 [Thread-6] INFO - 1.0 TrackBlock 1471086700 ENTRY: occupant=Train #1,
  state=RESERVED->OCCUPIED, trainId=Train #1
...
BUILD SUCCESSFUL in 749ms
```

### /tmp/working Success (Excerpt)

```
10:02:01.388 [Thread-3] INFO - 1.0 Train #1 2.879... 2.880... 1.440... kA null 98.559...
10:02:01.389 [Thread-3] INFO - 2.0 Train #1 2.879... 5.760... 5.760... kA null 94.239...
...
10:02:02.061 [Thread-3] INFO - 60.0 Train #3 -0.960... 10.502... 42.546... kB kB 57.453...
10:02:02.061 [Thread-3] INFO - 60.0 Train #2 4.640... 23.998... 260.089... kA kA 59.910...
BUILD SUCCESSFUL in 2s
```

---

*Report Date: 2026-02-02*
*Test Executor: Claude Code*
*Next Steps: Detailed behavioral comparison required*
