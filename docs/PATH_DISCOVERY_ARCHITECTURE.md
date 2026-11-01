# Path Discovery Architecture

**Issue #292 Phases 1-5** - Architecture design for separated static, reservation, and navigation APIs

**Date**: 2026-01-27
**Status**: Implemented
**Authors**: Claude Sonnet 4.5 (traffic-simulation-expert), bedaHovorka
**Authority**: As per TEAM.md, traffic-simulation-expert has authority over simulation architecture and physics

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [Design Goals](#design-goals)
4. [Architecture Overview](#architecture-overview)
5. [Component Design](#component-design)
6. [Design Rationale](#design-rationale)
7. [Trade-Offs](#trade-offs)
8. [Implementation Phases](#implementation-phases)
9. [Testing Strategy](#testing-strategy)
10. [Future Considerations](#future-considerations)

---

## Executive Summary

**Problem**: Original `pathToNextSemaphore()` method mixed three distinct concerns (static topology, dynamic reservation, train navigation), leading to fragility, race conditions, and complex workaround code.

**Solution**: Separate path discovery into three specialized services:
1. **TopologyNavigator** - Pure graph traversal (no state dependencies)
2. **PathReservationService** - Dispatcher logic (find FREE paths, reserve atomically)
3. **TrainNavigationService** - Train-specific navigation (follow RESERVED paths)

**Impact**:
- ✅ Eliminates Issue #291 workaround (manual path construction)
- ✅ Eliminates Issue #282 workaround (block ownership validation)
- ✅ Reduces ShuntingLoop by ~100 lines
- ✅ Enables clean editor validation without SimulationContext
- ✅ Old API removed in Phase 5; every caller migrated (`RoutingServices`)

**Implementation**: 5 phases (2026-01-11 to 2026-01-27), 1321+ tests passing, zero regressions

---

## Problem Statement

### Original Design Issues

**Single Method, Multiple Responsibilities**:
```kotlin
// SimulationEnvironment.pathToNextSemaphore() does THREE things:
fun pathToNextSemaphore(separator: PathSeparator, next: TrackSection): Path?
```

1. **Static Topology Navigation** - Graph traversal from separator to next semaphore
2. **Dynamic State Validation** - Check if blocks are FREE/RESERVED/OCCUPIED
3. **Ownership Ambiguity** - Who owns blocks? Unclear semantics

**Consequences**:
- **Issue #291**: ShuntingLoop required ~100 lines of manual path construction to work around ambiguous block ownership
- **Issue #282**: Trains navigated into blocks reserved by other trains, requiring a validation workaround
- **Editor Limitation**: Static topology validation forced conversion to SimulationContext (mixing concerns)
- **Fragile Tests**: Tests required complex mocking to control block states

---

### Root Cause Analysis

**Violation of Single Responsibility Principle**:
- Topology navigation (static concern) mixed with block state management (dynamic concern)
- Editor operations (static topology) forced to use simulation APIs (dynamic state)
- Train navigation (ownership-specific) used same API as dispatcher (find any FREE path)

**TOCTOU Race Condition**:
```kotlin
// Original pattern (TOCTOU vulnerability):
val path = context.pathToNextSemaphore(start, next) // Time-of-Check
if (path != null) {
    path.blocks.forEach { it.reserveFrom(semaphore) } // Time-of-Use
    // Another train could reserve blocks between check and use!
}
```

**Ambiguous Block Ownership**:
- Blocks have `reservedFrom: PathSeparator?` but no train ID
- Multiple trains could think they own the same block
- No bidirectional tracking (train → blocks, block → train)

---

## Design Goals

### Functional Goals

1. **Clean Separation**: Static topology, dynamic reservation, and train navigation are independent operations
2. **Atomic Reservation**: All-or-nothing path reservation (no partial reservations)
3. **Explicit Ownership**: Clear train↔block ownership tracking (bidirectional)
4. **Type Safety**: Separate interfaces prevent mixing incompatible operations
5. **Backward Compatibility**: Existing code worked unchanged during Phases 1-4; the old API was removed in Phase 5

### Non-Functional Goals

1. **Performance**: No degradation from original implementation (< 10% variance)
2. **Testability**: Services can be tested independently with simple mocks
3. **Maintainability**: Each service has single clear responsibility
4. **Engine Independence**: The services depend on `SimulationEnvironment`, not on the simulation engine (kDisco)
5. **Zero Regressions**: All 1321+ tests passing, simulation behavior unchanged

---

## Architecture Overview

### Three-Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Application Layer                         │
│  (Editor, Interlocking, ShuntingLoop, Train, InOutWorker)  │
└─────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌──────────────┐  ┌─────────────────┐  ┌──────────────────┐
│  Topology    │  │   Reservation   │  │      Train       │
│  Navigator   │  │    Service      │  │   Navigation     │
│  (Static)    │  │  (Dispatcher)   │  │   (Ownership)    │
└──────────────┘  └─────────────────┘  └──────────────────┘
        │                   │                   │
        └───────────────────┴───────────────────┘
                            ▼
                ┌───────────────────────┐
                │  Path Reservation     │
                │      Registry         │
                │  (Ownership Tracker)  │
                └───────────────────────┘
                            ▼
                ┌───────────────────────┐
                │    Network Topology   │
                │   (Graph + Grid)      │
                └───────────────────────┘
```

### Layer Responsibilities

**1. TopologyNavigator (Static Layer)**
- **Input**: Static network topology (graph + grid)
- **Output**: Paths based purely on graph connectivity
- **State**: None (stateless, pure function)
- **Use Case**: Editor validation, network analysis

**2. PathReservationService (Dispatcher Layer)**
- **Input**: Network topology + block states (FREE/RESERVED/OCCUPIED)
- **Output**: Atomically reserved paths for trains
- **State**: Manages PathReservationRegistry (train↔block ownership)
- **Use Case**: Dispatcher finding available routes, interlocking path setup

**3. TrainNavigationService (Train Layer)**
- **Input**: Network topology + block ownership (via registry)
- **Output**: Paths through blocks RESERVED for specific train
- **State**: Queries PathReservationRegistry (read-only from train perspective)
- **Use Case**: Train requesting next track section, navigation validation

**4. PathReservationRegistry (Ownership Layer)**
- **Input**: Reservation/release requests from PathReservationService
- **Output**: Bidirectional train↔block ownership mappings
- **State**: bidirectional train↔block maps plus switch claims and PathInfo, held by delegate tables (see [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §2)
- **Use Case**: Prevent conflicts, enable ownership queries

---

## Component Design

> **Historical sketches.** The Interface blocks below are the January 2026 design sketches and do not match the shipped signatures: the shipped navigator method is `findAllTopologicalPaths`, `reservePath` returns `ReservationResult` (`Success`, `NoPathExists`, `AllPathsBlocked`, `Conflict`, `NonContiguousStart`, `GeometricallyImpossible`, `DivergesFromHeldRoute`), and the registry registers through `registerAtomic`. The implementations were split into many units since; for the current design see [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md), and for exact signatures the source under `context/navigation/`.

### 1. TopologyNavigator

**Interface**:
```kotlin
interface TopologyNavigator {
    /**
     * Find path between two separators using static topology only.
     * No block state validation.
     */
    fun findPath(start: PathSeparator, target: PathSeparator): Path?

    /**
     * Get next track section from separator (pure graph traversal).
     */
    fun getNextTrackSection(separator: PathSeparator, current: TrackSection?): TrackSection?

    /**
     * Find path from separator to next semaphore (static topology).
     */
    fun findPathToNextSemaphore(separator: PathSeparator, next: TrackSection): Path?
}
```

**Design Decisions**:
- **Stateless**: No internal state, pure functions based on topology
- **Context-agnostic**: Works with both EditingContext and SimulationContext
- **Scoped**: One instance per context (Koin scope-per-context pattern)
- **Fast**: No state checks, pure graph algorithms

---

### 2. PathReservationService

**Interface**:
```kotlin
interface PathReservationService {
    /**
     * Reserve path atomically (all-or-nothing).
     * Returns Success with path, or Failure with reason.
     */
    fun reservePath(trainId: String, start: PathSeparator, target: PathSeparator): ReservationResult

    /**
     * Release all blocks reserved by train.
     */
    fun releasePath(trainId: String)

    /**
     * Find all reservable paths (blocks must be FREE).
     * Returns empty list if no paths available.
     */
    fun findReservablePaths(start: PathSeparator, target: PathSeparator): List<Path>
}

sealed class ReservationResult {
    data class Success(val path: Path) : ReservationResult()
    data class Failure(val reason: String) : ReservationResult()
}
```

**Design Decisions**:
- **Atomic Reservation**: All-or-nothing semantics prevent partial reservations
- **Registry Integration**: Bidirectional ownership tracking (train → blocks, block → train)
- **Switch Configuration**: Automatic switch setup during reservation (no manual intervention)
- **Failure Transparency**: Failure result explains why reservation failed
- **TOCTOU Fix**: Check and reserve in single atomic operation (no race condition)

---

### 3. TrainNavigationService

**Interface**:
```kotlin
interface TrainNavigationService {
    /**
     * Find path to next semaphore through blocks RESERVED for this train.
     * Returns null if path includes blocks owned by different train.
     */
    fun findReservedPathForTrain(trainId: String, separator: PathSeparator, next: TrackSection): Path?

    /**
     * Check if path is fully reserved for train.
     */
    fun isPathReservedForTrain(trainId: String, start: PathSeparator, target: PathSeparator): Boolean

    /**
     * Get all blocks currently reserved by train.
     */
    fun getReservedBlocks(trainId: String): Set<DynamicTrackBlock>
}
```

**Design Decisions**:
- **Ownership Validation**: Only returns paths through blocks reserved for specific train
- **Registry Query**: Read-only access to PathReservationRegistry (no direct reservations)
- **Null Semantics**: `null` means "path not reserved for THIS train" (clear failure reason)
- **Backward Compatibility**: Reuses existing `pathToNextSemaphore` for topology navigation

---

### 4. PathReservationRegistry

**Interface**:
```kotlin
class PathReservationRegistry {
    /**
     * Register train ownership of blocks (bidirectional).
     */
    fun register(trainId: String, blocks: Set<DynamicTrackBlock>)

    /**
     * Unregister train (release all blocks).
     */
    fun unregister(trainId: String)

    /**
     * Get all blocks reserved by train.
     */
    fun getBlocks(trainId: String): Set<DynamicTrackBlock>

    /**
     * Get train ID that owns block, or null if FREE.
     */
    fun getOwner(block: DynamicTrackBlock): String?

    /**
     * Check if block is owned by specific train.
     */
    fun isOwnedBy(block: DynamicTrackBlock, trainId: String): Boolean
}
```

**Design Decisions**:
- **Bidirectional Mapping**: Fast queries in both directions (train → blocks, block → train)
- **Scoped Lifetime**: One registry per SimulationContext (Koin scope-per-context)
- **Shared State**: All navigation services within same context share ONE registry
- **Thread Safety**: Not thread-safe (matches the single-threaded kDisco model)

---

## Design Rationale

### Why Three Services Instead of One?

**Separation of Concerns**:
- **TopologyNavigator**: Editor doesn't need dynamic state (separation of static/dynamic)
- **PathReservationService**: Dispatcher needs atomic reservation (prevent race conditions)
- **TrainNavigationService**: Train needs ownership validation (prevent conflicts)

**Example: Editor Validation**:
```kotlin
// Before: Forced to use SimulationContext (mixed concerns)
val simContext = ContextTransformer.transform(editingContext)
val path = simContext.pathToNextSemaphore(start, next)
simContext.close()

// After: Clean separation (static topology only)
val navigator = editingContext.getTopologyNavigator()
val path = navigator.findPathToNextSemaphore(start, next)
```

---

### Why Atomic Reservation?

The original check-then-reserve pattern left a TOCTOU window between checking that the blocks were free and reserving them (the snippet is under [Root Cause Analysis](#root-cause-analysis)). `reservePath` closes it with all-or-nothing reservation and rollback; the remaining window and why it is acceptable in a single-threaded engine are analysed in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §7.

**Benefits**:
- No partial reservations (all-or-nothing)
- Clear success/failure semantics

---

### Why Bidirectional Registry?

**Ownership Queries**:
```kotlin
// Query 1: What blocks does Train1 own?
val blocks = registry.getBlocks("Train1")

// Query 2: Who owns this block?
val owner = registry.getOwner(block)

// Query 3: Does Train1 own this block?
val owned = registry.isOwnedBy(block, "Train1")
```

**Performance**: Both queries are O(1) with bidirectional mapping
**Consistency**: Impossible to have orphaned blocks or phantom owners

---

### Why Scope-Per-Context Pattern?

**Isolation**:
- Each SimulationContext has its own registry (no state bleeding between simulations)
- Services share ONE registry within context (consistent view of ownership)

**Lifecycle**:
```kotlin
val context = factory.createContext()
// Registry created automatically via Koin scope
val service = context.getPathReservationService()
// Uses the context's registry

context.close() // Registry cleaned up automatically
```

**Benefits**:
- No manual lifecycle management
- Automatic cleanup via AutoCloseable pattern
- Type-safe dependency injection

---

## Trade-Offs

### 1. Code Duplication vs. Separation

**Trade-Off**: TopologyNavigator and PathReservationService both do graph traversal

**Decision**: Accept duplication for clean separation
- TopologyNavigator: Pure graph algorithms (no state checks)
- PathReservationService: Graph traversal + state validation + atomic reservation
- **Rationale**: Mixing would force TopologyNavigator to handle dynamic state (defeats purpose)

---

### 2. Performance vs. Safety

**Trade-Off**: Atomic reservation requires locking all blocks before checking availability

**Decision**: Prioritize safety (atomic reservation) over performance
- **Cost**: Slight performance overhead from two-phase commit pattern
- **Benefit**: Zero race conditions, clear success/failure semantics
- **Measurement**: < 10% performance variance in benchmarks

---

### 3. Backward Compatibility vs. Clean Break

**Trade-Off**: Keep deprecated methods or force immediate migration?

**Decision**: Deprecate first (WARNING level), remove after the callers migrated
- **Rationale**: 1321+ tests used the old APIs, an immediate break would have required massive changes
- **Outcome**: ✅ COMPLETE. `pathToNextSemaphore()` and `getNextTrackSection()` are removed; `Train` and `InOutWorker` use the services through `RoutingServices`

---

### 4. Registry State vs. Block State

**Trade-Off**: Should registry be source of truth for ownership, or blocks?

**Decision**: Blocks remain source of truth, registry is secondary index
- **Block**: `state: State` (FREE/RESERVED/OCCUPIED), `reservedFrom: PathSeparator?`
- **Registry**: `trainToBlocks: Map<String, Set<Block>>`, `blockToTrain: Map<Block, String>`
- **Rationale**: Blocks already have state management, registry adds train ID tracking

---

## Implementation Phases

### Phase 1: Extract Static Topology Navigator (Issue #293)

**Goal**: Create TopologyNavigator for static path finding

**Changes**:
- New interface: `TopologyNavigator`
- New implementation: `DefaultTopologyNavigator`
- Koin module: `navigationModule` with scope support
- **Tests**: 35 tests (network topology verification, path discovery)

**Outcome**: Editor can validate network without SimulationContext

---

### Phase 2: Create Path Reservation Service (Issue #294)

**Goal**: Atomic path reservation with ownership tracking

**Changes**:
- New interface: `PathReservationService`
- New implementation: `DefaultPathReservationService`
- New class: `PathReservationRegistry`
- Koin module: Add to `navigationModule`
- **Tests**: 28 tests (atomic reservation, registry, conflict detection)

**Outcome**: Dispatcher can reserve paths atomically, preventing race conditions

---

### Phase 3: Implement Train Navigation Service (Issue #295)

**Goal**: Train-specific navigation with ownership validation

**Changes**:
- New interface: `TrainNavigationService`
- New implementation: `DefaultTrainNavigationService`
- Koin module: Add to `navigationModule`, share registry with PathReservationService
- **Tests**: 22 tests (ownership validation, reserved path queries)

**Outcome**: Trains only navigate through blocks they own, eliminating conflicts

---

### Phase 4: Migrate ShuntingLoop to New APIs (Issue #296)

**Goal**: Prove new architecture works by migrating most complex consumer

**Changes**:
- **Removed**: ~100 lines of manual path construction (`constructPath()` method)
- **Added**: Integration with TopologyNavigator and PathReservationService
- **Simplified**: Switch configuration, path setup logic
- **Tests**: All 35 ShuntingLoop tests passing, golden output validated

**Outcome**: ShuntingLoop simplified, Issue #291 root cause addressed

---

### Phase 5: Deprecate Old APIs and Cleanup (Issue #297) ✅ COMPLETE

**Goal**: Remove deprecated methods, finalize migration

**Changes**:
- **Deprecated, then removed**: `pathToNextSemaphore()`, `getNextTrackSection()`; callers migrated (`RoutingServices`)
- **Added**: `EditingContext.getTopologyNavigator()` method
- **Documentation**: PATH_DISCOVERY_MIGRATION_GUIDE.md, PATH_DISCOVERY_ARCHITECTURE.md

**Outcome**: Clean API, comprehensive documentation

---

## Testing Strategy

### Unit Tests

**TopologyNavigator**:
- Static path finding (BFS correctness)
- Network topology verification (expected separators, connectivity)
- Performance: O(V+E) graph traversal

**PathReservationService**:
- Atomic reservation (all-or-nothing)
- Registry consistency (bidirectional mapping)
- Conflict detection (multiple trains, same block)
- Switch configuration correctness

**TrainNavigationService**:
- Ownership validation (only RESERVED blocks)
- Null path semantics (path not reserved for THIS train)
- Registry queries (getReservedBlocks correctness)

---

### Integration Tests

**ShuntingLoop Scenarios**:
- Two trains, two tracks (k1 and k2 both used)
- Three trains, concurrency (no conflicts, proper load balancing)
- Golden output validation (simulation results unchanged)

**Concurrency Tests**:
- Multiple trains requesting paths simultaneously
- Registry consistency under concurrent access (single-threaded in kDisco, but test design)

---

### Regression Tests

**Golden Output Validation**:
```bash
# Baseline (before migration)
./gradlew runExample -PexampleName=shuntingLoop -PendTime=1024 > baseline.log

# After migration
./gradlew runExample -PexampleName=shuntingLoop -PendTime=1024 > migrated.log

# Compare (ignore timing variations)
diff -u baseline.log migrated.log | grep -v "Time:"
```

**Performance Benchmarks**:
- Path finding: < 10% variance from baseline
- Reservation: < 10% variance from baseline
- Full simulation: < 10% variance from baseline

---

## Future Considerations

### Multi-Threaded Simulations

The simulation is single-threaded (kDisco); there is no planned engine migration. If a multi-threaded engine is ever adopted, the synchronization options for the registry and the reservation are analysed in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §7.

---

### Advanced Routing Strategies

**Current**: First-fit path selection (ShuntingLoop)
**Future Enhancements**:
- **Round-robin load balancing**: Distribute traffic across parallel tracks
- **Priority-based routing**: High-priority trains get preferred paths
- **Predictive reservation**: Reserve paths ahead of train arrival
- **Conflict resolution**: Automatic rerouting when paths conflict

---

### Performance Optimization

**Current**: O(V+E) graph traversal, O(1) registry queries, budgeted and cached switch-constrained route search (Issue #1151)
**Future Optimizations**:
- **Incremental updates**: Update only changed paths when topology changes
- **Spatial indexing**: R-tree or quad-tree for faster topology queries

---

## Summary

### Achievements

✅ **Clean Separation**: Static topology, dynamic reservation, and train navigation are independent
✅ **Atomic Reservation**: All-or-nothing path reservation (no partial reservations)
✅ **Explicit Ownership**: Bidirectional train↔block tracking (clear ownership semantics)
✅ **Simplified Code**: ShuntingLoop reduced by ~100 lines
✅ **Old API Removed**: Callers migrated to the services (`RoutingServices`)
✅ **Zero Regressions**: All 1321+ tests passing, simulation behavior unchanged
✅ **Comprehensive Documentation**: Migration guide + architecture documentation

---

### Key Design Principles

1. **Single Responsibility**: Each service has one clear purpose
2. **Separation of Concerns**: Static topology separate from dynamic state
3. **Interface Segregation**: Clients depend only on methods they use
4. **Dependency Inversion**: Services depend on abstractions (TopologyNavigator interface)
5. **Liskov Substitution**: SimulationContext extends SimulationEnvironment safely
6. **Open/Closed**: Services extensible without modifying existing code

---

### Lessons Learned

**1. Atomic Operations Matter**: TOCTOU race conditions are insidious, atomic reservation eliminates entire class of bugs

**2. Bidirectional Mappings Pay Off**: Small memory overhead (2x storage) for O(1) queries in both directions

**3. Deprecation Strategy**: WARNING-level deprecation allows gradual migration without breaking builds

**4. Scope-Per-Context Pattern**: Koin scopes provide clean lifecycle management with zero boilerplate

**5. Testing Is Critical**: 1321+ tests caught regressions early, golden output validation ensures behavior preservation

---

## Related Documentation

- [PATH_DISCOVERY_MIGRATION_GUIDE.md](PATH_DISCOVERY_MIGRATION_GUIDE.md) - Step-by-step migration instructions
- [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) - Detailed reservation service design
- [CONTEXT_REFACTORING_DESIGN.md](CONTEXT_REFACTORING_DESIGN.md) - Context system architecture
- [STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md](STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md) - Static/dynamic wrapper pattern
- [Issue #292](https://github.com/bedaHovorka/interlockSim/issues/292) - Parent issue for path discovery restructuring

---

**Architecture Status**: ✅ Implemented and Validated
**Last Updated**: 2026-10-29
**Next Steps**: ✅ COMPLETE. `Train.kt` and `InOutWorker.kt` use the services (`RoutingServices`); the old API is removed.
