# Grid Parameterization Design - Executive Summary

**Issue:** #139 - Grid Parameterization Design (Phase 1 of #131)
**Status:** Design Phase Complete - Ready for Implementation Review
**Date:** 2026-01-18

---

## Quick Overview

This design enables the railway network grid to support both **static cells** (editing context) and **dynamic cell wrappers** (simulation context) through:

1. **Type parameterization** of `Array2DMap<T : Cell>`
2. **Visitor pattern** for rendering (replaces reflection)
3. **Identity preservation** via `System.identityHashCode()`
4. **Context transformation** factory for editing→simulation conversion

---

## Core Architecture

### Type Hierarchy

```
Cell (interface)
  ├─ NodeCell (abstract) - Static railway objects
  │    ├─ RailSwitch
  │    ├─ RailSemaphore
  │    └─ InOut
  │
  └─ DynamicPathSeparator (interface) - Simulation wrappers
       ├─ DynamicRailSwitch(static: RailSwitch)
       ├─ DynamicRailSemaphore(static: RailSemaphore)
       └─ DynamicInOut(static: InOut)
```

### Identity Contract

**Critical Guarantee:** Dynamic wrappers maintain stable identity based on wrapped static objects:

```kotlin
// Identity based on wrapped object
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    return when (other) {
        is DynamicRailSwitch -> static === other.static
        is RailSwitch -> static === other
        else -> false
    }
}

override fun hashCode(): Int = System.identityHashCode(static)
```

**Why this works:**
- `System.identityHashCode()` is stable (JVM guarantee)
- Multiple wrappers for same static object are equal
- IdentityHashMap ensures single wrapper instance per static object

---

## Key Design Decisions

### 1. Grid Parameterization

**Change:**
```kotlin
// OLD: Fixed type
class Array2DMap<V> { ... }

// NEW: Type parameter with Cell constraint
class Array2DMap<T : Cell> { ... }
```

**Benefit:** Compile-time type safety for static vs. dynamic grids

### 2. Rendering Strategy: Visitor Pattern

**Change:**
```kotlin
// OLD: Reflection-based dispatch
fun draw(g: Graphics2D, cell: Cell) {
    val method = javaClass.getMethod("draw", Graphics2D::class.java, cell.javaClass)
    method.invoke(this, g, cell)  // Fails with dynamic wrappers
}

// NEW: Visitor pattern with delegation
interface CellRenderVisitor {
    fun visitRailSwitch(switch: RailSwitch, g: Graphics2D)
    fun visitRailSemaphore(semaphore: RailSemaphore, g: Graphics2D)
    // ...
}

fun draw(g: Graphics2D, cell: Cell) {
    cell.acceptRenderer(this, g)  // Type-safe dispatch
}

// Dynamic wrappers delegate to static
class DynamicRailSwitch(val static: RailSwitch) {
    override fun acceptRenderer(visitor: CellRenderVisitor, g: Graphics2D) {
        static.acceptRenderer(visitor, g)  // Delegate to static
    }
}
```

**Benefits:**
- ✓ Works with dynamic wrappers (delegation)
- ✓ Type-safe (compile-time checking)
- ✓ Fast (no reflection overhead)
- ✓ Extensible (add new cell types easily)

### 3. Context Transformation

**Factory method for editing→simulation conversion:**

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

**Identity Preservation:**
- `toDynamic()` uses IdentityHashMap to ensure single wrapper per static object
- Multiple calls return **same wrapper instance**
- Stable identity for collections (Set, Map)

---

## Implementation Phases

| Phase | Effort | Deliverables | Risk |
|-------|--------|--------------|------|
| **1. Grid Parameterization** | 3 days | Parameterized `Array2DMap<T : Cell>`, 10-15 tests | Low |
| **2. Rendering Protocol** | 4 days | Visitor pattern, 15-20 tests | Medium |
| **3. Context Transformation** | 3 days | Factory method, 10-15 tests | Medium |
| **4. Identity Validation** | 2 days | Identity tests, benchmarks | Low |
| **Total** | **12-15 days** | **45-65 new tests** | **Medium** |

---

## Test Impact Analysis

### Affected Tests

| Category | Test Classes | Impact | Changes Required |
|----------|--------------|--------|------------------|
| **Context Tests** | 5 | **HIGH** | Update for parameterized types, add transformation tests |
| **Cell Tests** | 4 | **MEDIUM** | Add rendering protocol tests |
| **Simulation Tests** | 13 | **LOW-MEDIUM** | Verify no ClassCastException |
| **Utility Tests** | 6 | **LOW** | Grid tests transparent to parameterization |

### New Test Categories

1. **Grid Parameterization Tests** (10-15 tests)
   - Type parameter with static cells
   - Type parameter with dynamic wrappers
   - Mixed static/dynamic storage

2. **Context Transformation Tests** (10-15 tests)
   - Editing→simulation conversion
   - Identity preservation
   - Graph structure copying

3. **Identity Preservation Tests** (15-20 tests)
   - Dynamic wrapper equality
   - Hash code stability
   - IdentityHashMap behavior

4. **Rendering Visitor Tests** (15-20 tests)
   - Static cell rendering
   - Dynamic wrapper delegation
   - Visitor dispatch correctness

