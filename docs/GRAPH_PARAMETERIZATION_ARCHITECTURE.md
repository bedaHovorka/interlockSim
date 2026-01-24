# Graph Parameterization Architecture (Issue #277)

**Status:** Implemented
**Date:** January 2026
**Related Issues:** #277 (this), #131 (grid parameterization), #275 (unblocked)

---

## Executive Summary

Issue #277 enables **type-safe parameterized graph** to support `DynamicTrackBlock` wrappers during simulation. This architectural enhancement eliminates unchecked type casts and provides direct access to dynamic track state (FREE/RESERVED/OCCUPIED) for simulation processes.

**Key Achievement:** Context graph parameterized with `T extends TrackBlock` allows:
- **EditingContext:** `ExtendedUnorientedGraph<Point, TrackBlock, Segment>` (static configuration)
- **SimulationContext:** `ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>` (dynamic state)

---

## Design Motivation

### Problem Statement (Before Issue #277)

Prior to graph parameterization, accessing track state during simulation required:
1. Retrieve `TrackBlock` from graph (static object)
2. Convert to dynamic wrapper: `context.toDynamic(track)`
3. Access state: `dynamicTrack.getState()`

This created two problems:
- **Type safety:** Graph access required unchecked casts `(TrackBlock) graph.get(...)`
- **Indirection:** Two-step process to access track state

### Solution (After Issue #277)

With parameterized graph, simulation contexts directly return dynamic wrappers:
1. Retrieve `DynamicTrackBlock` from graph (already wrapped)
2. Access state: `dynamicTrack.getState()` ✅

Benefits:
- ✅ **Type-safe:** Compiler verifies track block types at compile time
- ✅ **Single-step:** Direct access to state without wrapper conversion
- ✅ **State visibility:** Simplified state queries for simulation logic and AnimatedSim visualization

---

## Architecture

### Type Parameter Hierarchy

```kotlin
// Base Context interface (parameterized over Cell and TrackBlock types)
interface Context<out C : Cell, T : TrackBlock> {
    fun getGraph(): ExtendedUnorientedGraph<Point, T, Segment>
    fun getRailwayNetGrid(): RailwayNetGrid<C>
    // ... other methods
}

// Editing context specialization (uses static TrackBlock)
interface EditingContext : Context<NodeCell, TrackBlock> {
    fun putCell(point: Point, cell: NodeCell)
    fun joinCells(p1: Point, p2: Point, track: TrackBlock)
    // ... editing operations
}

// Simulation context specialization (uses DynamicTrackBlock)
interface SimulationContext : Context<Cell, DynamicTrackBlock>, SimulationEnvironment {
    fun run(endTime: Long)
    fun stop()
    // ... simulation operations
}
```

**Key Insight:** By parameterizing `Context` with both cell type (`C`) and track block type (`T`), we achieve:
- Editing operations work with static `TrackBlock` objects
- Simulation operations work with `DynamicTrackBlock` wrappers
- Type safety enforced at compile time

### BaseContext Implementation

```kotlin
abstract class BaseContext<T : TrackBlock> : Context<Cell, T> {
    protected val grid: AbstractRailwayNetGrid<Cell>
    protected val graph: ExtendedUnorientedGraph<Point, T, Segment>

    override fun getGraph(): ExtendedUnorientedGraph<Point, T, Segment> = graph
    override fun getRailwayNetGrid(): RailwayNetGrid<Cell> = grid

    // ... shared infrastructure
}
```

**Design Pattern:** BaseContext is parameterized only over `T : TrackBlock` because:
- Grid always uses `Cell` (base type for both editing and simulation)
- Graph uses type parameter `T` (varies by context type)

### Specializations

```kotlin
// Editing context: graph contains static TrackBlock
class DefaultEditingContext : BaseContext<TrackBlock>, EditingContext {
    // Mutable operations: putCell, removeCell, joinCells, etc.
    // Graph type: ExtendedUnorientedGraph<Point, TrackBlock, Segment>
}

// Simulation context: graph contains DynamicTrackBlock
class DefaultSimulationContext(
    processFactory: SimulationProcessFactory
) : BaseContext<DynamicTrackBlock>, SimulationContext {
    // Immutable network structure (frozen after initialization)
    // Graph type: ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
}
```

---

## Transformation Process

### ContextTransformer

The `ContextTransformer` object handles transformation from `EditingContext` to `SimulationContext`:

