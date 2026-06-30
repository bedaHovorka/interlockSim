# Design Spec: #618 Train-Completion Reservation Cleanup Fix

**Date:** 2026-06-30  
**Issue:** [#618 — Fix train-completion cleanup: unlock switches in `unregister` (I1) + emit legacy `BlockOccupancyEvent` on release paths (I2)](https://github.com/bedaHovorka/interlockSim/issues/618)  
**Branch:** `feat/issue-618-train-completion-cleanup`  
**PR target:** `develop`

---

## 1. Summary

GitHub issue #618 surfaced two correctness gaps in the train-completion reservation cleanup path of `DefaultPathReservationService`:

1. **I1 (bug):** Switches locked during `reservePath` are never unlocked when a train completes its journey via the production `unregister` path.
2. **I2 (consistency):** The legacy `BlockOccupancyEvent` / `addBlockOccupancyListener` channel is emitted from the test-only `releasePath` but **not** from the production release paths `unregister` / `unregisterBlock`, so legacy subscribers miss `BLOCK_RELEASED` events.

This spec records the design for a separate branch and PR that fixes both issues.

---

## 2. Current State

### 2.1 Relevant source locations

- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt`
  - `reservePath` — registers and locks switches via `registry.registerSwitches(trainId, switches)` (line ~217).
  - `releasePath` — test-only release path that unlocks switches and emits both new `BlockEvent.BlockReleased` and legacy `BlockOccupancyEvent(BLOCK_RELEASED)` (lines ~353–410).
  - `unregister` — production train-completion path. Calls only `registry.unregister(trainId)` and emits `BlockEvent.BlockReleased`. Does **not** unlock switches or emit legacy events (lines ~1615–1626).
  - `unregisterBlock` — production per-block release path called by `Train.Tail`. Calls `registry.unregisterBlock(trainId, block)` and emits `BlockEvent.BlockReleased`. Does **not** emit legacy events (lines ~1638–1647).
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationRegistry.kt`
  - `registerSwitches` — locks switches (line ~589).
  - `unregisterSwitches` — unlocks switches and removes mappings (lines ~618–638).
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt`
  - `Train.kt:968` → `env.releaseTrainReservations(name)` → `DefaultSimulationContext.releaseTrainReservations` → `pathService.unregister(trainId)`.
  - `Train.kt:575` → `env.unregisterBlock` → `pathService.unregisterBlock`.
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt`
  - New kDisco-bus event channel.
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/BlockOccupancyEvent.kt`
  - Legacy listener-based event channel.

### 2.2 Why `releasePath` is intentionally left alone

`releasePath` is called only from tests. It does extra work (`cancelPathSetup` per block) that is **not** appropriate for the production train-completion path because the tail-leaves-block flow has already torn down block state. Routing production cleanup through `releasePath` risks double-fire and spurious `PathSeparatorChangeException`. Therefore the fix adds the missing behavior to `unregister`/`unregisterBlock`, rather than redirecting callers to `releasePath`.

---

## 3. Design

### 3.1 I1 — Unlock switches in `unregister`

Make `unregister` symmetric with `releasePath` for switch cleanup only.

```kotlin
override fun unregister(trainId: String): List<DynamicTrackBlock> {
    // Unlock switches before registry cleanup, matching releasePath behavior.
    val switches = registry.getSwitches(trainId)
    switches.forEach { switch ->
        try {
            switch.unlock()
            logger.debug { "unregister: Unlocked switch ${switch.hashCode()} for $trainId" }
        } catch (e: Exception) {
            logger.warn(e) { "unregister: Failed to unlock switch $switch" }
        }
    }

    val releasedBlocks = registry.unregister(trainId)
    registry.unregisterSwitches(trainId) // idempotent unlock + mapping removal

    logger.info {
        "unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
            releasedBlocks.joinToString(", ") { it.toString() }
    }

    val simTime = currentSimulationTime()
    releasedBlocks.forEach { block ->
        emitBlockReleased(block, trainId, simTime)
    }
    return releasedBlocks
}
```

**Design decisions:**
- `switch.unlock()` is called before `registry.unregister` and `registry.unregisterSwitches`. `unregisterSwitches` is idempotent, so a second unlock is safe (same pattern as `releasePath`).
- No `cancelPathSetup` is added here; that remains exclusive to `releasePath` and the tail-leave path.
- `DynamicRailSwitch.unlock()` is itself idempotent and only fires property-change listeners; no kDisco `Condition` wakeup is involved.
- `BlockEvent.BlockReleased` is emitted **after** registry cleanup so `isBlockAvailable()` already returns `true` for subscribers.

### 3.2 I2 — Emit legacy `BlockOccupancyEvent` from production release paths

To keep the new and legacy channels consistent across all three release paths (`releasePath`, `unregister`, `unregisterBlock`), extract a single helper that emits both channels.

```kotlin
/**
 * Emit both the new kdisco-bus BlockEvent.BlockReleased and the legacy
 * BlockOccupancyEvent(BLOCK_RELEASED) for a single block.
 *
 * This keeps the two event channels consistent on every production and test
 * release path (releasePath, unregister, unregisterBlock).
 */
private fun emitBlockReleased(
    block: DynamicTrackBlock,
    trainId: String,
    simTime: Double
) {
    emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
    registry.emit(
        BlockOccupancyEvent(
            block = block,
            type = BlockOccupancyEventType.BLOCK_RELEASED,
            trainId = trainId,
            occupant = null,
            previousState = TrackFacility.State.RESERVED,
            newState = TrackFacility.State.FREE,
            simulationTime = simTime
        )
    )
}
```

Then replace the inline dual-channel emission in `releasePath` and the single-channel emission in `unregister` / `unregisterBlock` with calls to this helper.

**`unregisterBlock` change:**

```kotlin
override fun unregisterBlock(
    trainId: String,
    block: DynamicTrackBlock
): Boolean {
    val released = registry.unregisterBlock(trainId, block)
    if (released) {
        emitBlockReleased(block, trainId, currentSimulationTime())
    }
    return released
}
```

**Field values for legacy event:**
- `previousState = TrackFacility.State.RESERVED`
- `newState = TrackFacility.State.FREE`
- `occupant = null`
- `type = BlockOccupancyEventType.BLOCK_RELEASED`
- `simulationTime = currentSimulationTime()`

These mirror `releasePath` exactly.

### 3.3 Why a helper (decision B)

The issue text originally showed inline dual-channel emission. We choose a single private helper because:
- All three release paths stay consistent by construction.
- Any future release path naturally gets both channels.
- The helper is small, private, and easy to remove later when the legacy channel is deprecated.
- No public API changes.

---

## 4. Tests

### 4.1 New / extended tests

1. **Switch unlock after `unregister` (unit)**  
   Extend `PathReservationServiceTest` (inside the existing switch/rollback nests). Reserve a path through `vA`/`vB` in `vyhybna.xml`, assert switches are locked and mapped, call `service.unregister(trainId)` (not `releasePath`), then assert every captured switch is unlocked, `registry.getSwitches(trainId)` is empty, and `registry.getReservedBlocks(trainId)` is empty.

2. **Switch unlock after `releaseTrainReservations` (integration)**  
   Same scenario, but call `simulationContext.releaseTrainReservations(trainId)` — the real production entry point used by `Train.kt:968`.

3. **Second train reconfigures a switch after first train completes (integration)**  
   Two-train scenario on `vyhybna.xml`: train1 reserves through `vA`, completes via the real flow, train2 reserves a path requiring `vA` in the opposite configuration; assert train2's `reservePath` succeeds and `vA.conf` reflects train2's path. If `ThreeTrainLoop` already covers this end-to-end, add explicit `vA.locked == false` assertions after completion to avoid duplication.

4. **Legacy subscriber receives `BLOCK_RELEASED` on `unregister` path**  
   Register an `addBlockOccupancyListener`, reserve a path, call `releaseTrainReservations(trainId)` or `service.unregister(trainId)`, assert the listener receives exactly one `BLOCK_RELEASED` per reserved block with `newState = FREE` and matching `trainId`.

5. **Legacy subscriber receives `BLOCK_RELEASED` on `unregisterBlock` path**  
   Reserve a path, then call `service.unregisterBlock(trainId, block)`, assert the listener receives a `BLOCK_RELEASED` for that block.

### 4.2 Existing tests to keep green

- `SwitchConfigurationTest`
- `SignalConfigurationRollbackTest`
- `DeadlockDetectionTest`
- Full `ShuntingLoop` suite
- `PathReservationServiceTest` (now integration-tagged)
- `PathReservationRegistryTest` (now integration-tagged)

### 4.3 Test-debt note (do not fix here)

`PathReservationServiceTest.kt:2007` exercises `service.releasePath` (test-only) for the legacy listener. After this fix the production paths are covered by tests #4–#5. A separate future issue may retire the legacy channel and migrate that test to `onBlockEvent`.

---

## 5. Error Handling and Backward Compatibility

- `switch.unlock()` and `registry.unregisterSwitches` are idempotent. Double-unlock is harmless.
- Existing `addBlockOccupancyListener` consumers now receive the `BLOCK_RELEASED` events they were missing. This is a bug fix, not a breaking change.
- `unregister` return type and semantics are unchanged; only internal cleanup is added.
- `ShuntingLoop` will now correctly unlock `vA`/`vB` at journey end. No existing test asserts that switches stay locked after completion.
- No public API changes.

---

## 6. Build / Quality Gates

The change must pass:

```bash
./gradlew clean build detekt ktlintCheck test integrationTest
```

CI resolution of the kDisco SNAPSHOT is tracked separately; no local `~/.m2` kDisco 0.6.0-SNAPSHOT is required for these changes.

---

## 7. Branch and PR Plan

1. Create branch `feat/issue-618-train-completion-cleanup` from `develop`.
2. Implement the `DefaultPathReservationService` changes and tests.
3. Run the full quality gate.
4. Open a PR to `develop` with a description following `.github/PULL_REQUEST_TEMPLATE.md`.
5. Do **not** auto-merge; wait for external review (per project memory).

---

## 8. Acceptance Criteria

- [ ] `DefaultPathReservationService.unregister` unlocks switches and calls `registry.unregisterSwitches(trainId)`.
- [ ] `DefaultPathReservationService.unregisterBlock` emits legacy `BlockOccupancyEvent(BLOCK_RELEASED)` when a block is released.
- [ ] `DefaultPathReservationService.unregister` emits legacy `BlockOccupancyEvent(BLOCK_RELEASED)` for each released block.
- [ ] All three release paths use the same dual-channel emission helper.
- [ ] Tests #1–#5 added and green.
- [ ] `./gradlew clean build detekt ktlintCheck test integrationTest` green.
- [ ] Regression suites (`SwitchConfigurationTest`, `SignalConfigurationRollbackTest`, `DeadlockDetectionTest`, `ShuntingLoop` suite) green.
