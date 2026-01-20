# Context Inheritance Incompatibility Analysis

**Issue:** [#153](https://github.com/bedaHovorka/interlockSim/issues/153) - Context Inheritance Incompatibility: SimulationContext cannot extend EditingContext
**Parent Issue:** [#92](https://github.com/bedaHovorka/interlockSim/issues/92) (Context Refactoring)
**Related:** [#131](https://github.com/bedaHovorka/interlockSim/issues/131) (Grid Parameterization), [#139](https://github.com/bedaHovorka/interlockSim/issues/139) (Grid Parameterization Design), [#136](https://github.com/bedaHovorka/interlockSim/issues/136) (JVM Signature Clash Resolution)
**Author:** Analysis based on architecture review
**Date:** 2026-01-19 (Initial), 2026-01-20 (Updated)
**Status:** Active Implementation - 11 Sub-Issues Created (#158-#168)

---

## Executive Summary

**Problem:** `DefaultSimulationContext` currently extends `DefaultEditingContext`, but this inheritance relationship is **architecturally incompatible** with the Grid Parameterization design (#131/#139) and violates the principle that simulation contexts have **immutable network structures**.

**Core Issues:**
1. **Type parameter conflicts**: Editing and simulation contexts require different parameterized grids (static vs. dynamic cells)
2. **Interface segregation violation**: Simulation contexts should NOT support editing operations (putCell, removeCell, moveCell)
3. **Immutability assumptions**: Simulation network structure must be frozen at initialization time
4. **Graph parameterization**: Both contexts need their own parameterized graph structures

**Recommended Solution:** Refactor to **composition over inheritance** - make `DefaultEditingContext` and `DefaultSimulationContext` sibling implementations that share common functionality through a base class or delegation, each with appropriately parameterized grids.

---

## Table of Contents

1. [Current Architecture](#1-current-architecture)
2. [Problems with Current Inheritance](#2-problems-with-current-inheritance)
3. [Grid Parameterization Impact](#3-grid-parameterization-impact)
4. [Immutable Network Assumption](#4-immutable-network-assumption)
5. [Proposed Solution](#5-proposed-solution)
6. [Implementation Sub-Issues](#6-implementation-sub-issues)
7. [Implementation Phases](#7-implementation-phases)
8. [Migration Strategy](#8-migration-strategy)
9. [Test Impact Analysis](#9-test-impact-analysis)
10. [Key Findings from Related Issues](#10-key-findings-from-related-issues)
11. [References](#11-references)
12. [Decision Points](#12-decision-points)
13. [Implementation Status](#13-implementation-status)

---

## 1. Current Architecture

### 1.1 Context Hierarchy

```
Context<out C : Cell> (interface)
  ├─ EditingContext : Context<Cell> (interface)
  │    └─ DefaultEditingContext (implementation)
  │         - Array2DMap<Cell> with static objects
  │         - Mutable operations: putCell, removeCell, moveCell, joinCells
  │
  └─ SimulationContext : EditingContext (interface)
       └─ DefaultSimulationContext : DefaultEditingContext (implementation)
            - Inherits Array2DMap<Cell> from parent
            - IdentityHashMap<PathSeparator, DynamicPathSeparator> for wrappers
            - Simulation operations: run(), stop(), pathToNextSemaphore()
```

### 1.2 Current Interface Definitions

**Context.kt (lines 19-97):**
```kotlin
interface Context<out C : Cell> {
    fun getRailWayNetGrid(): RailwayNetGrid<C>
    fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment>
    // ...
}
```

**EditingContext.kt (lines 42-100):**
```kotlin
interface EditingContext : Context<Cell> {
    fun putCell(key: Point, cell: NodeCell)
    fun removeCell(key: Point)
    fun moveCell(from: Point, to: Point)
    fun joinCells(key1: Point, key2: Point, trackBlock: TrackBlock)
    fun removeLine(block: TrackBlock)
    var currentMaxSpeed: Double
    var currentTrackLength: Double
    var currentNameString: String
}
```

**SimulationContext.kt (lines 59-242):**
```kotlin
interface SimulationContext : EditingContext {
    fun run()
    fun stop()
    fun pathToNextSemaphore(separator: PathSeparator, next: TrackSection): Path?
    fun toDynamic(separator: PathSeparator): DynamicPathSeparator
    fun toDynamic(track: TrackFacility): DynamicTrack
    // + many more simulation-specific operations
}
```

### 1.3 Implementation Classes

**DefaultEditingContext.kt (lines 82-614):**
```kotlin
open class DefaultEditingContext(
    cols: Int,
    rows: Int
) : EditingContext {
    private val railwayNetGrid: DefaultRailWayNetGrid = DefaultRailWayNetGrid(cols, rows)
    private val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> = HashMapGraph()

    override fun putCell(key: Point, cell: NodeCell) { /* mutable */ }
    override fun removeCell(key: Point) { /* mutable */ }
    override fun moveCell(from: Point, to: Point) { /* mutable */ }
    // ...
}
```

**DefaultSimulationContext.kt (lines 88-830):**
```kotlin
open class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    private val processFactory: SimulationProcessFactory
) : DefaultEditingContext(cols, rows), SimulationContext {
    // Inherits railwayNetGrid from DefaultEditingContext
    private val staticToDynamicMap: MutableMap<PathSeparator, DynamicPathSeparator> = IdentityHashMap()
    private val staticTrackToDynamicMap: MutableMap<TrackFacility, DynamicTrack> = IdentityHashMap()

    override fun run() { /* simulation */ }
    override fun toDynamic(separator: PathSeparator): DynamicPathSeparator { /* wrapper lookup */ }
    // ...
}
```

---

## 2. Problems with Current Inheritance

### 2.1 Type Parameter Conflicts

**Problem:** Editing and simulation contexts need **different grid types**:

| Context Type | Required Grid Type | Cell Contents |
|--------------|-------------------|---------------|
| **EditingContext** | `RailwayNetGrid<Cell>` | Static cells: `RailSwitch`, `RailSemaphore`, `InOut`, `TrackBlockPart` |
| **SimulationContext** | `RailwayNetGrid<Cell>` | Dynamic wrappers: `DynamicRailSwitch`, `DynamicRailSemaphore`, `DynamicInOut`, `TrackBlockPart` |

**Current Issue:**
```kotlin
class DefaultSimulationContext : DefaultEditingContext {
    // PROBLEM: Inherits railwayNetGrid with static cells
    // Cannot change to dynamic cells without breaking inheritance
}
```

**Expected After Grid Parameterization (#131):**
```kotlin
class DefaultEditingContext {
    private val railwayNetGrid: RailwayNetGrid<Cell>
    // Grid contains: RailSwitch, RailSemaphore, InOut (static)
}

class DefaultSimulationContext {
    private val railwayNetGrid: RailwayNetGrid<Cell>
    // Grid SHOULD contain: DynamicRailSwitch, DynamicRailSemaphore, DynamicInOut (wrappers)
    // But inheritance prevents this!
}
```

### 2.2 Interface Segregation Principle Violation

**Problem:** `SimulationContext` extends `EditingContext`, inheriting editing operations that should NOT be available during simulation.

**Violated Operations:**
```kotlin
interface SimulationContext : EditingContext {
    // Should NOT inherit these:
    fun putCell(key: Point, cell: NodeCell)      // ❌ Network is immutable in simulation
    fun removeCell(key: Point)                    // ❌ Cannot remove cells during simulation
    fun moveCell(from: Point, to: Point)          // ❌ Cannot move cells during simulation
    fun joinCells(...)                            // ❌ Cannot join cells during simulation
    fun removeLine(block: TrackBlock)             // ❌ Cannot remove tracks during simulation
}
```

**Architectural Assumption (from user requirement):**
> "Simplification based on assumption: in simulation context is immutable net of dynamic nodes and tracks."

**Consequence:** Allowing editing operations in simulation context:
1. Violates immutability assumption
2. Could corrupt running simulation state
3. Breaks thread-safety guarantees (jDisco expects stable structure)
4. Complicates reasoning about simulation correctness

### 2.3 Liskov Substitution Principle Concerns

**Problem:** `DefaultSimulationContext` cannot safely substitute for `DefaultEditingContext` because:

1. **Preconditions change**: Editing operations should throw exceptions in simulation context
2. **Invariants differ**: Simulation requires immutable network, editing requires mutability
3. **Behavioral differences**: Same method calls should have drastically different effects

**Example Violation:**
```kotlin
fun modifyNetwork(context: EditingContext) {
    context.putCell(Point(5, 10), RailSwitch(...))  // Valid in editing
}

val simContext: SimulationContext = DefaultSimulationContext(...)
simContext.run()  // Start simulation

// Should this work? Currently yes (inherited), but architecturally NO!
modifyNetwork(simContext)  // ❌ Should fail - network is immutable during simulation
```

### 2.4 Graph Parameterization Conflicts

**From #131 Design:**
Both contexts need parameterized graphs with different vertex/edge types:

```kotlin
// Editing Context Graph (static objects)
ExtendedUnorientedGraph<Point, TrackBlock, Segment>
// Vertices: Point (grid coordinates)
// Edges: TrackBlock (static configuration)
// Extensions: Segment (static topology)

// Simulation Context Graph (should use dynamic objects?)
ExtendedUnorientedGraph<Point, TrackBlock, Segment>
// Same types, but semantics differ
// May need: ExtendedUnorientedGraph<Point, DynamicTrack, Segment>?
```

**Issue:** Sharing graph structure through inheritance prevents independent parameterization.

---

## 3. Grid Parameterization Impact

### 3.1 Design Goals from Issue #131/#139

**From GRID_PARAMETERIZATION_DESIGN.md:**

> "The grid should be a type-parameterized container `Array2DMap<T : Cell>` that can hold either static `Cell` instances (editing) or dynamic wrapper instances (simulation), with stable object identity preserved across transformations."

**Key Requirements:**
1. ✅ Type parameterization: `Array2DMap<T : Cell>`
2. ✅ Static cells in editing context
3. ✅ Dynamic wrappers in simulation context
4. ❌ **BLOCKED by inheritance** - cannot have different grid types in parent/child

### 3.2 Context Transformation Factory

**From GRID_PARAMETERIZATION_SUMMARY.md (lines 115-136):**

```kotlin
fun EditingContext.toSimulationContext(
    processFactory: SimulationProcessFactory
): SimulationContext {
    val simContext = DefaultSimulationContext(getCols(), getRows(), processFactory)

    // Transform all cells to dynamic wrappers
    forEach { (point, staticCell) ->
        val dynamicCell = when (staticCell) {
            is RailSwitch -> simContext.toDynamic(staticCell)
            is RailSemaphore -> simContext.toDynamic(staticCell)
            is InOut -> simContext.toDynamic(staticCell)
            else -> staticCell  // TrackBlockPart stays static
        }
        simContext.putCellAt(point.x, point.y, dynamicCell as Cell)
    }

    // Copy graph, track blocks, etc.
    return simContext
}
```

**Problem with Current Inheritance:**
- `putCellAt(x, y, dynamicCell)` writes dynamic wrapper to grid
- But inherited grid is `Array2DMap<Cell>` with static cells already present
- Creates inconsistency: some cells static, some dynamic
- No type-level enforcement of "simulation grid has dynamic cells only"

### 3.3 Visitor Pattern for Rendering

**From GRID_PARAMETERIZATION_SUMMARY.md (lines 79-104):**

Rendering needs to handle both static cells (editing) and dynamic wrappers (simulation):

```kotlin
// Dynamic wrappers delegate rendering to static objects
class DynamicRailSwitch(val static: RailSwitch) {
    override fun acceptRenderer(visitor: CellRenderVisitor, g: Graphics2D) {
        static.acceptRenderer(visitor, g)  // Delegate to static
    }
}
```

**Issue:** Inheritance model assumes single grid type, complicating rendering dispatch. Separate implementations would allow specialized rendering per context type.

---

## 4. Immutable Network Assumption

### 4.1 Architectural Requirement

**From user specification:**
> "Simplification based on assumption: in simulation context is immutable net of dynamic nodes and tracks."

**Implications:**
1. **Network structure frozen at simulation start**
   - No adding/removing cells
   - No modifying track connections
   - No changing graph topology

2. **Only dynamic state changes allowed**
   - Switch configurations (MAIN/BRANCH)
   - Semaphore signals (FREE/STOP/CAUTION)
   - Train positions and velocities
   - Track occupancy

3. **Benefits of immutability**
   - Thread-safety (jDisco single-threaded assumption)
   - Reasoning about simulation correctness
   - Memory efficiency (no copy-on-write needed)
   - Predictable behavior

### 4.2 Current Implementation Contradicts Immutability

**Problem:** `DefaultSimulationContext` inherits ALL editing operations:

```kotlin
class DefaultSimulationContext : DefaultEditingContext {
    // Inherited mutable operations (should NOT be available):
    override fun putCell(key: Point, cell: NodeCell)      // ⚠️ Breaks immutability
    override fun removeCell(key: Point)                    // ⚠️ Breaks immutability
    override fun moveCell(from: Point, to: Point)          // ⚠️ Breaks immutability
    override fun joinCells(...)                            // ⚠️ Breaks immutability
    override fun removeLine(block: TrackBlock)             // ⚠️ Breaks immutability
}
```

**Current "Protection":** None. These methods can be called during simulation, potentially corrupting state.

**Desired Behavior:**
```kotlin
val simContext: SimulationContext = ...
simContext.run()  // Start simulation

// Should throw UnsupportedOperationException:
simContext.putCell(Point(5, 10), RailSwitch(...))
// Error: "Cannot modify network during simulation - structure is immutable"
```

### 4.3 Thread Safety Concerns

**jDisco Discrete Event Simulation Assumptions:**
- Single-threaded execution model
- Stable object references (no structural changes during simulation)
- Sequential event processing

**Risk with Mutable Operations:**
```kotlin
// Thread 1: Simulation running
simContext.run()

// Thread 2: Editor modifies network (currently allowed due to inheritance)
simContext.putCell(Point(5, 10), RailSwitch(...))

// RESULT: Race condition, simulation corruption, undefined behavior
```

**Solution:** Remove editing operations from simulation interface entirely.

---

## 5. Proposed Solution

### 5.1 Refactored Architecture: Composition over Inheritance

**New Hierarchy:**

```
Context<out C : Cell> (interface)
  ├─ EditingContext : Context<Cell> (interface)
  │    └─ DefaultEditingContext (implementation)
  │         - RailwayNetGrid<Cell> with static objects
  │         - Mutable operations: putCell, removeCell, moveCell
  │
  └─ SimulationContext : Context<Cell> (interface)  ⬅️ DOES NOT EXTEND EditingContext
       └─ DefaultSimulationContext (implementation)
            - RailwayNetGrid<Cell> with dynamic wrappers
            - Immutable network (no editing operations)
            - Simulation operations: run(), stop(), pathToNextSemaphore()
```

**Key Changes:**
1. ✅ `SimulationContext` no longer extends `EditingContext`
2. ✅ Both extend only `Context<Cell>`
3. ✅ Each has independent grid implementation
4. ✅ Simulation context does NOT inherit editing operations

### 5.2 Shared Functionality via Composition

**Option A: Base Class with Protected Utilities**

```kotlin
abstract class BaseContext(
    cols: Int,
    rows: Int
) : Context<Cell> {
    protected val changeSupport: PropertyChangeSupport = PropertyChangeSupport(this)
    protected val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> =
        HashMapGraph()

    // Shared utility methods (protected)
    protected fun bresenham(...) { /* algorithm */ }
    protected fun hardJoin(...) { /* track joining logic */ }

    // Common property change support
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        changeSupport.addPropertyChangeListener(listener)
    }
}

class DefaultEditingContext(cols: Int, rows: Int) : BaseContext(cols, rows), EditingContext {
    private val railwayNetGrid: RailwayNetGrid<Cell> = DefaultRailWayNetGrid(cols, rows)
    // Only static cells
}

class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    processFactory: SimulationProcessFactory
) : BaseContext(cols, rows), SimulationContext {
    private val railwayNetGrid: RailwayNetGrid<Cell> = DefaultRailWayNetGrid(cols, rows)
    // Only dynamic wrappers
    private val staticToDynamicMap: IdentityHashMap<PathSeparator, DynamicPathSeparator> = ...
}
```

**Option B: Helper Class via Delegation**

```kotlin
internal class ContextHelper(cols: Int, rows: Int) {
    val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> = ...
    fun bresenham(...) { /* shared algorithm */ }
    fun hardJoin(...) { /* shared logic */ }
}

class DefaultEditingContext(cols: Int, rows: Int) : EditingContext {
    private val helper = ContextHelper(cols, rows)
    private val railwayNetGrid: RailwayNetGrid<Cell> = ...
}

class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    processFactory: SimulationProcessFactory
) : SimulationContext {
    private val helper = ContextHelper(cols, rows)
    private val railwayNetGrid: RailwayNetGrid<Cell> = ...
}
```

**Recommended:** Option A (base class) - cleaner, more maintainable, preserves encapsulation.

### 5.3 Context Transformation

**Factory Method Pattern:**

```kotlin
fun EditingContext.toSimulationContext(
    processFactory: SimulationProcessFactory
): SimulationContext {
    // Create new simulation context (separate from editing context)
    val simContext = DefaultSimulationContext(
        getRailWayNetGrid().getCols(),
        getRailWayNetGrid().getRows(),
        processFactory
    )

    // Transform grid: static cells → dynamic wrappers
    getRailWayNetGrid().forEach { (point, staticCell) ->
        val dynamicCell = when (staticCell) {
            is RailSwitch -> simContext.toDynamic(staticCell)
            is RailSemaphore -> simContext.toDynamic(staticCell)
            is InOut -> simContext.toDynamic(staticCell)
            is TrackBlockPart -> staticCell  // Stays static
            else -> error("Unknown cell type: ${staticCell::class}")
        }
        // Internal putCellAt (bypasses immutability check, only used during construction)
        simContext.internalPutCell(point, dynamicCell as Cell)
    }

    // Copy graph structure (edges remain static TrackBlock references)
    getGraph().forEach { (vertex, edges) ->
        simContext.internalAddGraphVertex(vertex, edges)
    }

    // Freeze network (make immutable)
    simContext.freeze()

    return simContext
}
```

**Key Points:**
- ✅ Editing context remains unchanged (static cells)
- ✅ Simulation context gets new grid with dynamic wrappers
- ✅ Identity preserved (same static objects wrapped)
- ✅ Network frozen after initialization

### 5.4 Immutability Enforcement

**Add Mutability State to SimulationContext:**

```kotlin
class DefaultSimulationContext(...) : SimulationContext {
    private var frozen: Boolean = false

    internal fun internalPutCell(point: Point, cell: Cell) {
        require(!frozen) { "Cannot modify frozen simulation context" }
        railwayNetGrid.put(point, cell)
    }

    internal fun freeze() {
        frozen = true
        logger.info { "Simulation network structure frozen (immutable)" }
    }

    // No public putCell, removeCell, moveCell methods
    // (Not inherited from EditingContext anymore)
}
```

---

## 6. Implementation Sub-Issues

Issue #153 has been broken down into **11 sub-issues** organized across **6 phases**. Each sub-issue represents a distinct deliverable with clear acceptance criteria and dependencies.

### Phase 1: Foundation (2 days)

**[#158](https://github.com/bedaHovorka/interlockSim/issues/158) - Issue #153.2: Design and implement BaseContext abstraction**
- **Priority:** HIGH
- **Estimate:** 2 days
- **Dependencies:** None
- **Description:** Create abstract base class that provides shared functionality for both editing and simulation contexts without forcing inheritance between them.
- **Key Deliverables:**
  - BaseContext abstract class with shared utilities (PropertyChangeSupport, graph management, Bresenham algorithm)
  - Protected methods for common operations (bresenham, hardJoin)
  - Architecture documentation
  - Resolution of JVM signature clash (see #136)

### Phase 2: Context Refactoring (8 days)

**[#159](https://github.com/bedaHovorka/interlockSim/issues/159) - Issue #153.3: Refactor DefaultEditingContext to extend BaseContext**
- **Priority:** HIGH
- **Estimate:** 2 days
- **Dependencies:** #158
- **Description:** Modify DefaultEditingContext to extend BaseContext instead of implementing EditingContext directly.

**[#160](https://github.com/bedaHovorka/interlockSim/issues/160) - Issue #153.4: Refactor DefaultSimulationContext to extend BaseContext (NOT DefaultEditingContext)**
- **Priority:** CRITICAL
- **Estimate:** 3 days
- **Dependencies:** #159
- **Description:** Break inheritance: DefaultSimulationContext extends BaseContext, not DefaultEditingContext.

**[#161](https://github.com/bedaHovorka/interlockSim/issues/161) - Issue #153.4.5: Refactor RailwayNetGridCanvas for Context Type Handling**
- **Priority:** CRITICAL
- **Estimate:** 1 day
- **Dependencies:** #160
- **Description:** Fix GUI code that assumes SimulationContext can be cast to EditingContext.
- **Status:** MUST be completed before #162 (interface hierarchy change)

**[#162](https://github.com/bedaHovorka/interlockSim/issues/162) - Issue #153.5: Remove EditingContext inheritance from SimulationContext interface**
- **Priority:** HIGH
- **Estimate:** 2 days
- **Dependencies:** #160, #161
- **Description:** Change SimulationContext interface to extend only Context<Cell>, not EditingContext.

### Phase 3: Context Transformation (2 days)

**[#163](https://github.com/bedaHovorka/interlockSim/issues/163) - Issue #153.6: Implement Context Transformation Factory**
- **Priority:** MEDIUM
- **Estimate:** 2 days
- **Dependencies:** #162
- **Description:** Create factory method `EditingContext.toSimulationContext()` to transform editing context to simulation context with dynamic wrappers.

### Phase 4: Grid Parameterization Integration (3 days)

**[#164](https://github.com/bedaHovorka/interlockSim/issues/164) - Issue #153.7: Parameterize Context Grids - Static vs Dynamic Separation**
- **Priority:** HIGH
- **Estimate:** 3 days
- **Dependencies:** #163
- **Description:** Complete grid parameterization integration - static cells in editing, dynamic wrappers in simulation.

### Phase 5: Immutability Enforcement (2 days)

**[#165](https://github.com/bedaHovorka/interlockSim/issues/165) - Issue #153.8: Enforce Simulation Network Immutability**
- **Priority:** MEDIUM
- **Estimate:** 2 days
- **Dependencies:** #164
- **Description:** Add immutability enforcement with `frozen` flag in DefaultSimulationContext.

### Phase 6: Testing & Documentation (4 days)

**[#168](https://github.com/bedaHovorka/interlockSim/issues/168) - Issue #153.9: Add Comprehensive Context Refactoring Tests**
- **Priority:** HIGH
- **Estimate:** 2 days
- **Dependencies:** #165
- **Description:** Add 20-30 new tests for context transformation, immutability, and interface segregation.

**[#166](https://github.com/bedaHovorka/interlockSim/issues/166) - Issue #153.10: Update Documentation**
- **Priority:** MEDIUM
- **Estimate:** 1 day
- **Dependencies:** #168
- **Description:** Update CLAUDE.md, architecture docs, and design documents.

**[#167](https://github.com/bedaHovorka/interlockSim/issues/167) - Issue #153.11: Update Architecture Diagrams**
- **Priority:** LOW
- **Estimate:** 1 day
- **Dependencies:** #166
- **Description:** Create/update PlantUML diagrams showing new context hierarchy.

### Implementation Timeline Summary

- **Phase 1 (Foundation):** 2 days
- **Phase 2 (Context Refactoring):** 8 days
- **Phase 3 (Context Transformation):** 2 days
- **Phase 4 (Grid Parameterization):** 3 days
- **Phase 5 (Immutability):** 2 days
- **Phase 6 (Testing & Documentation):** 4 days
- **Total Base Estimate:** 21 days
- **With Buffer (30%):** ~27 days (~5-6 weeks)

### Critical Path

The critical path for this refactoring follows this sequence:
1. #158 (BaseContext design) → **FOUNDATION**
2. #159 (EditingContext refactor) → **START REFACTORING**
3. #160 (SimulationContext refactor) → **BREAK INHERITANCE**
4. #161 (GUI fix) → **PREPARE FOR INTERFACE CHANGE**
5. #162 (Interface hierarchy) → **COMPLETE SEPARATION**

**All subsequent work** (#163-#168) depends on completing this critical path.

### Success Criteria Across All Sub-Issues

- ✅ SimulationContext does NOT extend EditingContext
- ✅ Both contexts extend BaseContext (or implement interfaces independently)
- ✅ No code duplication between contexts
- ✅ Grid parameterization complete (static vs dynamic separation)
- ✅ Immutability enforced for simulation contexts
- ✅ All 687+ tests pass
- ✅ No performance regression
- ✅ Documentation and diagrams updated

---

## 7. Implementation Phases

### Phase 1: Create BaseContext Abstract Class (2 days)

**Tasks:**
1. Extract common functionality from DefaultEditingContext
2. Create `BaseContext` abstract class with:
   - Property change support
   - Graph management utilities
   - Bresenham algorithm
   - Hard join logic
3. Update DefaultEditingContext to extend BaseContext
4. Verify all 628 existing tests pass

**Success Criteria:**
- [ ] All existing tests pass
- [ ] No behavioral changes
- [ ] Code coverage maintained

### Phase 2: Refactor DefaultSimulationContext to Use BaseContext (3 days)

**Tasks:**
1. Change DefaultSimulationContext to extend BaseContext (not DefaultEditingContext)
2. Implement SimulationContext interface directly
3. Add own railwayNetGrid instance (dynamic cells)
4. Add immutability enforcement (`frozen` flag)
5. Update all tests that assume inheritance

**Success Criteria:**
- [ ] DefaultSimulationContext no longer extends DefaultEditingContext
- [ ] All simulation tests pass
- [ ] Editing operations not available in SimulationContext interface

### Phase 3: Update Interface Hierarchy (2 days)

**Tasks:**
1. Change `SimulationContext` to extend `Context<Cell>` only (not EditingContext)
2. Remove editing operation declarations from SimulationContext
3. Update all code that assumes SimulationContext is an EditingContext
4. Add `EditingContext.toSimulationContext()` factory method

**Success Criteria:**
- [ ] Interface hierarchy reflects architectural intent
- [ ] Type checker enforces separation
- [ ] Factory method creates simulation contexts correctly

### Phase 4: Parameterize Grids (Integration with #131) (5 days)

**Tasks:**
1. Apply Grid Parameterization design from #139
2. Specialize grids:
   - `DefaultEditingContext`: `RailwayNetGrid<Cell>` (static cells)
   - `DefaultSimulationContext`: `RailwayNetGrid<Cell>` (dynamic wrappers)
3. Update transformation logic to ensure type safety
4. Implement Visitor pattern for rendering

**Success Criteria:**
- [ ] Grids properly parameterized
- [ ] Type safety enforced at compile time
- [ ] Rendering works for both static and dynamic cells

### Phase 5: Documentation and Testing (2 days)

**Tasks:**
1. Update architecture documentation
2. Add comprehensive tests for:
   - Context transformation
   - Immutability enforcement
   - Grid parameterization
3. Update CLAUDE.md with new architecture

**Success Criteria:**
- [ ] All documentation updated
- [ ] 45-65 new tests added (from #139)
- [ ] Architecture diagrams reflect new structure

**Total Estimated Effort:** 14 days (3 weeks)

---

## 7. Migration Strategy

### 7.1 Code That May Break

**Pattern 1: Assuming SimulationContext is an EditingContext**

```kotlin
// OLD CODE (breaks after refactor):
fun modifyNetwork(context: EditingContext) {
    context.putCell(Point(5, 10), RailSwitch(...))
}

val simContext: SimulationContext = ...
modifyNetwork(simContext)  // ❌ Type error after refactor

// MIGRATION:
// If editing is needed, use EditingContext explicitly
fun modifyNetwork(context: EditingContext) { ... }  // Still works

val editContext: EditingContext = DefaultEditingContext(...)
modifyNetwork(editContext)  // ✓ OK

// Simulation contexts cannot be edited
val simContext: SimulationContext = editContext.toSimulationContext(factory)
// modifyNetwork(simContext)  // ❌ Compile error (correct behavior)
```

**Pattern 2: Casting SimulationContext to EditingContext**

```kotlin
// OLD CODE (breaks):
val simContext: SimulationContext = ...
val editContext = simContext as EditingContext  // ❌ ClassCastException after refactor

// MIGRATION:
// Don't cast - use proper context type
val editContext: EditingContext = DefaultEditingContext(...)
val simContext: SimulationContext = editContext.toSimulationContext(factory)
```

**Pattern 3: Calling Editing Operations on SimulationContext**

```kotlin
// OLD CODE (allowed but wrong):
val simContext: SimulationContext = ...
simContext.putCell(Point(5, 10), RailSwitch(...))  // ⚠️ Breaks immutability assumption

// After refactor: Compile error (correct!)
// putCell not available on SimulationContext interface
```

### 7.2 API Compatibility

**Breaking Changes:**
1. ❌ `SimulationContext` no longer extends `EditingContext`
2. ❌ Cannot call `putCell`, `removeCell`, etc. on `SimulationContext`
3. ❌ Cannot cast `SimulationContext` to `EditingContext`

**Non-Breaking Changes:**
1. ✅ `EditingContext` API unchanged
2. ✅ `Context<Cell>` API unchanged
3. ✅ Simulation-specific operations unchanged

**Migration Path:**
- Use `EditingContext.toSimulationContext()` factory for conversion
- Separate editing phase (build network) from simulation phase (run)
- Update tests that assume inheritance

---

## 8. Test Impact Analysis

### 8.1 Affected Test Classes

| Test Category | Impact | Changes Required |
|---------------|--------|------------------|
| **DefaultEditingContextTest** | LOW | None - still works as before |
| **DefaultSimulationContextTest** | HIGH | Update to not assume EditingContext inheritance |
| **MockSimulationContext** | HIGH | Refactor to not extend DefaultEditingContext |
| **XMLContextFactoryTest** | MEDIUM | Update to use toSimulationContext() factory |
| **ShuntingLoopTest** | LOW | May need minor updates for context creation |
| **InOutWorkerTest** | LOW | May need minor updates for context creation |
| **TrainTest** | LOW | May need minor updates for context creation |

### 8.2 New Tests Required

**Context Transformation Tests (10-15 tests):**
```kotlin
@Test
fun `toSimulationContext creates separate context with dynamic wrappers`() {
    val editContext = DefaultEditingContext(10, 10)
    editContext.putCell(Point(5, 5), RailSwitch(...))

    val simContext = editContext.toSimulationContext(factory)

    // Verify separate contexts
    assertThat(simContext).isNotSameAs(editContext)

    // Verify dynamic wrapper in simulation grid
    val simCell = simContext.getRailWayNetGrid()[Point(5, 5)]
    assertThat(simCell).isInstanceOf<DynamicRailSwitch>()

    // Verify original context unchanged
    val editCell = editContext.getRailWayNetGrid()[Point(5, 5)]
    assertThat(editCell).isInstanceOf<RailSwitch>()
}

@Test
fun `simulation context is immutable after freeze`() {
    val simContext = DefaultSimulationContext(10, 10, factory)
    simContext.freeze()

    // Attempting to modify should fail
    assertThrows<IllegalStateException> {
        simContext.internalPutCell(Point(5, 5), RailSwitch(...))
    }
}
```

**Interface Segregation Tests (5-10 tests):**
```kotlin
@Test
fun `SimulationContext does not expose editing operations`() {
    val simContext: SimulationContext = ...

    // These should not compile:
    // simContext.putCell(...)      // ❌ Compile error
    // simContext.removeCell(...)   // ❌ Compile error
    // simContext.moveCell(...)     // ❌ Compile error
}
```

**Total New Tests:** ~20-30 for this refactoring + 45-65 from Grid Parameterization (#139)

---

## 10. Key Findings from Related Issues

### Issue #136: JVM Signature Clash with Abstract Graph Property

**Status:** ✅ RESOLVED (Closed)
**Impact on BaseContext Design:** CRITICAL

#### Problem Discovered

During Phase 2 of the DefaultContext refactoring (#98), a JVM signature clash was encountered when attempting to implement the BaseContext/AbstractContext pattern. The issue arose from having both:
1. An abstract property: `abstract val graph: EnumUnorientedGraph<RailwayObjectInterface>`
2. An interface method: `override fun getGraph(): EnumUnorientedGraph<RailwayObjectInterface>`

Both map to the same JVM signature `getGraph()`, creating a **platform declaration clash**.

#### Investigation Results

- **Codebase analysis**: 32 usages of `getGraph()` exist across the codebase
- **Access pattern**: No direct `.graph` property access is used anywhere
- **Usage**: All code uses the method call syntax `context.getGraph()`

#### Resolution (Implemented)

**Option A (Chosen):** Remove abstract property from AbstractContext

The resolution removed the abstract `graph` property from the base class and requires each concrete subclass to define its own private graph field with a getter method:

```kotlin
// AbstractContext/BaseContext: NO abstract property
abstract class BaseContext : Context<Cell> {
    // ❌ NO: abstract val graph: EnumUnorientedGraph<...>
    // ✅ YES: Only abstract method from interface
    abstract override fun getGraph(): EnumUnorientedGraph<...>
}

// Concrete subclasses: Private field + getter
class DefaultEditingContext : BaseContext, EditingContext {
    private val graph = EnumUnorientedGraph<...>()
    override fun getGraph() = graph
}

class DefaultSimulationContext : BaseContext, SimulationContext {
    private val graph = EnumUnorientedGraph<...>()
    override fun getGraph() = graph
}
```

#### Implications for Issue #153

This resolution **directly impacts the BaseContext design** proposed in Section 5.2 Option A:

1. ✅ **BaseContext abstract class is still viable** - we can use it
2. ⚠️ **Cannot use abstract properties** - must use abstract methods
3. ✅ **Each context must define own graph field** - acceptable for this refactoring
4. ✅ **No code duplication concern** - graph initialization is simple (`HashMapGraph()`)

**Updated BaseContext Pattern:**

```kotlin
abstract class BaseContext(
    cols: Int,
    rows: Int
) : Context<Cell> {
    protected val changeSupport: PropertyChangeSupport = PropertyChangeSupport(this)

    // ❌ CANNOT DO THIS (JVM signature clash):
    // protected abstract val graph: ExtendedUnorientedGraph<Point, TrackBlock, Segment>

    // ✅ MUST DO THIS INSTEAD:
    abstract override fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment>

    // Shared utility methods (protected)
    protected fun bresenham(...) { /* algorithm */ }
    protected fun hardJoin(...) { /* track joining logic */ }
}

class DefaultEditingContext(cols: Int, rows: Int) : BaseContext(cols, rows), EditingContext {
    // Each subclass defines its own private graph field
    private val graph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> = HashMapGraph()
    override fun getGraph() = graph
}

class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    processFactory: SimulationProcessFactory
) : BaseContext(cols, rows), SimulationContext {
    // Each subclass defines its own private graph field
    private val graph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> = HashMapGraph()
    override fun getGraph() = graph
}
```

#### Impact on Section 5.2 Recommendations

The recommendation for **Option A (Base Class)** remains valid with this modification:
- ✅ Still cleaner than Option B (Helper Class via Delegation)
- ✅ Still maintains encapsulation
- ✅ Still provides code reuse for shared utilities
- ⚠️ Minor adjustment: Each subclass must define `private val graph` field

**No architectural change needed** - just implementation detail adjustment.

#### Verification

Issue #136 verification requirements apply to #158 (BaseContext implementation):
- [ ] Code compiles without JVM signature clash
- [ ] All tests pass (687+ tests)
- [ ] No behavioral changes
- [ ] Clean architecture maintained (no workarounds needed)

---

## 11. References

### Related Issues

- **[#153](https://github.com/bedaHovorka/interlockSim/issues/153)** - THIS ISSUE: Context Inheritance Incompatibility
- **[#92](https://github.com/bedaHovorka/interlockSim/issues/92)** - Parent issue (Context Refactoring)
- **[#136](https://github.com/bedaHovorka/interlockSim/issues/136)** - ✅ RESOLVED: JVM Signature Clash with Abstract Graph Property (CRITICAL for BaseContext design)
- **[#131](https://github.com/bedaHovorka/interlockSim/issues/131)** - Grid Parameterization (parent epic)
- **[#139](https://github.com/bedaHovorka/interlockSim/issues/139)** - Grid Parameterization Design (Phase 1 of #131)
- **[#98](https://github.com/bedaHovorka/interlockSim/issues/98)** - Context Refactoring (DefaultContext split)
- **[#100](https://github.com/bedaHovorka/interlockSim/issues/100)** - Static/Dynamic Separation (Phase 4)
- **[#149](https://github.com/bedaHovorka/interlockSim/pull/149)** - Add type parameter to RailwayNetGrid interface
- **[#151](https://github.com/bedaHovorka/interlockSim/pull/151)** - Add type parameters to Context hierarchy

### Implementation Sub-Issues (#153.X)

- **[#158](https://github.com/bedaHovorka/interlockSim/issues/158)** - #153.2: Design and implement BaseContext abstraction
- **[#159](https://github.com/bedaHovorka/interlockSim/issues/159)** - #153.3: Refactor DefaultEditingContext to extend BaseContext
- **[#160](https://github.com/bedaHovorka/interlockSim/issues/160)** - #153.4: Refactor DefaultSimulationContext to extend BaseContext
- **[#161](https://github.com/bedaHovorka/interlockSim/issues/161)** - #153.4.5: Refactor RailwayNetGridCanvas for Context Type Handling
- **[#162](https://github.com/bedaHovorka/interlockSim/issues/162)** - #153.5: Remove EditingContext inheritance from SimulationContext interface
- **[#163](https://github.com/bedaHovorka/interlockSim/issues/163)** - #153.6: Implement Context Transformation Factory
- **[#164](https://github.com/bedaHovorka/interlockSim/issues/164)** - #153.7: Parameterize Context Grids - Static vs Dynamic Separation
- **[#165](https://github.com/bedaHovorka/interlockSim/issues/165)** - #153.8: Enforce Simulation Network Immutability
- **[#168](https://github.com/bedaHovorka/interlockSim/issues/168)** - #153.9: Add Comprehensive Context Refactoring Tests
- **[#166](https://github.com/bedaHovorka/interlockSim/issues/166)** - #153.10: Update Documentation
- **[#167](https://github.com/bedaHovorka/interlockSim/issues/167)** - #153.11: Update Architecture Diagrams

### Design Documents

- **GRID_PARAMETERIZATION_DESIGN.md** - Comprehensive grid parameterization architecture
- **GRID_PARAMETERIZATION_SUMMARY.md** - Executive summary of grid design
- **STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md** - Phase 4 wrapper pattern
- **CONTEXT_REFACTORING_DESIGN.md** - Context hierarchy design
- **FACTORY_PATTERN_IMPLEMENTATION.md** - SimulationProcessFactory pattern

### Architecture Principles

- **Interface Segregation Principle (ISP)** - Clients should not depend on interfaces they don't use
- **Liskov Substitution Principle (LSP)** - Subtypes must be substitutable for base types
- **Composition over Inheritance** - Prefer composition to avoid tight coupling
- **Immutability** - Simulation network structure is frozen at initialization

---

## 12. Decision Points

### 12.1 Questions for Architecture Review

1. **Shared Functionality Approach:** ✅ **RESOLVED via #136**
   - Option A: BaseContext abstract class (recommended)
   - Option B: ContextHelper delegation class
   - **Decision:** ✅ **Option A - BaseContext abstract class**
   - **Resolution:** Issue #136 proved this approach is viable with minor modification (no abstract properties due to JVM signature clash, each subclass defines private graph field)
   - **Status:** Ready for implementation in #158

2. **Graph Parameterization:**
   - Should simulation context have `ExtendedUnorientedGraph<Point, DynamicTrack, Segment>`?
   - Or keep `ExtendedUnorientedGraph<Point, TrackBlock, Segment>` and wrap on access?
   - **Decision:** ⏳ **DEFERRED to Phase 4 (#164)**
   - **Recommendation:** Keep `ExtendedUnorientedGraph<Point, TrackBlock, Segment>` initially for backward compatibility, evaluate during #164 implementation

3. **Migration Strategy:**
   - Big-bang refactor (all at once)
   - Incremental (deprecate, migrate, remove)
   - **Decision:** ✅ **Phased incremental approach (6 phases over 5-6 weeks)**
   - **Rationale:** Sub-issues #158-#168 define clear incremental path with minimal risk
   - **Critical path:** #158 → #159 → #160 → #161 → #162 (foundation before new features)

4. **Immutability Enforcement:**
   - Compile-time (remove operations from interface) ✓ Recommended
   - Runtime (throw exceptions if frozen)
   - Both (defense in depth)
   - **Decision:** ⏳ **DEFERRED to #165 (Phase 5)**
   - **Recommendation:** Both (compile-time via interface segregation + runtime frozen flag for defense in depth)

### 12.2 Approval Status

- **traffic-simulation-expert** - ⏳ Pending (simulation correctness, immutability assumptions)
- **kotlin-tech-lead** - ⏳ Pending (architecture, type parameterization, design patterns)
- **java-senior-dev** - ⏳ Pending (legacy code compatibility, migration risks)
- **railway-civil-engineer** - ⏳ Pending (domain validation, network immutability requirements)

---

## 13. Implementation Status

### Current Phase: Foundation (Phase 1)

**Next Action:** Begin implementation of **#158 (BaseContext abstraction)**

### Issue Status Summary

| Phase | Sub-Issue | Title | Status | Priority | Est. Days |
|-------|-----------|-------|--------|----------|-----------|
| 1 | #158 | BaseContext abstraction | 🔵 OPEN | HIGH | 2 |
| 2 | #159 | Refactor DefaultEditingContext | 🔵 OPEN | HIGH | 2 |
| 2 | #160 | Refactor DefaultSimulationContext | 🔵 OPEN | CRITICAL | 3 |
| 2 | #161 | Fix RailwayNetGridCanvas | 🔵 OPEN | CRITICAL | 1 |
| 2 | #162 | Remove EditingContext inheritance | 🔵 OPEN | HIGH | 2 |
| 3 | #163 | Context Transformation Factory | 🔵 OPEN | MEDIUM | 2 |
| 4 | #164 | Parameterize Context Grids | 🔵 OPEN | HIGH | 3 |
| 5 | #165 | Enforce Network Immutability | 🔵 OPEN | MEDIUM | 2 |
| 6 | #168 | Add Refactoring Tests | 🔵 OPEN | HIGH | 2 |
| 6 | #166 | Update Documentation | 🔵 OPEN | MEDIUM | 1 |
| 6 | #167 | Update Architecture Diagrams | 🔵 OPEN | LOW | 1 |

**Total Progress:** 0/11 complete (0%)
**Estimated Remaining:** 21 days (base) + 6 days buffer = 27 days

### Key Milestones

- [ ] **Milestone 1 (Phase 1-2 Complete):** BaseContext created, both contexts refactored (Day 10)
- [ ] **Milestone 2 (Phase 3-4 Complete):** Context transformation and grid parameterization (Day 15)
- [ ] **Milestone 3 (Phase 5-6 Complete):** Immutability enforced, testing complete (Day 21)
- [ ] **Final Release:** All sub-issues closed, documentation updated (Day 27)

---

**Status:** ✅ **Active Implementation** - Document updated with findings from #136. Ready to proceed with #158 (BaseContext implementation).

**Document History:**
- 2026-01-19: Initial pre-analysis created
- 2026-01-20: Updated with #153 sub-issues (#158-#168), #136 findings (JVM signature clash resolution), implementation timeline, and critical path analysis