```kotlin
object ContextTransformer {
    fun createSimulationContext(
        editingContext: EditingContext,
        processFactory: SimulationProcessFactory
    ): SimulationContext {
        val simulationContext = DefaultSimulationContext(processFactory)

        // Copy network structure with graph wrapping
        copyGraphStructure(editingContext, simulationContext)
        copyConfiguration(editingContext, simulationContext)
        copyInOutList(editingContext, simulationContext)

        simulationContext.freeze() // Make immutable
        return simulationContext
    }
}
```

### Graph Wrapping in copyGraphStructure()

The transformation process wraps each static `TrackBlock` in a `DynamicTrackBlock`:

```kotlin
private fun copyGraphStructure(
    source: EditingContext,
    target: DefaultSimulationContext
) {
    val sourceGraph = source.getGraph() // ExtendedUnorientedGraph<Point, TrackBlock, Segment>

    for (entry in sourceGraph.entrySet()) {
        val staticBlock: TrackBlock = entry.value
        val dynamicBlock = DynamicTrackBlock(staticBlock) // Wrap in dynamic wrapper

        target.getGraph().put(entry.key1, entry.key2, dynamicBlock, entry.segment)
    }
}
```

**Critical Insight:** Transformation creates a **one-to-one mapping** between static and dynamic objects:
- Each `DynamicTrackBlock` wraps exactly one `TrackBlock` (stored in `staticRef`)
- `staticRef` provides access to immutable configuration (length, maxSpeed, endpoints)
- Dynamic state (occupant, reservedFrom, state) managed by wrapper

---

## Usage Patterns

### Pattern 1: Type-Safe Graph Access (Recommended)

```kotlin
// In simulation code
val simulationContext: SimulationContext = // ...
val graph = simulationContext.getGraph() // Type: ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>

val dynamicBlock: DynamicTrackBlock = graph.get(point1, point2) // Type-safe, no cast!

// Direct state access
when (dynamicBlock.getState()) {
    State.FREE -> logger.info("Track available")
    State.RESERVED -> logger.info("Path set up from ${dynamicBlock.reservedFrom}")
    State.OCCUPIED -> logger.info("Train present: ${dynamicBlock.occupant}")
}
```

### Pattern 2: Unwrapping for Static Configuration

When you need static configuration (e.g., in ShuntingLoop), unwrap via `staticRef`:

```kotlin
// From ShuntingLoop.kt (lines 217-224) - Reference implementation
private fun getBlock(
    context: SimulationContext,
    blockName: String,
    loc1: NodeCell,
    loc2: NodeCell
): SimpleTrackBlock {
    val graph = context.getGraph() // Returns ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
    val block = graph.get(loc1, loc2) // Type-safe: returns DynamicTrackBlock

    // Unwrap to get staticRef (immutable configuration)
    val dynamicBlock = block as DynamicTrackBlock
    return dynamicBlock.staticRef as SimpleTrackBlock
}
```

**Why unwrap?** ShuntingLoop needs static configuration (track endpoints, length) to set up paths. The static configuration never changes, so we access it via `staticRef`.

### Pattern 3: Iteration Over All Tracks

```kotlin
// Check state of all tracks in the network
val graph = simulationContext.getGraph()

for (entry in graph.entrySet()) {
    val dynamicBlock: DynamicTrackBlock = entry.value // Type-safe iteration

    if (dynamicBlock.getState() == State.OCCUPIED) {
        println("Track ${entry.key1} → ${entry.key2} occupied by ${dynamicBlock.occupant}")
    }
}
```

---

## DynamicTrackBlock Wrapper

### Responsibilities

The `DynamicTrackBlock` wrapper separates:
- **Static properties** (delegated to wrapped `TrackBlock`):
  - `length()` - Track length in meters
  - `maxSpeed()` - Maximum permitted speed
  - `ends()` - Track endpoints (PathSeparators)
  - `getNextTrackSection()` - Static topology

- **Dynamic state** (managed by wrapper):
  - `occupant` - Current train on track (null if free)
  - `reservedFrom` - Reservation direction (null if not reserved)
  - `getState()` - Current state (FREE/RESERVED/OCCUPIED)

### State Machine

```
         setUpPath()           enter(train)
  FREE ───────────────> RESERVED ───────────────> OCCUPIED
    ^                     |                          |
    |                     | cancelPathSetup()        |
    +─────────────────────+                          |
    |                                                 |
    +─────────────────────────────────────────────────+
                        leave(train)
```

