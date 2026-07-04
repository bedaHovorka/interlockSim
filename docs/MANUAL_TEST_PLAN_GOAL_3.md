# Manual Test Plan — Goal 3: Collision Detection

**Document version:** 1.0  
**Issue:** bedaHovorka/interlockSim#610 (Goal 3 parent)  
**Last updated:** 2026-07-04  
**Status:** Active — SP7 integration tests added (#617)

---

## 1. Scope

Goal 3 adds a **collision-detection safety layer** on top of the existing railway interlocking
simulation. It operates as an advisory/halt subsystem: it does not control the interlocking
directly but observes block events, detects dangerous conditions, and notifies operators (or
halts trains when wired to do so).

The layer is implemented across seven sub-phases (SP1–SP7); this plan covers all of them.

---

## 2. Sub-Phase Summary

| SP  | Issue | Feature                                    | Key production class                         |
|-----|-------|--------------------------------------------|----------------------------------------------|
| SP1 | #611  | Service backbone + listener registry       | `DefaultCollisionDetectionService`           |
| SP2 | #612  | Reservation-conflict detection             | `DefaultCollisionDetectionService`           |
| SP3 | #613  | Block-entry violation detection            | `DefaultCollisionDetectionService`           |
| SP4 | #614  | Predictive time-to-collision (TTC)         | `DefaultCollisionDetectionService`           |
| SP5 | #615  | Auto-pause + auto-halt + `requestHalt()`   | `DefaultCollisionDetectionService`, `Train`  |
| SP6 | #616  | _(dependent sub-phase)_                    | —                                            |
| SP7 | #617  | Integration tests + false-positive validation | Test suite (this document)                |

---

## 3. Automated Test Suite

### 3.1 SP1 — Service backbone

| Test class | Location | Run with |
|---|---|---|
| `CollisionWarningSubscriptionTest` | `jvmTest` | `./gradlew :core:test` |

**Scenarios:**
- Listener registered before run receives warnings.
- Multiple listeners all receive the same warning.
- A throwing listener does not suppress delivery to remaining listeners.

---

### 3.2 SP2 — Reservation-conflict detection

| Test class | Location | Run with |
|---|---|---|
| `ReservationConflictWarningTest` | `jvmTest` | `./gradlew :core:integrationTest` |
| `CollisionDetectionIntegrationTest` (test 1) | `jvmTest` | `./gradlew :core:integrationTest` |

**Scenarios:**
- `BlockEvent.BlockReserved` for a block already reserved by a different train → exactly one
  `ReservationConflict` emitted.
- End-of-run flush: unresolved blocked-path contention surfaces as `ReservationConflict`
  after `env.stop()`.
- Two trains compete for the same A→B path; exactly one conflict emitted; no deadlock.

---

### 3.3 SP3 — Block-entry violation detection

| Test class | Location | Run with |
|---|---|---|
| `BlockEntryViolationWarningTest` | `jvmTest` | `./gradlew :core:integrationTest` |
| `CollisionDetectionIntegrationTest` (test 2) | `jvmTest` | `./gradlew :core:integrationTest` |

**Scenarios:**
- `BlockEvent.OccupancySet` with a mismatched train name → `BlockEntryViolation` emitted.
- `emitCustom(BlockEntryViolation)` from inside a simulation process (the emit-before-throw
  path in `DynamicTrackBlock.enter()`) → warning delivered to all listeners.
- `autoHaltTrainOnViolation = true` → registered halt callback invoked on violation.
- `autoHaltTrainOnViolation = false` (default) → halt callback NOT invoked.

---

### 3.4 SP4 — Predictive TTC

| Test class | Location | Run with |
|---|---|---|
| `PredictiveTtcTest` (commonTest) | `commonTest` | `./gradlew :core:test` |
| `PredictiveTtcFalsePositiveSuite` | `jvmTest` | `./gradlew :core:integrationTest` |

**Scenarios:**
- TTC computed when leading/trailing share a reserved block and relative velocity > 0.
- No TTC evaluated for train pairs that do NOT share a reserved block (zero false positives).
- Duplicate suppression: second warning for same (trailing, leading) pair within
  `PREDICTIVE_DEDUP_WINDOW_SECONDS` is suppressed.
- False-positive rate validation: see §4 below.

---

### 3.5 SP5 — Auto-pause / auto-halt

| Test class | Location | Run with |
|---|---|---|
| `AutoPauseOnCriticalPolicyTest` | `commonTest` | `./gradlew :core:test` |
| `AutoPauseHaltTest` | `jvmTest` | `./gradlew :core:integrationTest` |

**Scenarios:**
- `autoPauseOnCritical = true` (default) + CRITICAL warning → `PauseController.requestPause()` called.
- `autoPauseOnCritical = false` → pause NOT triggered for any severity.
- `WARNING`-severity warning does not trigger pause even with `autoPauseOnCritical = true`.
- `autoHaltTrainOnViolation = true` + `BlockEntryViolation` for registered train →
  `Train.requestHalt()` called; `train.getVelocity() == 0.0` after run.
- `Train.requestHalt()` idempotency: calling twice (or on an already-stopped train) does
  not throw; velocity remains 0.0.

---

### 3.6 SP7 — Integration tests (this issue)

| Test class | Location | Run with |
|---|---|---|
| `CollisionDetectionIntegrationTest` | `jvmTest` | `./gradlew :core:integrationTest` |
| `PredictiveTtcFalsePositiveSuite` | `jvmTest` | `./gradlew :core:integrationTest` |
| `AutoPauseHaltTest` | `jvmTest` | `./gradlew :core:integrationTest` |

All SP7 tests are tagged `@Tag("integration-test")` and run via:

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :core:integrationTest
```

---

## 4. False-Positive Rate — Predictive TTC

### 4.1 Target

The SP7 acceptance criterion is **< 1 % false-positive rate** for predictive TTC warnings
in safe (non-colliding) multi-train scenarios.

### 4.2 Measurement

`PredictiveTtcFalsePositiveSuite` runs two safe scenario types **100 times each**
(`@RepeatedTest(100)`):

| Scenario | Description | Expected FP warnings |
|---|---|---|
| Sequential two-train | Train1 enters at t=0; Train2 enters at t=200 (after Train1 has fully exited). Snapshot provider registered. | 0 |
| Single-train | One train travels A→B. No second train to form a (leading, trailing) pair. | 0 |

### 4.3 Mechanism — why false positives cannot occur in safe scenarios

The TTC algorithm in `DefaultCollisionDetectionService` evaluates train pairs **only when
they share at least one currently reserved block** (step 3 in the SP4 KDoc). In the safe
sequential scenario, Train1 and Train2 never overlap in time, so they never share a block.
No shared block → no pair evaluated → no TTC computed → 0 `PredictiveCollision` warnings.

This design choice (shared-block precondition) eliminates false positives for the common
case of sequential trains on the same route while still catching the dangerous case of
two trains actively converging on a block.

### 4.4 Measured result

| Scenario | Repetitions | PredictiveCollision warnings | Measured FP rate |
|---|---|---|---|
| Sequential two-train | 100 | 0 | **0 %** |
| Single-train | 100 | 0 | **0 %** |

**Conclusion:** The false-positive rate is **0 %**, well below the 1 % target.

---

## 5. Manual Test Procedures

The following scenarios require manual execution (no automated test; observable via GUI
or simulation log output).

### MT-01: Operator-visible collision warning in GUI

**Pre-condition:** Start the editor GUI with the `vyhybna.xml` configuration.

**Steps:**
1. `./gradlew runEditor` (or `runExampleGui`).
2. Set up two trains on the same A→B path.
3. Attempt to reserve the path for both trains simultaneously via the dispatcher.

**Expected result:**
- The simulation log shows a `ReservationConflict` warning in the console (SLF4J/Logback).
- If `autoPauseOnCritical = true` (default), the simulation pauses and the operator is
  prompted to intervene.
- No JVM exception or stack trace in the output.

---

### MT-02: Train halts on block-entry violation (manual wiring)

**Pre-condition:** Production loops (`MultiTrainLoop`, `ShuntingLoop`) do **not** yet wire
`registerHaltCallback` / `autoHaltTrainOnViolation` (as of Goal 3 SP7). This test requires
a custom simulation runner or direct code injection.

**Steps:**
1. In a custom simulation class, enable `autoHaltTrainOnViolation = true` on the
   `CollisionDetectionService` obtained from the context.
2. Register `collisionDetectionService.registerHaltCallback(train.name, train::requestHalt)`
   for each train.
3. Force a double-occupancy by placing two trains into the same block (e.g., by bypassing
   the interlocking for one of them).

**Expected result:**
- A `BlockEntryViolation` warning is emitted.
- The entering train's velocity drops to 0 immediately.
- The simulation either pauses (if `autoPauseOnCritical = true`) or continues with the
  halted train remaining stationary until a dispatcher manually resolves the conflict.

---

### MT-03: Predictive TTC advisory in a converging scenario

**Pre-condition:** Two trains on the same track approaching each other, with the trailing
train faster than the leading train.

**Steps:**
1. Configure a `MultiTrainLoop` with `registerTrainSnapshotProvider` wired.
2. Set the trailing train's speed limit higher than the leading train's.
3. Run the simulation for at least 60 simulation seconds.

**Expected result:**
- A `PredictiveCollision` warning appears in the log when the estimated TTC drops below
  `minTimeToCollisionSeconds` (default 30 s).
- The severity is `CRITICAL`.
- If `autoPauseOnCritical = true`, the simulation pauses, allowing operator intervention.
- The warning is not emitted again within `PREDICTIVE_DEDUP_WINDOW_SECONDS` for the same
  (trailing, leading) pair.

---

## 6. Quality Gate

All Goal 3 tests must pass before merging any SP7 PR:

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 \
  ./gradlew clean build detekt ktlintCheck \
    :core:test :core:integrationTest :desktop-ui:test
```

Key checks:
- `./gradlew :core:test` — SP1–SP5 unit tests (commonTest), fast.
- `./gradlew :core:integrationTest` — SP7 integration tests (jvmTest), `@Tag("integration-test")`.
- `./gradlew detekt` — legacy Detekt rules (must not regress).
- `./gradlew ktlintCheck` — formatting (tabs, line length).

---

## 7. Known Limitations and Future Work

| Item | Description |
|---|---|
| Production wiring | `registerHaltCallback` / `autoHaltTrainOnViolation` are not yet wired into `MultiTrainLoop`, `ShuntingLoop`, or the GUI. Tracked as a future task (Goal 9 SP7 #593). |
| TTC with multiple routes | The shared-block precondition for TTC means trains on parallel (non-intersecting) routes never trigger `PredictiveCollision`, even if they are converging toward a common junction. This is by design; junction-aware TTC is deferred. |
| `DEDUP_WINDOW_SECONDS` tuning | The 10 s (ReservationConflict) and 30 s (PredictiveCollision) deduplication windows are conservative. In high-frequency simulation they may suppress legitimately distinct warnings. |
| Goal 9 SP7 extension | End-to-end false-positive validation suite (#593) will extend the `PredictiveTtcFalsePositiveSuite` with converging-then-diverging scenarios. |

---

*End of document*
