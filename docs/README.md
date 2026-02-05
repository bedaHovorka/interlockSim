# Grid Parameterization Documentation Index

**Purpose:** Navigation guide for grid parameterization design and implementation documents
**Issue:** [#139](https://github.com/bedaHovorka/interlockSim/issues/139) - Grid Parameterization Design (Phase 1 of [#131](https://github.com/bedaHovorka/interlockSim/issues/131))
**Status:** Design Phase Complete - Awaiting Review
**Created:** 2026-01-18
**Authors:** kotlin-tech-lead, traffic-simulation-expert

---

## Quick Navigation

### Architecture Diagrams
- **[Context Hierarchy](diagrams/context-hierarchy.svg)** - Context class inheritance structure
- **[Context Transformation](diagrams/context-transformation.svg)** - Editing → Simulation workflow
- **[Factory Pattern](diagrams/factory-pattern.svg)** - Factory relationships and DI
- **[Grid Parameterization](grid-parameterization-architecture.puml)** - Grid type hierarchy
- **[Grid Transformation Flow](grid-transformation-flow.puml)** - Transformation process

### Start Here
- **[GRID_PARAMETERIZATION_INDEX.md](./GRID_PARAMETERIZATION_INDEX.md)** - Complete navigation guide and quick reference

### For Quick Understanding (5-10 minutes)
- **[GRID_PARAMETERIZATION_SUMMARY.md](./GRID_PARAMETERIZATION_SUMMARY.md)** - Executive summary with key decisions and tables

### For Implementation (45-60 minutes)
- **[GRID_PARAMETERIZATION_DESIGN.md](./GRID_PARAMETERIZATION_DESIGN.md)** - Complete architectural design with type hierarchy, identity contracts, rendering protocol, and implementation roadmap

### For Simulation Experts
- **[GRID_TRANSFORMATION_DESIGN.md](./GRID_TRANSFORMATION_DESIGN.md)** - Grid transformation algorithm, path reconstruction, simulation correctness, and safety invariants

### Visual Diagrams

#### Context Architecture (Issue #153)
- **[diagrams/context-hierarchy.puml](diagrams/context-hierarchy.puml)** - Context class hierarchy (PlantUML)
- **[diagrams/context-transformation.puml](diagrams/context-transformation.puml)** - Context transformation flow (PlantUML)
- **[diagrams/factory-pattern.puml](diagrams/factory-pattern.puml)** - Factory pattern integration (PlantUML)
- See [diagrams/README.md](diagrams/README.md) for details

#### Grid Parameterization (Issue #131)
- **[grid-parameterization-architecture.puml](./grid-parameterization-architecture.puml)** - Type hierarchy and architecture diagram (PlantUML)
- **[grid-transformation-flow.puml](./grid-transformation-flow.puml)** - Transformation flow diagram (PlantUML)

---

## Documentation Overview

This design deliverable provides comprehensive architecture documentation for implementing grid parameterization in the interlockSim railway simulator. The documentation is organized into complementary perspectives:

### Architecture Perspective (kotlin-tech-lead)

**Focus:** Type hierarchy, interfaces, class relationships, identity contracts, rendering patterns

**Key Documents:**
1. **GRID_PARAMETERIZATION_DESIGN.md** (56 KB)
   - Current architecture analysis
   - Type hierarchy design with UML
   - Identity preservation contracts (===, ==, hashCode)
   - CellRenderer abstraction strategy (Visitor pattern)
   - Context transformation design
   - Test impact analysis (662 existing tests, 45-65 new tests)
   - 4-phase implementation roadmap (12-15 days)
   - Architectural trade-offs

2. **GRID_PARAMETERIZATION_SUMMARY.md** (11 KB)
   - Executive summary for quick understanding
   - Key architectural decisions in table format
   - Success criteria and next steps

3. **grid-parameterization-architecture.puml** (9 KB)
   - Complete type hierarchy diagram
   - Static vs. dynamic separation
   - Grid infrastructure with type parameters
   - Rendering infrastructure (Visitor pattern)

4. **grid-transformation-flow.puml** (4 KB)
   - Step-by-step transformation flow
   - Decision points and parallel processes

### Simulation Perspective (traffic-simulation-expert)

**Focus:** Grid transformation algorithm, path reconstruction, physics preservation, safety invariants

**Key Document:**
- **GRID_TRANSFORMATION_DESIGN.md** (33 KB)
  - Grid transformation algorithm with pseudocode (O(n+e) complexity)
  - Path reconstruction strategy for simulation
  - 5 correctness invariants (lengths, speeds, topology, states, identity)
  - 5 railway safety invariants (SI-1 through SI-5)
  - Physics preservation analysis (Train equations unaffected)
  - Timing preservation guarantee (transformation before jDisco activation)
  - jDisco integration considerations
  - Risk assessment and edge cases
  - Example scenarios (vyhybna.xml walkthrough)
  - Implementation recommendations

### Navigation Guide

**GRID_PARAMETERIZATION_INDEX.md** provides:
- Document structure overview
- Navigation by task, role, and implementation phase
- Quick reference cards (identity, rendering, transformation)
- Review checklist
- Approval signatures section

---

## How to View PlantUML Diagrams

### Option 1: Online (No Installation)
1. Copy the contents of `.puml` file
2. Go to https://www.plantuml.com/plantuml/uml/
3. Paste and view

### Option 2: VS Code (Recommended for Development)
1. Install "PlantUML" extension by jebbs
2. Open `.puml` file in VS Code
3. Press `Alt+D` to preview

### Option 3: Local Rendering
```bash
# Install PlantUML (requires Java)
brew install plantuml  # macOS
sudo apt install plantuml  # Linux

# Render diagram
plantuml docs/grid-parameterization-architecture.puml
# Output: grid-parameterization-architecture.png
```

---

## Design Highlights

### Core Architecture Decisions

1. **Type Hierarchy**
   - Static objects: `RailSwitch`, `RailSemaphore`, `InOut` (immutable configuration)
   - Dynamic wrappers: `DynamicRailSwitch`, `DynamicRailSemaphore`, `DynamicInOut` (mutable state)
   - Identity based on wrapped static object

2. **Identity Preservation**
   ```kotlin
   // Uses System.identityHashCode() for stable hash
   override fun hashCode(): Int = System.identityHashCode(static)

   // IdentityHashMap ensures single wrapper per static object
   private val dynamicMap: IdentityHashMap<PathSeparator, DynamicPathSeparator>
   ```

3. **Rendering Strategy**
   - Replaces reflection with Visitor pattern
   - Type-safe dispatch at compile time
   - Dynamic wrappers delegate to static objects

4. **Grid Parameterization**
   ```kotlin
   // Type parameterized grid
   class Array2DMap<T : Cell> { ... }

   // Editing context with static cells
   class DefaultEditingContext : AbstractRailwayNetGrid<Cell>

   // Simulation context with dynamic wrappers
   class DefaultSimulationContext : AbstractRailwayNetGrid<Cell>
   ```

5. **Transformation Algorithm**
   - Time complexity: O(n + e) where n=cells, e=edges
   - Memory overhead: ~1.5 KB for typical network
   - Three phases: separators → tracks → validation
   - Completes before jDisco scheduler activation

---

## Implementation Roadmap

| Phase | Days | Deliverables | Risk |
|-------|------|--------------|------|
| 1. Grid Parameterization | 3 | Parameterized `Array2DMap<T : Cell>`, 10-15 tests | Low |
| 2. Rendering Protocol | 4 | Visitor pattern, 15-20 tests | Medium |
| 3. Context Transformation | 3 | Factory method, 10-15 tests | Medium |
| 4. Identity Validation | 2 | Identity tests, benchmarks | Low |

**Total:** 12-15 days

---

## Test Impact Analysis

**Existing Tests:** 662 total (628 passing, 34 skipped)

**Affected Categories:**
- **HIGH Impact:** Context tests (~80 tests) - require updates
- **MEDIUM Impact:** Cell tests (~50 tests) - add rendering protocol
- **LOW-MEDIUM Impact:** Simulation tests (~150 tests) - verify no ClassCastException

**New Tests Needed:** 45-65 tests across 4 categories

---

## Success Criteria

Grid transformation implementation is complete when:

- [ ] All existing tests pass (662 tests)
- [ ] New transformation tests pass (45-65 tests)
- [ ] Golden output unchanged for vyhybna.xml
- [ ] ShuntingLoop example produces same results
- [ ] Code coverage ≥ 51% (baseline maintained)
- [ ] All documentation updated

---

## Required Approvals

Before implementation begins, this design requires approval from:

- [ ] **kotlin-tech-lead** - Design completeness
- [ ] **traffic-simulation-expert** - Simulation correctness
- [ ] **railway-civil-engineer** - Domain correctness
- [ ] **java-senior-dev** - Legacy compatibility

---

## Related Documentation

### Prerequisites
1. `STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Phase 4 wrapper pattern
2. `CONTEXT_REFACTORING_DESIGN.md` - Context hierarchy design
3. `FACTORY_PATTERN_IMPLEMENTATION.md` - SimulationProcessFactory pattern

### Related Issues
- [#131](https://github.com/bedaHovorka/interlockSim/issues/131) - Grid Parameterization (parent epic)
- [#98](https://github.com/bedaHovorka/interlockSim/issues/98) - Context Refactoring
- [#100](https://github.com/bedaHovorka/interlockSim/issues/100) - Static/Dynamic Separation

---

## Questions or Feedback?

**During Review:** Comment on GitHub issue [#139](https://github.com/bedaHovorka/interlockSim/issues/139)

**During Implementation:** Refer to [GRID_PARAMETERIZATION_INDEX.md](./GRID_PARAMETERIZATION_INDEX.md) for navigation

---

**Document Version:** 1.0
**Last Updated:** 2026-01-18
**Status:** Design Phase Complete - Awaiting Review