**Invariants:**
- State transitions are validated (e.g., cannot enter FREE track)
- Identity based on `staticRef` (multiple wrappers for same static block are equal)
- Hash code stable across state changes

---

## Benefits Summary

### 1. Type Safety

**Before Issue #277:**
```kotlin
val graph = context.getGraph() // ExtendedUnorientedGraph<Point, TrackBlock, Segment>
val block = graph.get(p1, p2) as TrackBlock // Unchecked cast
val dynamicBlock = context.toDynamic(block) as DynamicTrack // Another unchecked cast
val state = dynamicBlock.getState()
```

**After Issue #277:**
```kotlin
val graph = context.getGraph() // ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
val dynamicBlock = graph.get(p1, p2) // Type-safe, no casts
val state = dynamicBlock.getState()
```

Compiler verifies types at compile time, eliminating `ClassCastException` risks.

### 2. Single-Step Access

Direct access to track state without intermediate wrapper conversion:
- `graph.get(p1, p2).getState()` ✅
- No need for `toDynamic()` call

### 3. State Visibility (AnimatedSim Enabler)

Future AnimatedSim visualization can query track states directly:
```kotlin
// AnimatedSim rendering loop
for (track in simulationContext.getGraph().values()) {
    val color = when (track.getState()) {
        State.FREE -> GREEN
        State.RESERVED -> YELLOW
        State.OCCUPIED -> RED
    }
    drawTrack(track, color)
}
```

### 4. Cleaner Code

Eliminates unchecked casts and intermediate variables in simulation classes:
- Train.kt
- InOutWorker.kt
- Generator.kt
- Interlocking.kt

### 5. Future-Proof Architecture

Foundation for additional dynamic wrappers:
- `DynamicCompoundTrack` (multi-section tracks)
- `DynamicRailSwitch` (already implemented, Issue #91)
- `DynamicInOut` (entry/exit points)

---

## Testing

### Test Coverage

**Unit Tests (15 tests):** DynamicTrackBlockTest.kt
- Construction and property delegation
- State machine transitions (FREE → RESERVED → OCCUPIED → FREE)
- Invalid transition prevention
- Identity and equality based on staticRef

**Transformation Tests (8 tests):** ContextTransformerTest.GraphParameterization
- Graph wrapping verification
- Static → dynamic mapping
- Type safety verification

**Integration Tests (5 tests):** ShuntingLoopOperationalTest.GraphParameterizationTests
- Track state visibility
- Type-safe graph access
- State invariant verification

**Total:** 28 new tests (all passing), 1410 existing tests (zero regressions)

---

## Related Documentation

- `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Wrapper pattern design (Issue #100)
- `docs/GRID_PARAMETERIZATION_ARCHITECTURE.md` - Grid type parameters (Issue #131)
- `docs/CONTEXT_INHERITANCE_INCOMPATIBILITY.md` - Context refactoring rationale (Issue #153)
- `docs/KOTLIN_STYLE_GUIDE.md` - Coding conventions

---

## Migration Guide

### For New Simulation Code

Use type-safe graph access directly:
```kotlin
val graph = simulationContext.getGraph()
val dynamicBlock = graph.get(point1, point2) // Type-safe!
val state = dynamicBlock.getState()
```

### For Existing Simulation Code

No changes required. Existing code using `toDynamic()` continues to work:
```kotlin
val staticBlock = context.getGraph().get(p1, p2).staticRef // Unwrap if needed
val dynamicBlock = context.toDynamic(staticBlock) // Still works
```

### For Code Needing Static Configuration

Unwrap via `staticRef`:
```kotlin
val dynamicBlock = graph.get(p1, p2)
val staticBlock = dynamicBlock.staticRef as SimpleTrackBlock
val length = staticBlock.length() // Access static configuration
```

---

## Future Enhancements

1. **Eliminate toDynamic() method** (Issue #275)
   - Replace all `context.toDynamic(track)` calls with direct graph access
   - Remove deprecated wrapper conversion methods

2. **AnimatedSim Visualization** (Goal 15)
   - Real-time track state visualization using graph parameterization
   - Color-coded tracks: green (FREE), yellow (RESERVED), red (OCCUPIED)

3. **Additional Dynamic Wrappers**
   - `DynamicCompoundTrack` for multi-section track blocks
   - Extend pattern to all track facility types

---

**Implementation complete. All tests passing. Zero regressions. Ready for production use.**
