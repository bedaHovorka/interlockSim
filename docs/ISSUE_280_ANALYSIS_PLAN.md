# Issue #280 Analysis Plan: Train Deadlock (Zero Acceleration)

**Issue:** https://github.com/bedaHovorka/interlockSim/issues/280
**Date:** 2026-01-24
**Status:** Planning Phase (No Implementation Yet)

## Team Assignment

**Primary Responsibility:** traffic-simulation-expert (sim/ package issue, critical blocker)
**Support:** kotlin-tech-lead (code architecture), java-senior-dev (regression analysis)
**Consultation:** railway-civil-engineer (validate train physics behavior)

## Executive Summary

After fixing train deadlock #275 (path reservation conflict), a NEW deadlock appeared in issue #280 where Train #1 shows zero acceleration around the first semaphore despite having the full path reserved from right InOut to left InOut.

**Critical Symptom:**
```
298.0 Train #1 0.0 4.238359807687085E-4 100.11923927567427 vB-around kB 0
                   ↑ acceleration is ZERO!                               ↑ distance to semaphore is ZERO!
```

**Root Cause Hypothesis:** `distanceToSemaphore()` returns 0, causing motor to stop accelerating (Train.kt:542).

## Timeline and Versions

| Version | Commit | Status | Description |
|---------|--------|--------|-------------|
| Working | `18108fa` | ✅ WORKING | Before PR #95, trains move correctly |
| Broken After #95 | ? | ❌ BROKEN | Path reservation conflict (#275) |
| Fix Attempt | `7c8cc3f` | ⚠️ NEW ISSUE | Fixed #275 but introduced #280 |
| Current | `HEAD` | ❌ BROKEN | Zero acceleration deadlock |

**Working logs:** https://github.com/bedaHovorka/interlockSim/actions/runs/21163388994/job/60862433123

## Analysis Strategy

### Phase 1: Environment Setup with Git Worktree

Create parallel working directories for side-by-side comparison:

```bash
cd /home/beda/work/interlockSim

# Create worktree for working version
git worktree add ../interlockSim-working 18108fa

# Create worktree for broken version after #275 fix
git worktree add ../interlockSim-broken 7c8cc3f

# Current directory stays on HEAD (feature/issue/280 branch)
```

This allows:
- Compare code files side-by-side
- Run both versions simultaneously
- Extract logs from working version
- Validate fixes against working baseline

### Phase 2: Root Cause Analysis

**Objective:** Understand why `distanceToSemaphore()` returns 0.

**Investigation Tasks:**

