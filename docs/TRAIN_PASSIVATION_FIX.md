# Train Passivation Fix - Complete Stop When Path Unavailable

## Issue Summary

**Problem:** Train #3 exhibited abnormal behavior when dispatcher could not reserve a forward path:
- Near-zero negative acceleration (-3.6E-9 m/s²)
- Near-zero velocity (8.48E-4 m/s)
- Continuous creeping motion at ~100m from semaphore
- Never reached semaphore before simulation end (t=300s)

**Root Cause:** When `trainNavService.findReservedPathForTrain()` returned null (no path reserved), the train would passivate and wait for the dispatcher. However, the motor continued running with residual velocity and acceleration, causing the train to creep slowly instead of stopping completely.

## Solution

**Location:** `src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt` lines 116-128

**Change:** Before calling `passivate()`, explicitly stop the motor and reset velocity/acceleration to zero:

```kotlin
if (path == null || next == null) {
    if (where is DynamicInOut) break
    // Train should wait for dispatcher to reserve next path
    // Stop motor completely to prevent creeping motion during passivation
    motor.cancelAccelerating()
    train.stop()
    logger.debug {
        "Train $number: No reserved path available at $where, halting completely and waiting for dispatcher"
    }
    // Do NOT stop the entire simulation!
    passivate()
    continue // Restart loop after passivation to re-check for next track section
}
```

## Physics Analysis

The acceleration/deceleration calculations in `Motor.derivatives()` are **correct**. The formula:

```
a = ((targetSpeed - velocity) * (targetSpeed + velocity)) / (2 * s)
```

Where:
- `targetSpeed` = 0.0 (stopping at semaphore with STOP signal)
- `velocity` = residual velocity (e.g., 0.0008 m/s)
- `s` = distance to semaphore (could be stale/incorrect when path is null)

When `s` is incorrect (stale path data) or very large (~100m), the formula produces tiny acceleration values like -3.6E-9 m/s², causing the train to creep indefinitely.

**The fix prevents this by:**
1. Stopping the motor explicitly before passivation
2. Setting velocity and acceleration to exactly 0.0
3. Preventing numerical drift during passivation

## Verification Results

### Test Coverage
- ✅ All 1658 tests pass (0 failures, 4 skipped)
- ✅ Added unit test: `distanceToSemaphore_withNullPath_returnsZero()`
- ✅ All ShuntingLoop tests pass (19 tests)

### Simulation Output

**Before Fix (feature/issue/291):**
```
146.0 Train #3 -3.6E-9 8.48E-4 <position> ... ~100.0
```
- Tiny negative acceleration
- Non-zero velocity
- Continuous creeping motion

**After Fix (current):**
```
286.0-301.0 Train #3 0.0 0.0 304.9999990000287 ... 100.00000099997129
```
- **Acceleration: 0.0 m/s²** (clean stop)
- **Velocity: 0.0 m/s** (no creeping)
- **Position: 304.999... m** (stopped ~100m from semaphore)
- Dispatcher logs: "All paths blocked from doA2 for Train #3"

### Expected Behavior

When the dispatcher cannot reserve a path forward:
1. ✅ Train decelerates to a **complete stop** (v=0.0, a=0.0)
2. ✅ Train **passivates** (yields to other processes)
3. ✅ Train **waits** for dispatcher to reserve path
4. ✅ When path becomes available, train resumes movement
5. ✅ Simulation continues normally (other trains proceed)

## Design Rationale

### Option Selected: **Option D - Halt train before passivation**

**Why this approach?**
- ✅ Clean semantics: Train fully stops while waiting
- ✅ Prevents numerical drift and creeping motion
- ✅ Matches user requirement for complete stop
- ✅ Preserves passivate-wait coordination behavior
- ✅ Minimal code change (low risk)

### Alternatives Considered

**Option A:** Fix `distanceToSemaphore()` null handling
- ❌ May not address stale path issue
- ❌ Doesn't prevent residual velocity drift

**Option B:** Stop velocity integration when path is null
- ❌ Interferes with simulation physics state machine
- ❌ Higher risk of breaking jDisco integration

**Option C:** Refresh `pathToSemaphore` when null
- ❌ Race conditions between Motor and Site processes
- ❌ More complex state synchronization

## Impact Assessment

### What Changed
- Train now comes to **complete stop** when dispatcher cannot reserve path
- No more creeping motion with tiny accelerations
- Clean passivation behavior (train halts, waits, resumes)

### What Didn't Change
- ✅ Physics calculations remain unchanged (validated against Java baseline)
- ✅ Zero test regressions (all 1658 tests pass)
- ✅ jDisco integration compatibility maintained
- ✅ Multi-train coordination unaffected
- ✅ Normal operation (when paths available) unchanged

## Related Issues

- **Issue #291:** Train acceleration/deceleration investigation
- **Issue #292:** Path discovery restructuring (completed)
- **Issue #282:** Block ownership validation (eliminated with path reservation service)

## Further Work

This fix addresses the **immediate symptom** (creeping motion) but highlights a deeper architectural issue:

**Potential Enhancement:** Consider redesigning the path reservation workflow to ensure trains always have valid path data or fail fast with clear semantics. Current behavior relies on dispatcher polling and reactive passivation.

See `LONG_TERM_GOALS.md` for future simulation engine improvements (migration to DSOL/Kalasim).

## Testing Recommendations

When modifying Train or path reservation logic:

1. **Unit tests:** Verify `distanceToSemaphore()` behavior with null paths
2. **Integration tests:** Run ShuntingLoop with extended simulation time (t=300s+)
3. **Golden output validation:** Check Train #3 metrics at t=146s-300s
4. **Multi-train scenarios:** Verify passivation doesn't deadlock other trains
5. **Physics regression:** Confirm acceleration formula remains correct (tolerance 1e-9)

## References

- Plan document: `/home/beda/.claude/projects/.../86a58e52-ba5d-493d-88e0-437eb473b219.jsonl`
- Implementation: `src/main/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt:116-128`
- Test: `src/test/kotlin/cz/vutbr/fit/interlockSim/sim/TrainTest.kt:162-174`
- Architecture: `docs/PATH_DISCOVERY_ARCHITECTURE.md`