**Total New Tests:** 45-65

---

## Critical Contracts

### TC1: Grid Transformation Preserves Points

```kotlin
val editingGrid: Array2DMap<Cell> = /* static cells */
val simGrid: Array2DMap<Cell> = /* dynamic wrappers */

editingGrid.keys.forEach { point ->
    assert(simGrid.containsKey(point))  // All points preserved
}
```

### TC2: Wrapper Unwraps to Original

```kotlin
val point = Point(5, 10)
val staticCell = editingGrid[point] as RailSwitch
val dynamicCell = simGrid[point] as DynamicRailSwitch

assert(dynamicCell.static === staticCell)  // Same object reference
```

### TC3: Identity Consistency

```kotlin
val context = DefaultSimulationContext(...)
val static = RailSwitch(...)
val dynamic1 = context.toDynamic(static)
val dynamic2 = context.toDynamic(static)

assert(dynamic1 === dynamic2)  // Same wrapper instance
assert(dynamic1.hashCode() == dynamic2.hashCode())  // Stable hash
```

---

## Trade-offs and Limitations

### Accepted Trade-offs

| Decision | Chosen | Alternative | Rationale |
|----------|--------|-------------|-----------|
| **Equality** | Asymmetric (dynamic→static works) | Symmetric | Simpler, no risk to static classes |
| **Grid Type** | Single parameterized grid | Dual static/dynamic grids | Memory efficient, clearer ownership |
| **Rendering** | Visitor pattern | Reflection | Type safety, performance |
| **Transformation** | Eager (upfront) | Lazy (on-demand) | Predictable, simpler |

### Known Limitations

1. **Asymmetric Equality:**
   ```kotlin
   val static = RailSwitch(...)
   val dynamic = DynamicRailSwitch(static)

   assert(dynamic == static)  // ✓ Works
   // assert(static == dynamic)  // ✗ May not work (static uses Any.equals())
   ```
   **Impact:** Most code uses dynamic wrappers, unidirectional check sufficient.

2. **TrackBlockPart Not Wrapped:**
   Track segments remain static in simulation grid (no dynamic state needed).

3. **Type Erasure:**
   Generic type parameter erased at runtime (use `is` checks, not reflection).

---

## Success Criteria

### Phase Completion Gates

**Phase 1: Grid Parameterization**
- [ ] All 628 existing tests pass
- [ ] 10-15 new parameterization tests added
- [ ] No compilation errors with new type parameters

**Phase 2: Rendering Protocol**
- [ ] All tests pass (including new rendering tests)
- [ ] GUI editor works with visitor pattern
- [ ] Visual regression testing confirms no rendering changes

**Phase 3: Context Transformation**
- [ ] Transformation tests pass (identity, structure)
- [ ] Simulation runs successfully with dynamic grid
- [ ] ShuntingLoop example works end-to-end

**Phase 4: Identity Validation**
- [ ] All identity tests pass
- [ ] Performance benchmarks show no regression
- [ ] Documentation complete

### Final Acceptance

- [ ] **All 662+ tests passing** (628 existing + 45-65 new)
- [ ] **Coverage improvement:** 51% → 55%+
- [ ] **Docker build successful**
- [ ] **Example simulations work** (ShuntingLoop, Train)
- [ ] **GUI editor functional**
- [ ] **Design review approved** by traffic-simulation-expert and railway-civil-engineer

---

## Next Steps

1. **Review Meeting:** Schedule design review with team (traffic-simulation-expert, railway-civil-engineer, java-senior-dev)
2. **GitHub Issues:** Create implementation tasks:
   - #139.1 - Grid Parameterization (Phase 1)
   - #139.2 - Rendering Protocol (Phase 2)
   - #139.3 - Context Transformation (Phase 3)
   - #139.4 - Identity Validation (Phase 4)
3. **TDD Approach:** Write tests first, implement second
4. **Checkpoint Reviews:** Review after each phase completion

---

## References

- **Full Design Document:** `docs/GRID_PARAMETERIZATION_DESIGN.md` (10,000+ words, comprehensive)
- **UML Diagram:** `docs/grid-parameterization-architecture.puml` (PlantUML)
- **Related Issues:**
  - #131 - Grid Parameterization (parent epic)
  - #98 - Context Refactoring (DefaultContext split)
  - #100 - Static/Dynamic Separation (Phase 4)
- **Architecture Docs:**
  - `STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` (Phase 4 pattern)
  - `FACTORY_PATTERN_IMPLEMENTATION.md` (SimulationProcessFactory)
  - `CONTEXT_REFACTORING_DESIGN.md` (Context hierarchy)

---

## Questions or Concerns?

**Contact:**
- **kotlin-tech-lead** (design author) - Architecture questions
- **traffic-simulation-expert** - Simulation correctness
- **railway-civil-engineer** - Railway domain validation
- **java-senior-dev** - Legacy code compatibility

**Decision Authority:**
- **traffic-simulation-expert** has final authority on simulation-related changes (per TEAM.md)
- **kotlin-tech-lead** has authority on Kotlin architecture and code quality

---

*This summary provides a high-level overview. See `GRID_PARAMETERIZATION_DESIGN.md` for complete technical details, code examples, and architectural analysis.*