1. **Add Comprehensive Debug Logging** (Task #4)
   - Log `pathToSemaphore` state throughout train lifecycle
   - Track path assignment in `accelerateToSignal()`
   - Track path consumption in `separatorAction()`
   - Log distance calculation in `distanceToSemaphore()`

2. **Compare Path Management Logic** (Task #2)
   - File: `src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt`
   - Compare wrapper identity handling between 18108fa and 7c8cc3f
   - Focus on:
     - Line 200: `pathToNextSemaphore()` call
     - Line 303: `pathToSemaphore = path` assignment
     - Line 341-344: `pathToSemaphore?.removeFirst()` consumption
     - Line 591-592: `distanceToSemaphore()` calculation

3. **Analyze pathToNextSemaphore Implementation** (Task #5)
   - File: `src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt`
   - Check if wrapper identity changes affect path retrieval
   - Verify semaphore cache (ShuntingLoop.kt:63) correctness

4. **Verify Path Consumption Logic** (Task #3)
   - Why `removeFirst()` called twice? (Train.kt:341-344)
   - Is path being consumed too quickly?
   - Are path elements matching separator positions correctly?

### Phase 3: Code Comparison

**Files to Compare (Working 18108fa vs Broken 7c8cc3f):**

| File | Key Changes | Priority |
|------|-------------|----------|
| `sim/Train.kt` | Wrapper identity handling | HIGH |
| `context/DefaultSimulationContext.kt` | Path lookup, dynamic wrappers | HIGH |
| `sim/ShuntingLoop.kt` | Semaphore cache, path construction | MEDIUM |
| `util/DynamicWrapperUtils.kt` | NEW in 7c8cc3f | HIGH |
| `objects/tracks/DynamicTrack.kt` | Wrapper changes | MEDIUM |

**Comparison Commands:**

```bash
# Compare Train.kt
diff -u ../interlockSim-working/src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt \
        src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt

# Compare DefaultSimulationContext.kt
diff -u ../interlockSim-working/src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt \
        src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt
```

### Phase 4: Golden Output Comparison

**Objective:** Establish baseline behavior from working version.

```bash
# Run working version
cd ../interlockSim-working
./gradlew runExample -PexampleName=shuntingLoop -PendTime=120 > /tmp/working.log 2>&1

# Run broken version
cd /home/beda/work/interlockSim
./gradlew runExample -PexampleName=shuntingLoop -PendTime=120 > /tmp/broken.log 2>&1

# Compare outputs
diff -u /tmp/working.log /tmp/broken.log | less
```

**Key Metrics to Compare:**
- Train acceleration values (should never be 0 between semaphores)
- Distance to semaphore (should decrease monotonically)
- Path assignment events (when and what path is set)
- Path consumption events (removeFirst calls)
- Semaphore crossing events

### Phase 5: Hypothesis Validation

**Hypothesis 1: pathToSemaphore is null**
- Add logging: `logger.debug { "distanceToSem: pathNull=${pathToSemaphore == null}" }`
- Expected: Path should NOT be null during active movement

**Hypothesis 2: Path consumed too quickly**
- Track removeFirst() calls vs separator crossings
- Expected: Path elements should match train position

**Hypothesis 3: Wrapper identity mismatch**
- Compare wrapper objects used in path lookups
- Check if `DynamicWrapperUtils.unwrapToStatic()` causing issues
- Expected: Wrappers should match consistently throughout train journey

**Hypothesis 4: Path length calculation wrong**
- Log `path.length()` and `front.getPosition()` at each step
- Expected: `path.length() - front.getPosition()` should be > 0 until semaphore reached

## Code Locations Reference

### Critical Code Sections

**Train.kt:**
```kotlin
// Line 539-555: Motor.derivatives() - acceleration calculation
override fun derivatives() {
    val s: Double = distanceToSemaphore()
    if (s <= 0) {  // ← BUG TRIGGER: This stops acceleration!
        accelerate = false
        return
    }
    // ... calculate acceleration based on distance
}

// Line 591-592: distanceToSemaphore() method
override fun distanceToSemaphore(): Double =
    if (pathToSemaphore == null) 0.0  // ← Returns 0 if null
    else pathToSemaphore!!.length() - front.getPosition()  // ← Returns 0 if consumed

// Line 200: Path retrieval in semaphoreAction
val path: Path? = env.pathToNextSemaphore(separator, next!!)

// Line 303: Path assignment in accelerateToSignal
pathToSemaphore = path

// Line 341-344: Path consumption in separatorAction (Front)
@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
pathToSemaphore?.removeFirst()
@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
pathToSemaphore?.removeFirst()  // ← Why twice???
```

**Changes from 7c8cc3f (Fix #275):**
```diff
- var where: PathSeparator = env.getInOuts().first { it.staticRef === staticInOut }
+ var where: PathSeparator = env.getInOuts().first { DynamicWrapperUtils.unwrapToStatic(it) === staticInOut }

- } else if (where is DynamicInOut && where == timetable.getIn() && next != null) {
+ } else if (where is DynamicInOut && where.staticRef == timetable.getIn() && next != null) {

- val isExitInOut = where is DynamicInOut && where == timetable.getOut() && next == null
+ val isExitInOut = where is DynamicInOut && where.staticRef == timetable.getOut() && next == null
```

## Sub-Issues to Create

### Sub-Issue 280.1: Add Debug Logging for Path State Tracking
**Assignee:** kotlin-junior-dev (supervised by traffic-simulation-expert)
**Files:** `sim/Train.kt`
**Objective:** Add comprehensive logging to track `pathToSemaphore` state throughout train lifecycle.

### Sub-Issue 280.2: Compare Path Management Between Working and Broken Versions
**Assignee:** java-senior-dev + traffic-simulation-expert
**Files:** `sim/Train.kt`, `context/DefaultSimulationContext.kt`
**Objective:** Use git worktree to identify exact code differences causing regression.

### Sub-Issue 280.3: Investigate Path Consumption Logic (removeFirst twice)
**Assignee:** traffic-simulation-expert
**Files:** `sim/Train.kt` (line 341-344)
**Objective:** Understand why `removeFirst()` is called twice and if this is correct.

### Sub-Issue 280.4: Verify pathToNextSemaphore and Wrapper Caching
**Assignee:** kotlin-tech-lead + traffic-simulation-expert
**Files:** `context/DefaultSimulationContext.kt`, `sim/ShuntingLoop.kt`
**Objective:** Verify path retrieval logic works correctly with wrapper changes from #275.

### Sub-Issue 280.5: Create Regression Test for Train Acceleration
**Assignee:** kotlin-junior-dev (supervised by traffic-simulation-expert)
**Files:** `src/test/kotlin/cz/vutbr/fit/interlockSim/sim/TrainAccelerationRegressionTest.kt`
**Objective:** Prevent future regressions with comprehensive test.

### Sub-Issue 280.6: Fix Identified Root Cause
**Assignee:** traffic-simulation-expert
**Files:** TBD (depends on analysis)
**Objective:** Implement fix based on root cause analysis.

## Success Criteria

1. ✅ Root cause identified and documented
2. ✅ Fix implemented and reviewed by traffic-simulation-expert
3. ✅ All existing tests pass (`./gradlew test`)
4. ✅ New regression test added and passing
5. ✅ Golden output comparison shows identical behavior to working version (18108fa)
6. ✅ ShuntingLoop smoke test runs without deadlock
7. ✅ Both Train #1 and Train #2 exit system successfully

## Constraints (Critical!)

**From CLAUDE.md - Code Modification Guidelines:**
- ⚠️ **sim/ package** - Minimal logic changes only; Koin DI allowed since 2026-03-20 (kDisco Phase 1 complete)
- ✅ **Tests are mandatory** - Any modified code MUST be covered by tests
- ✅ **No breaking changes** - Maintain backward compatibility with existing XML configurations
- ✅ **Conservative approach** - Only make explicitly required changes
- ✅ **Golden output validation** - Compare against working baseline (18108fa)

**Decision Authority:**
- **traffic-simulation-expert** has final say on sim/ package changes
- **kotlin-tech-lead** reviews code architecture and patterns
- **java-senior-dev** provides historical context and regression analysis

## Next Steps

1. **Setup git worktrees** for parallel comparison
2. **Add debug logging** (Sub-issue 280.1)
3. **Run comparison tests** (working vs broken versions)
4. **Analyze logs** to identify root cause
5. **Create GitHub sub-issues** with findings
6. **Implement fix** (supervised by traffic-simulation-expert)
7. **Validate against golden output**

## References

- **Issue #280:** https://github.com/bedaHovorka/interlockSim/issues/280
- **Issue #275:** https://github.com/bedaHovorka/interlockSim/issues/275 (previous deadlock)
- **Fix Commit:** 7c8cc3f (introduced new issue)
- **Working Version:** 18108fa (baseline for comparison)
- **Working Logs:** https://github.com/bedaHovorka/interlockSim/actions/runs/21163388994/job/60862433123
- **TEAM.md:** Agent roles and decision authority
- **CLAUDE.md:** Code modification guidelines and constraints

---

**Status:** PLANNING COMPLETE - AWAITING APPROVAL FROM traffic-simulation-expert
**Next Action:** Create GitHub sub-issues and begin Phase 1 (git worktree setup)
