# Design: Block-Occupancy Events (#569) and Two-Train Concurrency Validation (#587)

**Date:** 2026-06-28
**Branch:** goal-1
**Issues:** [#569](https://github.com/bedaHovorka/interlockSim/issues/569), [#587](https://github.com/bedaHovorka/interlockSim/issues/587)
**Worktrees:**
- #569 → `~/.claude/worktrees/issue-569` (branch `feat/issue-569-expose-block-occupancy-events`)
- #587 → `~/.claude/worktrees/agent-a5a0792b0b9cc28e6` (branch `feat/issue-587-two-train-concurrency-validation`)
- kdisco → `/home/beda/work/kdisco/.worktrees/issue-24-observable-events` (branch `issue-24-observable-events`)

---

## Context

Issue #569 is a Goal 10 prerequisite: the deliberative AI dispatcher (SP2b) must subscribe to
block-release and occupancy-change events to re-plan when a block frees up. Currently the
`PathReservationRegistry` only exposes kDisco `Condition`-based wake-ups for *train processes*;
there is no push-based event channel for external (non-train) observers.

Issue #587 validates the foundational multi-train behaviour introduced in Goal 1: two trains must
complete their routes without deadlock, event-ordering errors, or non-determinism across 100
consecutive runs.

**Parallelism constraint:** Agent A owns all kdisco changes (#569). Agent B makes zero kdisco
changes (#587). When both PRs are ready, the kdisco worktree is published (or snapshotted to
`mavenLocal`) before both interlockSim PRs merge to `goal-1`.

---

## Section 1 — kdisco improvements (issue-24 worktree)

### 1a. Multi-listener fan-out

**File:** `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt`

Replace the single-slot listener field:
```kotlin
// before
var eventListener: ((SimulationEvent) -> Unit)? = null

// after
val eventListeners: MutableList<(SimulationEvent) -> Unit> = mutableListOf()
```

`Simulation.onEvent(listener)` appends to the list (additive, not replace).
`Simulation.emit(event)` iterates the list; the zero-overhead guard becomes
`if (eventListeners.isEmpty()) return`.

This removes the "only one listener at a time" constraint while remaining backward-compatible for
callers that register exactly one listener.

### 1b. Top-level `emitCustom(payload)` in `Dsl.kt`

```kotlin
fun emitCustom(payload: Any?) {
    val ctx = Process.activeContext ?: return
    ctx.eventListeners.forEach { it(SimulationEvent.Custom(ctx.currentTime, payload)) }
}
```

Uses the existing `Process.activeContext` static (same pattern `Variable` already uses for
`Process.activeContext!!.currentTime`). Any code running on the simulation thread — including
interlockSim service methods — can call this without holding a `Process` reference.

### New kdisco tests

- `onEvent called twice — both listeners receive every event`
- `emitCustom invoked outside Process subclass delivers Custom event`
- Existing `ObservableEventsTest` must stay green.

---

## Section 2 — `BlockEvent` sealed class and emission points (#569)

**New file:**
`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt`

```kotlin
sealed class BlockEvent {
    abstract val block: DynamicTrackBlock
    abstract val time: Double

    data class BlockReserved(
        override val block: DynamicTrackBlock,
        val trainId: String,
        override val time: Double,
    ) : BlockEvent()

    data class BlockReleased(
        override val block: DynamicTrackBlock,
        val trainId: String,
        override val time: Double,
    ) : BlockEvent()

    data class OccupancySet(
        override val block: DynamicTrackBlock,
        val occupant: TrackOccupant,
        override val time: Double,
    ) : BlockEvent()

    data class OccupancyCleared(
        override val block: DynamicTrackBlock,
        override val time: Double,
    ) : BlockEvent()
}
```

### Emission points

All emit via the top-level `emitCustom(BlockEvent(...))`. `time` is
`Process.activeContext!!.currentTime`, available on the simulation thread where all mutations occur.

| Location | Event |
|---|---|
| `DefaultPathReservationService.reservePathToAnyNextSemaphore` — after atomic reserve succeeds | `BlockReserved` per block |
| `DefaultPathReservationService.releasePath` — after each block removed from registry | `BlockReleased` per block |
| `DefaultPathReservationService.unregister` — bulk release on train completion | `BlockReleased` per block |
| `DynamicTrackBlock.setOccupant(occupant)` | `OccupancySet` |
| `DynamicTrackBlock.clearOccupant()` | `OccupancyCleared` |

Occupancy events are emitted from `DynamicTrackBlock` itself, co-located with the state mutation.

---

## Section 3 — Subscription API on `SimulationEnvironment` (#569)

### New methods on the `SimulationEnvironment` interface

```kotlin
/**
 * Subscribe to block-level domain events (reserve / release / occupancy changes).
 * Called synchronously on the simulation thread.
 * Listeners registered after [run] has started are silently ignored (context is frozen).
 */
fun onBlockEvent(listener: (BlockEvent) -> Unit)

/**
 * Subscribe to raw kdisco simulation events (process lifecycle, resource changes, custom).
 * Called synchronously on the simulation thread.
 * Listeners registered after [run] has started are silently ignored (context is frozen).
 */
fun onSimulationEvent(listener: (cz.hovorka.kdisco.SimulationEvent) -> Unit)
```

### Wiring in `DefaultSimulationContext`

Two `MutableList`s stored as instance fields:

```kotlin
private val pendingSimEventListeners: MutableList<(kdisco.SimulationEvent) -> Unit> = mutableListOf()
private val pendingBlockEventListeners: MutableList<(BlockEvent) -> Unit> = mutableListOf()
```

`onSimulationEvent(l)` and `onBlockEvent(l)` append to these lists.

In `run()`, immediately after `simulation = sim`:

```kotlin
// wire raw kdisco listeners
pendingSimEventListeners.forEach { sim.onEvent(it) }

// wire block event listeners as a single filtered kdisco listener
if (pendingBlockEventListeners.isNotEmpty()) {
    sim.onEvent { event ->
        if (event is kdisco.SimulationEvent.Custom && event.payload is BlockEvent) {
            pendingBlockEventListeners.forEach { it(event.payload as BlockEvent) }
        }
    }
}
```

`onBlockEvent` is a filtered view of the kdisco bus — no second event channel; one unified path.
No Koin DI module changes required.

---

## Section 4 — `TwoTrainConcurrencyTest` (#587)

**New file:**
`core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt`

Tags: `@Tag("integration-test")`. Extends `KoinTestBase`.
Per-test timeout: `@Timeout(30, TimeUnit.SECONDS)`.

### Test 1 — `both trains complete without deadlock`

`@RepeatedTest(100)`

Topology: `linearPathWithSemaphoreSimulation(semaphoreAllowing = false)`.
Two `MultiTrainLoop.TrainSpec`s: `A→B inTime=0.0` and `A→B inTime=1.0`.

Assertions per run:
- `getTrainsEntered() == 2`
- `getTrainsExited() == 2`
- `getOccupiedResourceCount() == 0`

### Test 2 — `block transitions follow reserved → occupied → released order`

`@RepeatedTest(100)`

Same topology. A `PropertyChangeListener` is registered on the context before `run()` to
collect `TRAIN_EVENTS` report strings. After `run()`, parse the log and assert for each block
ID that no `released` token appears before `reserved`, and no `occupied` before `reserved`.

Uses the existing `report()` / `ContextChangeEvent` infrastructure — **no dependency on
`onBlockEvent` from #569**. The two issues can be implemented in parallel.

### Test 3 — `same-step arrival does not cause ordering exception`

`@RepeatedTest(100)`

Same topology. Both trains: `inTime = 0.0` (simultaneous arrival edge case — exercises
scheduler tie-breaking). Same completion assertions as Test 1.

---

## Parallelism and Merge Order

1. **Agent A** implements kdisco multi-listener + `emitCustom` in the kdisco worktree, then
   implements `BlockEvent`, emission points, and `SimulationEnvironment` API in the #569
   worktree. Publishes kdisco snapshot to `mavenLocal` for local testing.
2. **Agent B** implements `TwoTrainConcurrencyTest` in the #587 worktree. No kdisco changes.
   Both agents run independently; neither blocks the other.
3. Merge order: kdisco release/snapshot → #569 PR → #587 PR (or both interlockSim PRs
   simultaneously if kdisco is already published).
