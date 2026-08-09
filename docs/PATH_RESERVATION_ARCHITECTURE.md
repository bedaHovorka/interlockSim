# Path Reservation Architecture

**Document Version:** 1.1
**Last Updated:** 2026-08-09
**Related Issues:** #292 Phase 2 Enhancement; #893 (signal-clearing invariants + contiguity); #896/#899 (route-release fixes)

## Table of Contents

1. [Overview](#overview)
2. [Component Responsibilities](#component-responsibilities)
3. [Reservation Algorithm](#reservation-algorithm)
4. [Error Handling Strategy](#error-handling-strategy)
5. [Signal-Clearing Invariants (Issue #893, G1–G7)](#signal-clearing-invariants-issue-893-g1g7)
6. [Cancel / Release Paths Taxonomy](#cancel--release-paths-taxonomy)
7. [TOCTOU Trade-Off Analysis](#toctou-trade-off-analysis)
8. [Design Decisions](#design-decisions)
9. [Sequence Diagrams](#sequence-diagrams)
10. [Future Considerations](#future-considerations)

---

## 1. Overview

The path reservation system enables trains to atomically reserve sequences of track blocks before physically entering them. This prevents deadlocks and ensures safe train movement through the railway network.

### Key Components

- **PathReservationService** - Orchestration and atomic guarantees
- **PathReservationRegistry** - Ownership tracking (bidirectional mapping)
- **TopologyNavigator** - Static path finding (BFS traversal)
- **DynamicTrackBlock** - State machine and transitions

### Design Goals

1. **Atomic all-or-nothing reservation semantics** - Either all blocks reserved or none
2. **Early conflict detection** - Detect conflicts before committing to a path
3. **Clear separation of concerns** - Each component has single responsibility
4. **Single-threaded execution model** - No synchronization overhead

### High-Level Flow

```
Train requests path → Navigator finds routes → Service validates blocks
→ Service reserves blocks → Registry tracks ownership → Train enters path
```

---

## 2. Component Responsibilities

### PathReservationService

**Interface:** `cz.vutbr.fit.interlockSim.context.navigation.PathReservationService`
**Implementation:** `DefaultPathReservationService`

**Responsibilities:**
- Orchestrate path finding and reservation
- Validate block availability
- Atomically reserve blocks with rollback on failure
- Delegate ownership tracking to registry

**Key Methods:**
- `reservePath()` - Find and reserve free path
- `releasePath()` - Free all blocks for a train
- `isPathAvailable()` - Check availability (read-only)
- `getReservedBlocks()` - Query train ownership

**Design Principle:** Stateless coordinator (no internal state except dependencies)

### PathReservationRegistry

**Class:** `cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry`

**Responsibilities:**
- Track bidirectional mapping: train ↔ blocks
- Detect conflicts (block already owned by different train)
- Provide atomic registration with all-or-nothing semantics

**Key Data Structures:**
```kotlin
trainToBlocks: Map<String, MutableList<DynamicTrackBlock>>  // Train → Blocks
blockToTrain: Map<DynamicTrackBlock, String>                // Block → Train
```

**Key Methods:**
- `registerAtomic()` - Atomic registration with conflict detection (NEW)
- `unregister()` - Remove all mappings for a train
- `getOwner()` / `getBlocks()` - Query mappings

**Why Bidirectional?**
- O(1) conflict detection via `blockToTrain`
- O(1) block release via `trainToBlocks`
- Memory overhead negligible for typical simulations

### TopologyNavigator

**Interface:** `cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator`
**Implementation:** `DefaultTopologyNavigator`

**Responsibilities:**
- Find all topological paths (BFS traversal)
- Navigate static topology only (no dynamic state)
- Provide paths as sequences of TrackSections

**Key Method:**
- `findAllTopologicalPaths(start, target, maxDepth)` - BFS path finding

**Design Principle:** Pure function (no side effects, no state changes)

### DynamicTrackBlock

**Class:** `cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock`

**Responsibilities:**
- Manage block state machine (FREE → RESERVED → OCCUPIED → FREE)
- Track reservation ownership (trainId)
- Enforce state transition rules

**State Machine:**
```
FREE ----setUpPath()----> RESERVED ----enter()----> OCCUPIED
  ^                          |                          |
  |                          |                          |
  +------cancelPathSetup()---+                          |
  +------------------------leave()---------------------+
```

**Key Properties:**
- `trainId: String?` - Reservation ownership (set during RESERVED and OCCUPIED)
- `occupant: TrackOccupant?` - Physical presence (only during OCCUPIED)
- `reservedFrom: PathSeparator?` - Reservation direction

**Why String-based trainId?** See [Design Decisions](#design-decisions)

---

## 3. Reservation Algorithm

### High-Level Algorithm

```kotlin
fun reservePath(trainId: String, start: PathSeparator, target: PathSeparator): ReservationResult {
    // Step 1: Find all topological paths
    val candidatePaths = navigator.findAllTopologicalPaths(start, target, maxDepth)
    if (candidatePaths.isEmpty()) return NoPathExists

    // Step 2: Try each candidate path
    for (path in candidatePaths) {
        // 2a: Extract unique blocks
        val blocks = extractUniqueBlocks(path)

        // 2b: Validate all blocks FREE
        if (!blocks.areAllFree()) continue

        // 2c: Attempt atomic reservation (with rollback)
        val reservationResult = tryAtomicReservation(trainId, start, blocks)
        if (reservationResult != null) continue  // Failed, try next path

        // 2d: Register ownership (atomic operation)
        when (val result = registry.registerAtomic(trainId, blocks)) {
            is Success -> return Success(blocks)
            is Conflict -> {
                rollbackReservation(start, blocks)
                return Conflict(result.conflictingBlock, result.existingOwner)
            }
        }
    }

    // All paths blocked
    return AllPathsBlocked(candidatePaths.size)
}
```

### Detailed Steps

#### Step 0: Contiguity Precondition (Issue #893, task A-R1)

**Method:** `rejectNonContiguousStart(trainId, start)` (`DefaultPathReservationService.kt:422`)

**Purpose:** A route may only start where the train actually is. `[start]` must bound one of the blocks the train holds in the `PathReservationRegistry` or physically occupies; a failing request is rejected with `NonContiguousStart` (`PathReservationService.kt:170`) **before** any candidate-path discovery (`DefaultPathReservationService.kt:510-514`), so it also governs the already-owned early-return branch.

**Exemption:** A train with no footprint at all (neither registered nor occupied blocks — still outside the network) is exempt; its route legitimately starts at an entry InOut.

**Split malformation coverage:** The queued-train half of the malformation (a route requested from a mid-station Signal for a train still queued for admission) has an empty footprint and passes vacuously here. It is guarded only at the tool layer by `RequestRouteTool.queuedOriginError` (`PathReservationService.kt:151-162`), which self-disables when that tool is built with no InOut-name set or with no `DispatchLoopSensorPort`. Callers reaching `reservePath` by any other route get no protection against the queued-train form. This split is a binding traffic-simulation-expert ruling, not an oversight: tightening the vacuous arm would reject every legitimate train-entry reservation.

**Why distinct from `AllPathsBlocked`:** `AllPathsBlocked` is ordinary contention — a caller should retry next tick. `NonContiguousStart` will never succeed while the train stays where it is; the caller (or the LLM dispatcher behind it) must ask for a different origin. Collapsing the two hides a dispatcher-output defect inside a routine-traffic counter.

#### Step 1: Path Discovery (TopologyNavigator)

**Method:** `findAllTopologicalPaths()`
**Algorithm:** Breadth-First Search (BFS)

**Input:**
- Start separator (e.g., InOut entry point)
- Target separator (e.g., InOut exit point)
- Max depth limit (default: 100)

**Output:**
- List of paths (each path = list of TrackSections)
- Empty list if no topological route exists

**Ordering:**
- BFS guarantees shortest path first
- Multiple paths possible via switches/junctions

#### Step 2a: Block Extraction

**Method:** `extractUniqueBlocks(path: List<TrackSection>)`

**Purpose:** Convert TrackSections to unique DynamicTrackBlocks

**Why Deduplication?**
- Switch "around" blocks appear twice in path definition
- We only want to reserve each physical block once

**Implementation:**
```kotlin
val seen = mutableSetOf<DynamicTrackBlock>()
return path.mapNotNull { section ->
    val block = section.getTrackBlock()
    when {
        block is DynamicTrackBlock && seen.add(block) -> block
        else -> null  // Duplicate or wrong type
    }
}
```

#### Step 2b: Availability Check

**Method:** `blocks.areAllFree()`  (extension function)

**Checks:** All blocks in FREE state

**Why Check Before Reserve?**
- Early rejection of blocked paths
- Avoids unnecessary rollback overhead
- Clear separation of validation and modification

**Trade-off:** TOCTOU window exists (see [TOCTOU Analysis](#toctou-trade-off-analysis))

#### Step 2c: Atomic Reservation

**Method:** `tryAtomicReservation(trainId, separator, blocks)`

**Algorithm:**
```kotlin
val reservedSoFar = mutableListOf<DynamicTrackBlock>()
try {
    for (block in blocks) {
        block.setUpPathWithTrainId(separator, trainId)
        reservedSoFar.add(block)
    }
    return null  // Success
} catch (e: TrackReservationException) {
    // Rollback partial reservation
    rollbackReservation(separator, reservedSoFar)
    return classifyError(e)
}
```

**Atomicity Guarantee:**
- Either all blocks reserved, or none
- Partial failures trigger automatic rollback

**Rollback Strategy:**
```kotlin
for (block in reservedSoFar) {
    if (block.reservedFrom === separator) {
        block.cancelPathSetup(separator)
    }
}
```

**Error Handling:**
- `AlreadyReservedConflict` → Try next path (TOCTOU race)
- `InvalidStateTransition` → Log error, try next path (unexpected)

#### Step 2d: Ownership Registration

**Method:** `registry.registerAtomic(trainId, blocks)`

**Purpose:** Track train ownership in registry

**Atomicity Guarantee:**
- Either all blocks registered, or none
- Conflict detection before registration

**Result Handling:**
```kotlin
when (val result = registry.registerAtomic(trainId, blocks)) {
    is Success -> return Success(blocks)
    is Conflict -> {
        rollbackReservation(separator, blocks)
        return Conflict(result.conflictingBlock, result.existingOwner)
    }
}
```

**Why After Block Reservation?**
- Block state changes must precede registry updates
- Registry is metadata tracking, not primary state

---

## 4. Error Handling Strategy

### Exception Hierarchy

```
TrackReservationException (sealed class)
├── AlreadyReservedConflict
└── InvalidStateTransition
```

**Benefits of Sealed Class:**
- Compile-time exhaustive checking
- Type-safe error classification
- Structured error data (no string parsing)

### Error Classification

| Exception Type | Scenario | Recovery Strategy | Severity |
|---|---|---|---|
| **AlreadyReservedConflict** | Block reserved from different separator (TOCTOU race) | Try next path in list | Low |
| **InvalidStateTransition** | State machine violation (programming error) | Try next path, log error | Medium |
| **RegistrationResult.Conflict** | Registry detects ownership conflict | Rollback blocks, return Conflict | Medium |
| **NoPathExists** | No topological route (disconnected network) | Report to caller | High |
| **NonContiguousStart** | Start separator bounds none of the train's blocks (Issue #893, A-R1); will never succeed while the train stays where it is | Caller must request a different origin; do NOT retry the same start | High |

### Handling Guidelines

#### AlreadyReservedConflict

**Scenario:**
```kotlin
// Block was FREE during check, but became RESERVED before reservation
if (blocks.areAllFree()) {  // Check: all FREE
    // ... Time passes ...
    block.setUpPath(separator)  // Reserve: throws AlreadyReservedConflict!
}
```

**Recovery:**
```kotlin
catch (e: TrackReservationException.AlreadyReservedConflict) {
    logger.warn { "TOCTOU race: Block ${e.block} reserved by ${e.existingSeparator}" }
    // Rollback partial reservation
    rollbackReservation(separator, reservedSoFar)
    // Try next path
    continue
}
```

**Expected Frequency:** Rare (single-threaded model), but possible with complex interlocking logic

#### InvalidStateTransition

**Scenario:**
```kotlin
// Attempt illegal transition (e.g., OCCUPIED → RESERVED)
block.setUpPath(separator)  // Block is OCCUPIED, throws InvalidStateTransition
```

**Recovery:**
```kotlin
catch (e: TrackReservationException.InvalidStateTransition) {
    logger.error(e) { "State violation: ${e.operation} from ${e.fromState}" }
    // Rollback and try next path
    rollbackReservation(separator, reservedSoFar)
    continue
}
```

**Expected Frequency:** Should never happen (indicates programming bug)

#### RegistrationResult.Conflict

**Scenario:**
```kotlin
// Block reservation succeeded, but registry detects conflict
// (Different train already registered the block)
when (val result = registry.registerAtomic(trainId, blocks)) {
    is Conflict -> {
        logger.warn { "Registry conflict: ${result.conflictingBlock} owned by ${result.existingOwner}" }
        rollbackReservation(separator, blocks)
        return Conflict(result.conflictingBlock, result.existingOwner)
    }
}
```

**Expected Frequency:** Should never happen (indicates programming bug or race condition)

---

## 5. Signal-Clearing Invariants (Issue #893, G1–G7)

**Contract** (pinned by `PathReservationServiceTest.kt:1571-1592`): *a proceed aspect may not outlive the reservation that produced it.* Every release path resets the train's cleared signals to STOP before any block becomes FREE/available to another train. Until the #893 fix, `releasePath` and `unregister` cancelled blocks and unlocked switches but never touched a semaphore, so every aspect they cleared stayed lit forever — observed live on `exampleGui shuntingLoopAI 333`, where an `A → B` route granted at t=26.0 cleared `zA`, `doA1`, `doB1`; the `OrphanReservationSweeper` cancelled the stale route at t=88.0; all three were still showing S80 at the end of the run. `Signal.STOP` is always the fail-safe direction: it authorises nothing, so resetting too eagerly can only ever be over-restrictive.

### G1–G7 Invariant Table

| G | Invariant | Task | Source |
|---|-----------|------|--------|
| G1 | A partial (tail) route release must reset the governing semaphores of every released block through the ownership-aware `resetSemaphoresForReleasedBlocks`, not just `reservedFrom as? DynamicRailSemaphore`. | A3 | `PathReservationService.kt:647-702`; impl `DefaultPathReservationService.kt:2655-2676` |
| G2 | `unregisterBlock` (per-block tail-clearance) must return the released block's governing semaphores to STOP, not just update the registry. | A4 | impl `DefaultPathReservationService.kt:2623-2636` (reset call at :2632 before emit at :2633) |
| G3 | A signal-config failure in `reservePath` Step 2g must undo any PARTIAL aspect write (START may be physically lit though `recordClearedSemaphore` never ran) via `resetSemaphoreSet` + `resetUnrecordedStartSignal`. | A5 | `DefaultPathReservationService.kt:219`, `:290`, Step 2g `:641-659` |
| G4 | A route whose START semaphore faces AWAY from the requested direction must be rejected outright (a rear-facing START grants no proceed authority — a #566-class stall). | A1 | `DefaultPathReservationService.kt:2306-2342` (`configureStartSignal` G4 guard) |
| G5 | `DispatchDecision.SetSignalAspect` carries an optional `trainName`; `DefaultNetworkActuatorPort.setSignalAspect` has an attributed 3-arg overload recording through the same single ledger — closing the tracking-contract hole for any future caller. (Attribution-only; no live behaviour change.) | A6 | commit `e8f5460e` |
| G6 | Every cleared signal — including ones written by external paths (`DefaultInterlockingFacade.clearSignal`, the facade's block-list `requestRoute`) — must be folded into the single service ledger (`recordExternalClearedSemaphore`) so a later `releasePath`/sweeper reset can find and drop it. | A6 | `PathReservationService.kt:278-306`; impl `DefaultPathReservationService.kt:959-970` |
| G7 | `releasePath` must reset a train's cleared signals BEFORE its `blocks.isEmpty()` early return, and `releaseRoute` must report truthfully (via `hasClearedSignals`) when a train holds cleared signals but zero blocks — a signals-only reclaim counts as work done. | A7 | impl `DefaultPathReservationService.kt:896-906` (reset at :901 before empty-blocks early return), `:957` |

**Malformation class note:** G4 (rear-facing START rejection) and the contiguity precondition (A-R1, [Step 0](#step-0-contiguity-precondition-issue-893-task-a-r1)) are the same malformation class — a route the train cannot actually use (`PathReservationServiceTest.kt:2957`).

### Ownership-Aware Reset APIs

Three ownership-aware APIs underpin G1–G7. All share a single cleared-signal ledger (`clearedSemaphores` / `semaphoreClearedFor`) maintained by `recordClearedSemaphore` (`DefaultPathReservationService.kt:165-172`).

#### `resetSemaphoresForReleasedBlocks(trainId, blocks)`

- **Interface:** `PathReservationService.kt:699`
- **Impl:** `DefaultPathReservationService.kt:2655`
- **Candidate signal sources:** `block.ends()` (intermediate semaphore or InOut's `inSemaphore`) + `block.reservedFrom` (route START incl. InOut's `inSemaphore`). Both sources are needed because `reservePath` sets every reserved block's `reservedFrom` to the ROUTE-START separator, so for a multi-block route only the first block's `reservedFrom` is genuinely adjacent — `ends()` recovers the correct intermediate boundary for later blocks, and `reservedFrom` recovers the START itself (including the InOut case).

#### `hasClearedSignals(trainId)`

- **Interface:** `PathReservationService.kt:270`
- **Impl:** `DefaultPathReservationService.kt:957` (`= clearedSemaphores.containsKey(trainId)`)
- **Purpose:** Read by `DefaultNetworkActuatorPort.releaseRoute` (`:196`) BEFORE `releasePath` (`:197`) purges the bookkeeping this reads — so a signals-only release can be reported truthfully instead of being masked as "nothing happened" (G7).

#### `recordExternalClearedSemaphore(trainId, semaphore)`

- **Interface:** `PathReservationService.kt:303`
- **Impl:** `DefaultPathReservationService.kt:965`
- **Purpose:** The G6 single-signal-ledger entry point for external paths. Delegates to the same `recordClearedSemaphore` used internally by `reservePath`, so an external caller's write is folded into the single ledger this service already maintains. Without it, a facade-granted entry signal stayed lit forever after a sweep.

### Proven-Safe Scope (suffix/rearmost releases only)

The reset is **proven safe only for suffix / rearmost releases on a non-revisiting route** (`PathReservationService.kt:680-693`); NOT for an arbitrary mid-route subset, because the semaphore governing a released block can also be the one a still-reserved downstream block on the same route depends on (an intermediate boundary shared with a block further along). A route that loops back and becomes adjacent to a released block again has the same exposure: the semaphore this call resets may be the one that governs re-entry into the loop.

**Failure direction is fail-safe:** `Signal.STOP` authorises nothing, so the worst outcome of an over-eager reset outside the proven-safe scope is a train stalled behind a signal it still needed — never a train permitted to move where it should not be.

The impl performs no route-position validation of its own; the caller is responsible for staying within the proven-safe scope (`DefaultPathReservationService.kt:2649-2651`). See also [INTERLOCKING_SCOPE_LIMITATIONS.md](INTERLOCKING_SCOPE_LIMITATIONS.md) §B5 (revisiting / circular-route caveat).

### Ownership-Aware Last-Writer-Wins Reset

A semaphore that has since been re-cleared for a DIFFERENT train is left alone by a later release. This is **single-threaded sequential ownership hygiene** (the simulation is single-threaded — kDisco runs one sim thread), not a concurrency guard. Backed by the `semaphoreClearedFor` map:

- `recordClearedSemaphore` (`:165-172`) records `semaphoreClearedFor[semaphore] = trainId`.
- `resetClearedSemaphores` (`:181-203`) skips when `semaphoreClearedFor[semaphore] != trainId` (at `:184`).
- `resetSemaphoreSet` (`:219-249`) applies the same guard (at `:232`).

**Test:** `PathReservationServiceTest.kt:1649-1707` — *"resetSemaphoresForReleasedBlocks leaves a since re-cleared semaphore alone, and a later releasePath does too"*. After `t2` re-clears a semaphore `t1` released, a stale `resetSemaphoresForReleasedBlocks("t1", ...)` and a subsequent `releasePath("t1")` both leave `t2`'s live signal untouched.

---

## 6. Cancel / Release Paths Taxonomy

**Invariant: signals-first-blocks-second** — every full/partial release resets the train's cleared signals to STOP before any block becomes FREE/available to another train. A block must never become available to another train while the semaphore that authorises entry to it still shows proceed.

> **Scope note (*storno* vs *uvolnění*):** real railways distinguish *storno* (cancel before occupancy: dispatcher command, signal→Stop, then a mandatory time-release timer before the *závěr* frees) from *uvolnění* (release after occupancy: automatic/progressive behind the train). The simulator conflates both into the single release path below and has no approach-locking timer — acceptable because entry is gated on reservation, not signal-sighting (see [INTERLOCKING_SCOPE_LIMITATIONS.md](INTERLOCKING_SCOPE_LIMITATIONS.md) §B1 for the full safety argument).

There are six release call sites:

| # | Call site | Location | Role | Signals-first? |
|---|-----------|----------|------|----------------|
| 1 | `releasePath` | `DefaultPathReservationService.kt:896` | Full-route release. Reset `:901` (resetClearedSemaphores) before empty-blocks early return and before per-block `cancelPathSetup` `:913-922`; `registry.unregister` in `finally` `:939`. Invariant comment `:897-900`. | Yes (self-enforced) |
| 2 | `unregister` | `:2548` | Production train-completion path (`Train → releaseTrainReservations`). Reset `:2551`, switch unlock `:2555-2563`, **per-block `cancelPathSetup` `:2574-2583`**, `registry.unregister` `:2585`. **F1 fix (commit `d99862bc`):** the per-block `cancelPathSetup` loop was ADDED so a journey completing with RESERVED-but-never-entered blocks (bidirectional reversal / abandoned extension) no longer leaves orphan RESERVED blocks. Previously `registry.unregister` only removed ownership maps and `emitBlockReleased` hardcoded `newState=FREE`, masking the state/event divergence. Mirrors `releasePath` `:913-922` (comment `:2565-2572`). | Yes (self-enforced) |
| 3 | `unregisterBlock` | `:2623` | Production per-block tail-clearance (`Train.Tail.separatorAction`). `resetSemaphoresForReleasedBlocks(trainId, listOf(block))` `:2632` BEFORE `emitBlockReleased` `:2633` (**F2 fix**, commit `d99862bc` — comment `:2629-2631` "Matches releasePath's invariant :897-900"). | Yes (self-enforced) |
| 4 | bypass-rollback | `releaseBypassRollbackBlocks` `:1140` (private helper; call site `reservePathToAnyNextSemaphore` `:1076`) | Releases the wrongly-reserved path when the reserved path doesn't use the required `next` block. Per-block `cancelPathSetup` with `registry.unregisterBlock` in `finally` `:1154-1158` (**F3 fix**: `finally` so a `cancelPathSetup` throw no longer leaks a registered block; uses `block.reservedFrom`). Signals reset done by the caller at `:1072-1074` before the helper. | Caller-enforced |
| 5 | `rollbackUnconfigurableCandidate` | `:2500` (internal) | Rolls back a candidate whose switches (Step 2f) or START signal (Step 2g) couldn't be configured. Scoped to the candidate's own mutations (no `registry.unregister(trainId)`); per-block `cancelPathSetup` with `registry.unregisterBlock` in `finally` `:2529-2531` (**F3 fix**). Signal-config-failure (G3) reset done by the caller in Step 2g. | Caller-enforced |
| 6 | `releaseUntravelledTail` | `dispatcher-agent/.../PartialRouteReleaser.kt:46` (interface), impl `RegistryPartialRouteReleaser.kt:73` | Releases the un-travelled tail of a stalled reservation while leaving the occupied block registered. `resetSemaphoresForReleasedBlocks(trainId, eligible)` `RegistryPartialRouteReleaser.kt:106` BEFORE any `cancelPathSetup` `:113` / `unregisterBlock` `:114`. Caller `OrphanReservationSweeper.kt:315`. | Yes (self-enforced) |

**Summary:** `releasePath`, `unregister`, `unregisterBlock`, and `releaseUntravelledTail` enforce signals-first-blocks-second themselves; the two rollback helpers handle a never-granted candidate and rely on their caller (Step 2g / the bypass path) to reset signals.

---

## 7. TOCTOU Trade-Off Analysis

### Problem Description

**TOCTOU (Time-Of-Check-Time-Of-Use)** race condition exists between:

1. **Check:** `if (!blocks.areAllFree())` (line 116-119 in DefaultPathReservationService.kt)
2. **Use:** `tryAtomicReservation()` (line 128-137)

**Window:** Small time gap where block state could change.

### Scenario Example

```
Thread/Process 1 (Train A):
    t0: Check blocks [B1, B2, B3] → all FREE
    t1: Reserve B1 → SUCCESS

Thread/Process 2 (Train B):  [HYPOTHETICAL - system is single-threaded]
    t1.5: Reserve B2 → SUCCESS  [Would require multi-threading]

Thread/Process 1 (Train A):
    t2: Reserve B2 → AlreadyReservedConflict!
    t3: Rollback B1
```

### Why Acceptable in Current System

#### 1. Single-Threaded Execution

**Current Model:** jDisco discrete event simulation runs in single thread.

**Implication:** No concurrent reservations possible.

**Evidence:** All simulation processes (Train, InOutWorker, Generator) execute sequentially.

#### 2. Atomic Rollback

**Detection:** `AlreadyReservedConflict` exception thrown during reservation.

**Recovery:** Immediate rollback of partial reservation.

**Guarantee:** System never left in inconsistent state.

#### 3. Worst Case is Acceptable

**Failure Mode:** False positive (path appears free but isn't).

**Consequence:** Try next path in candidate list.

**Impact:** Minimal performance penalty (one extra path attempt).

### Detection and Recovery Flow

```mermaid
graph TD
    A[Check blocks FREE] -->|All FREE| B[Start reservation]
    B --> C[Reserve Block 1]
    C --> D[Reserve Block 2]
    D -->|AlreadyReservedConflict| E[Catch exception]
    E --> F[Rollback Block 1]
    F --> G[Try next path]

    D -->|Success| H[Reserve Block N]
    H --> I[Register ownership]
    I --> J[Return Success]
```

### Future Multi-Threading Considerations

If simulation engine is migrated to multi-threaded execution, synchronization required:

#### Option 1: Block-Level Locking

```kotlin
synchronized(blocks) {
    if (blocks.areAllFree()) {
        tryAtomicReservation(blocks, trainId, separator)
    }
}
```

**Pros:** Fine-grained locking
**Cons:** Lock contention, deadlock risk

#### Option 2: Global Reservation Lock

```kotlin
private val reservationLock = ReentrantLock()

fun reservePath(...): ReservationResult {
    reservationLock.withLock {
        // Entire reservation algorithm
    }
}
```

**Pros:** Eliminates TOCTOU, simple to reason about
**Cons:** Serializes all reservations, performance bottleneck

#### Option 3: Optimistic Locking (Recommended)

```kotlin
// Keep current TOCTOU model, rely on exception-based rollback
// Multi-threaded performance tests show acceptable contention rates
```

**Pros:** No lock overhead, good parallelism
**Cons:** Retry overhead on contention

### Decision: Keep Current Model

**Rationale:**
1. Single-threaded execution makes TOCTOU extremely rare
2. Exception-based rollback is clean and testable
3. No performance penalty in current system
4. Easy to add synchronization later if needed

**Documentation:** This trade-off is clearly documented for future maintainers.

---

## 8. Design Decisions

### String-Based Train IDs vs Object References

**Decision:** Use `String trainId` instead of `TrackOccupant` references in registry and block state.

**Rationale:**

#### 1. Early Reservation Support

**Requirement:** Reserve path before train object exists.

**Example:**
```kotlin
// Generator creates train AFTER path is reserved
val result = reservationService.reservePath("Train-${nextId++}", entry, exit)
if (result is Success) {
    val train = Train(trainId, entry)  // Create train object now
}
```

**Alternative (rejected):** Use dummy train objects → unnecessary complexity.

#### 2. Registry Decoupling

**Benefit:** Registry doesn't need full object graph references.

**Simplification:**
```kotlin
// With String IDs:
val owner = registry.getOwner(block)  // Returns "Train-123"

// With Object references (rejected):
val owner = registry.getOwner(block)  // Returns Train instance
val ownerId = owner.toString()  // Extra indirection
```

#### 3. Clearer Logging

**With String IDs:**
```
[INFO] Block reserved by Train-123
[WARN] Conflict: Block owned by Train-456
```

**With Object References:**
```
[INFO] Block reserved by Train@7a8b3c
[WARN] Conflict: Block owned by Train@9f4e2d  // Useless for debugging
```

#### 4. Path Release Without Object

**Use Case:** Release path when train object no longer available.

**Example:**
```kotlin
// Train exited network, object cleaned up
// But we still need to release blocks
reservationService.releasePath("Train-123")  // String ID sufficient
```

**Alternative (rejected):** Keep train object alive for registry cleanup → memory leak risk.

### Bidirectional Registry Mappings

**Decision:** Maintain both `trainToBlocks` and `blockToTrain` maps.

**Rationale:**

#### 1. O(1) Conflict Detection

**Query:** "Is this block already owned?"

**Implementation:**
```kotlin
val existingOwner = blockToTrain[block]  // O(1) lookup
if (existingOwner != null && existingOwner != trainId) {
    return RegistrationResult.Conflict(block, existingOwner)
}
```

**Alternative (rejected):** Search all train block lists → O(n*m) where n=trains, m=avg blocks/train.

#### 2. O(1) Block Release

**Query:** "What blocks does this train own?"

**Implementation:**
```kotlin
val blocks = trainToBlocks[trainId]  // O(1) lookup
blocks.forEach { block -> block.cancelPathSetup(...) }
```

**Alternative (rejected):** Search all blocks in network → O(n) where n=total blocks.

#### 3. Minimal Memory Overhead

**Analysis:**
- `trainToBlocks`: ~10-20 trains × ~10-50 blocks = ~1000 entries
- `blockToTrain`: ~100-500 total blocks = ~500 entries
- Total: ~1500 map entries × ~32 bytes = ~48 KB

**Verdict:** Negligible for modern JVM (typical heap: 512 MB - 2 GB).

### Factory Scope for Services

**Decision:** Use Koin `factory` scope for navigator and service (not `single` singleton).

**Code:**
```kotlin
val navigationModule = module {
    factory<TopologyNavigator> { (context: Context<Cell>) ->
        DefaultTopologyNavigator(context)
    }

    factory<PathReservationService> { (navigator: TopologyNavigator, environment: SimulationEnvironment) ->
        DefaultPathReservationService(navigator, environment)
    }
}
```

**Rationale:**

#### 1. Prevents State Pollution

**Problem:** Singleton shares state across simulation runs.

**Example:**
```kotlin
// Run 1: Simulation A reserves blocks
val service = get<PathReservationService>()  // Singleton
service.reservePath("Train-1", ...)

// Run 2: Simulation B starts
val service = get<PathReservationService>()  // SAME INSTANCE!
// Registry still contains blocks from Simulation A → incorrect state
```

**Solution:** Factory creates fresh instance per injection.

#### 2. Clear Lifecycle Management

**Factory Pattern:** Each context gets its own navigator and service.

**Lifecycle:**
```kotlin
// Simulation start
val context = SimulationContextFactory.create(...)
val navigator = getKoin().get { parametersOf(context) }  // New instance
val service = getKoin().get { parametersOf(navigator, context) }  // New instance

// Simulation end
context.dispose()  // All services garbage-collected
```

#### 3. Parameter Passing

**Factory enables constructor parameter injection:**
```kotlin
factory<TopologyNavigator> { (context: Context<Cell>) ->
    DefaultTopologyNavigator(context)  // Context passed from callsite
}
```

**Alternative (rejected):** Singleton with mutable context → thread-safety nightmare.

### trainId Preservation Across States

**Decision:** Preserve `trainId` during RESERVED → OCCUPIED transition.

**Code:**
```kotlin
override fun enter(newOccupant: TrackOccupant) {
    // ...state transition...
    occupant = newOccupant
    reservedFrom = null

    // IMPORTANT: trainId remains set from reservation phase
    // This preserves ownership tracking across the transition
}
```

**Rationale:**

#### 1. Registry Continuity

**Problem:** Registry needs to know train ownership while train is physically present.

**Use Case:**
```kotlin
// During OCCUPIED state
val owner = registry.getOwner(block)  // Must return "Train-123"
```

**Alternative (rejected):** Re-register during enter() → unnecessary complexity.

#### 2. Path Release Logic

**Use Case:**
```kotlin
// Train calls releasePath() while partially in network
// Some blocks RESERVED, others OCCUPIED
service.releasePath("Train-123")  // Works for both states
```

**Requirement:** trainId available in both RESERVED and OCCUPIED states.

#### 3. Logging Consistency

**Benefit:** trainId appears in logs for entire block lifecycle.

**Log Output:**
```
[INFO] Block RESERVE: trainId=Train-123
[INFO] Block ENTRY: trainId=Train-123  // Still present!
[INFO] Block EXIT: trainId=Train-123
```

**Alternative (rejected):** Lose trainId on enter → log correlation impossible.

---

## 9. Sequence Diagrams

### Successful Reservation Flow

```mermaid
sequenceDiagram
    participant Train
    participant Service as PathReservationService
    participant Navigator
    participant Registry
    participant Block as DynamicTrackBlock

    Train->>Service: reservePath("Train-1", entry, exit)
    Service->>Navigator: findAllTopologicalPaths(entry, exit)
    Navigator-->>Service: [path1, path2]

    loop For each path
        Service->>Service: extractUniqueBlocks(path1)
        Service->>Service: blocks.areAllFree()?
        alt All blocks FREE
            loop For each block
                Service->>Block: setUpPathWithTrainId(separator, "Train-1")
                Block-->>Service: SUCCESS
            end
            Service->>Registry: registerAtomic("Train-1", blocks)
            Registry-->>Service: Success
            Service-->>Train: Success(blocks)
        else Some blocks OCCUPIED
            Service->>Service: Try next path
        end
    end
```

### Conflict Detection and Fallback

```mermaid
sequenceDiagram
    participant Train
    participant Service as PathReservationService
    participant Navigator
    participant Registry
    participant Block1
    participant Block2

    Train->>Service: reservePath("Train-1", entry, exit)
    Service->>Navigator: findAllTopologicalPaths(entry, exit)
    Navigator-->>Service: [path1]

    Service->>Service: extractUniqueBlocks(path1)
    Service->>Service: blocks.areAllFree() → true

    Service->>Block1: setUpPathWithTrainId(sep, "Train-1")
    Block1-->>Service: SUCCESS

    Service->>Block2: setUpPathWithTrainId(sep, "Train-1")
    Block2-->>Service: AlreadyReservedConflict!

    Service->>Service: Catch exception
    Service->>Block1: cancelPathSetup(sep)
    Block1-->>Service: Rollback success

    Service-->>Train: AllPathsBlocked(1)
```

### Path Release Flow

```mermaid
sequenceDiagram
    participant Train
    participant Service as PathReservationService
    participant Registry
    participant Blocks as DynamicTrackBlock[n]

    Train->>Service: releasePath("Train-1")
    Service->>Registry: getBlocks("Train-1")
    Registry-->>Service: [block1, block2, block3]

    loop For each block
        Service->>Blocks: cancelPathSetup(reservedFrom)
        Blocks-->>Service: State: RESERVED → FREE
    end

    Service->>Registry: unregister("Train-1")
    Registry-->>Service: Released blocks

    Service-->>Train: [block1, block2, block3]
```

### Registry Atomic Operation

```mermaid
sequenceDiagram
    participant Service as PathReservationService
    participant Registry

    Service->>Registry: registerAtomic("Train-1", [B1, B2, B3])

    alt No conflicts
        Registry->>Registry: Validate: B1 not owned
        Registry->>Registry: Validate: B2 not owned
        Registry->>Registry: Validate: B3 not owned
        Registry->>Registry: Register all blocks
        Registry-->>Service: Success
    else Conflict detected
        Registry->>Registry: Validate: B1 not owned
        Registry->>Registry: Validate: B2 owned by Train-2!
        Registry->>Registry: Register NOTHING (atomic guarantee)
        Registry-->>Service: Conflict(B2, "Train-2")
    end
```

### Cancel-Route (releaseRoute) Flow (Issue #893, G7)

The `cancel_route` tool path. The G7 invariant — `hasClearedSignals` is read BEFORE `releasePath` purges the bookkeeping — ensures a signals-only reclaim (train holds cleared signals but zero blocks, reachable after a partial release reclaimed its un-travelled tail) is reported truthfully as work done.

```mermaid
sequenceDiagram
    participant LLM as LLM dispatcher
    participant Applier as DispatchDecisionApplier
    participant Port as NetworkActuatorPort
    participant Service as PathReservationService
    participant Registry

    LLM->>Applier: DispatchDecision.ReleaseRoute(trainName)
    Applier->>Port: releaseRoute(trainName)

    Note over Port,Service: G7: read hasClearedSignals BEFORE releasePath<br/>purges the bookkeeping this reads
    Port->>Service: hasClearedSignals(trainName)
    Service-->>Port: true / false

    Port->>Service: releasePath(trainName)
    Note over Service: Signals-first: resetClearedSemaphores(trainName)<br/>BEFORE blocks become FREE
    Service->>Service: resetClearedSemaphores(trainName)
    Service->>Registry: getBlocks(trainName)
    Registry-->>Service: blocks (may be empty)

    alt blocks non-empty
        loop For each block
            Service->>Service: cancelPathSetup(reservedFrom)
        end
    end
    Note over Service,Registry: registry.unregister in finally<br/>(guaranteed even if cancelPathSetup throws)
    Service->>Registry: unregister(trainName)
    Service-->>Port: released blocks

    Note over Port: return releasedBlocks.isNotEmpty() || hadClearedSignals<br/>(truthful: signals-only reclaim = work done)
    Port-->>Applier: true / false
    Applier-->>LLM: outcome
```

**Source:** `DefaultNetworkActuatorPort.releaseRoute` (`:192-210`); `hasClearedSignals` read at `:196` before `releasePath` at `:197`; truthfulness ruling at `:209` (`releasedBlocks.isNotEmpty() || hadClearedSignals`). Test: `DefaultNetworkActuatorPortTest.kt:524-538` documents the read-before-purge ordering.

---

## 10. Future Considerations

### Multi-Threading Support

**Trigger:** Migration from jDisco to DSOL/Kalasim with multi-threaded simulation engine.

**Required Changes:**

1. **Add synchronization around block state checks and transitions**
   ```kotlin
   synchronized(blocks) {
       if (blocks.areAllFree()) {
           tryAtomicReservation(blocks, trainId, separator)
       }
   }
   ```

2. **Consider lock-free data structures for registry**
   ```kotlin
   private val blockToTrain = ConcurrentHashMap<DynamicTrackBlock, String>()
   private val trainToBlocks = ConcurrentHashMap<String, CopyOnWriteArrayList<DynamicTrackBlock>>()
   ```

3. **Profile lock contention impact**
   - Measure reservation throughput with 10, 50, 100 concurrent trains
   - Identify bottlenecks (global lock vs. fine-grained locking)

**Trade-off:** Lock overhead vs. TOCTOU elimination.

**Recommendation:** Profile first, optimize second (current TOCTOU model may be acceptable even with multi-threading).

### Deadlock Detection

**Current System:** Prevents deadlock via early reservation.

**Limitation:** No detection of circular wait conditions.

**Future Enhancement:**

#### Deadlock Detection Algorithm

```kotlin
fun detectCircularWait(trainId: String, requestedBlocks: List<DynamicTrackBlock>): Boolean {
    val visited = mutableSetOf<String>()
    val stack = mutableListOf(trainId)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (current in visited) return true  // Cycle detected!
        visited.add(current)

        // Find trains blocking this train
        val blockingTrains = requestedBlocks
            .mapNotNull { registry.getOwner(it) }
            .filter { it != current }

        stack.addAll(blockingTrains)
    }
    return false
}
```

#### Deadlock Resolution Strategies

1. **Train Priority** - High-priority trains preempt low-priority reservations
2. **Timeout + Backoff** - Release reservation after timeout, retry with backoff
3. **Deadlock Avoidance** - Check for potential deadlock before reservation

**Implementation Complexity:** Medium (requires dependency tracking)

**Performance Impact:** Low (only activated on reservation failure)

### Priority Reservations

**Use Case:** Express trains should preempt local trains.

**Design:**

```kotlin
fun reservePath(
    trainId: String,
    start: PathSeparator,
    target: PathSeparator,
    priority: TrainPriority = TrainPriority.NORMAL
): ReservationResult
```

#### Priority Levels

| Priority | Behavior | Use Case |
|---|---|---|
| LOW | Cannot preempt, waits for free path | Shunting operations |
| NORMAL | Standard reservation | Regular passenger/freight |
| HIGH | Can preempt LOW reservations | Express passenger |
| EMERGENCY | Can preempt LOW/NORMAL | Rescue/maintenance |

#### Preemption Logic

```kotlin
if (conflict && requestPriority > existingPriority) {
    val preempted = registry.preempt(conflictingBlock, trainId, priority)
    logger.info { "Preempted $preempted (priority ${preempted.priority})" }
    // Send notification to preempted train
    notifyPreemption(preempted)
}
```

**Trade-off:** Fairness vs. priority adherence.

**Documentation Required:** Clear policy on when preemption is allowed.

### Performance Optimization

#### Caching Path Finding Results

**Observation:** Paths between InOut pairs are static (topology doesn't change during simulation).

**Optimization:**
```kotlin
private val pathCache = ConcurrentHashMap<Pair<PathSeparator, PathSeparator>, List<List<TrackSection>>>()

fun findAllTopologicalPaths(start: PathSeparator, target: PathSeparator): List<List<TrackSection>> {
    return pathCache.getOrPut(start to target) {
        computePaths(start, target)  // Expensive BFS
    }
}
```

**Benefit:** O(1) path lookup after first call.

**Invalidation:** Clear cache on topology changes (switches, track closures).

#### Indexing Registry by Track Sections

**Problem:** Current registry indexes by individual blocks, but queries often involve track sections.

**Optimization:**
```kotlin
private val sectionToTrain = ConcurrentHashMap<TrackSection, String>()

fun reserveSection(trainId: String, section: TrackSection) {
    val existingOwner = sectionToTrain[section]
    if (existingOwner != null && existingOwner != trainId) {
        throw ConflictException(section, existingOwner)
    }
    sectionToTrain[section] = trainId
}
```

**Benefit:** Reduced loop overhead, better cache locality.

**Trade-off:** Increased memory usage (sections AND blocks indexed).

#### Profiling Large-Scale Simulations

**Benchmark Scenarios:**
- 1000+ block network
- 100+ concurrent trains
- 10,000+ reservation operations

**Metrics:**
- BFS path finding time
- Reservation latency (50th, 95th, 99th percentile)
- Rollback frequency
- Memory usage

**Tool:** JMH (Java Microbenchmark Harness) for accurate profiling.

### Alternative Simulation Engines

**Context:** Path reservation system designed to be simulation-engine-agnostic.

**Current:** jDisco (discrete event simulation, single-threaded)

**Future Options:** DSOL, Kalasim (see LONG_TERM_GOALS.md)

#### Migration Strategy

**Abstraction Layer:** `SimulationEnvironment` interface enables adapter pattern.

**Steps:**
1. Implement `DSolSimulationEnvironment` adapter
2. Replace jDisco Process with DSOL event scheduling
3. Update simulation loop (while maintaining single-threaded model initially)
4. Run integration tests with both engines in parallel (A/B validation)
5. Profile performance (DSOL should be faster due to modern JVM optimizations)

**Risk Mitigation:**
- PathReservationService code unchanged (depends only on SimulationEnvironment interface)
- Test suite validates behavior identical between engines
- Gradual rollout (one simulation scenario at a time)

**Expected Effort:** Medium (2-4 weeks for full migration and validation)

---

## Appendix A: Related Documentation

- [CLAUDE.md](../CLAUDE.md) - Project overview and architecture
- [CONTEXT_REFACTORING_DESIGN.md](CONTEXT_REFACTORING_DESIGN.md) - Context system design (Issue #98)
- [STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md](STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md) - Static/dynamic wrapper pattern (Issue #100)
- [LONG_TERM_GOALS.md](../LONG_TERM_GOALS.md) - Project roadmap (Issue #293)

## Appendix B: Code References

**Key Files:**
- `PathReservationService.kt` - Interface definition
- `DefaultPathReservationService.kt` - Implementation
- `PathReservationRegistry.kt` - Ownership tracking
- `TopologyNavigator.kt` - Path finding interface
- `DynamicTrackBlock.kt` - Block state machine
- `TrackReservationException.kt` - Exception hierarchy
- `DynamicTrackBlockExtensions.kt` - Kotlin extension functions

> **Note:** These files grow over the life of the project. Specific line counts rot quickly and are intentionally omitted — consult the current source for sizes. (At the time of v1.1, `PathReservationService.kt` is ~715 lines and `DefaultPathReservationService.kt` is ~2915 lines.)

**Test Files:**
- `PathReservationServiceTest.kt` - 15 test cases (382 lines)
- `PathReservationRegistryTest.kt` - 16 test cases (NEW)

**Total Test Coverage:**
- PathReservationService: 90%+
- PathReservationRegistry: 95%+
- DynamicTrackBlock (reservation paths): 85%+

## Appendix C: Version History

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0 | 2026-01-26 | Claude Code | Initial comprehensive documentation |
| 1.1 | 2026-08-09 | Claude Code | Issue #893: add G1–G7 signal-clearing invariants, ownership-aware reset APIs (`resetSemaphoresForReleasedBlocks`/`hasClearedSignals`/`recordExternalClearedSemaphore`) with proven-safe-scope and last-writer-wins caveats, `NonContiguousStart` + contiguity precondition (Step 0), Cancel/Release Paths Taxonomy of the six release call sites (signals-first-blocks-second + the `unregister` asymmetry fixed by F1 in `d99862bc`), and a cancel-route sequence diagram. Fix stale Appendix B line counts. |

---

**End of Document**
