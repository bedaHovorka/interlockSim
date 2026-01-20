# Grid Transformation Algorithm Design Document

**Issue:** #139 - Grid Parameterization Design (Phase 1 of #131)
**Date:** 2026-01-18
**Author:** traffic-simulation-expert (Claude Opus 4.5)
**Status:** DESIGN PHASE - No implementation yet

## Executive Summary

This document provides comprehensive design documentation for the grid transformation algorithm that converts an editing-time static grid into a simulation-time dynamic grid. The transformation is a critical step in enabling parameterized railway network simulations where network structure can be configured independently of simulation state.

**Key Design Principles:**
1. **Separation of Concerns** - Static topology vs. dynamic state
2. **Correctness First** - Railway safety invariants must be preserved
3. **Deterministic Transformation** - Same input always produces same output
4. **Fail-Fast Validation** - Catch configuration errors before simulation starts

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Current Architecture Analysis](#2-current-architecture-analysis)
3. [Grid Transformation Algorithm](#3-grid-transformation-algorithm)
4. [Path Reconstruction Strategy](#4-path-reconstruction-strategy)
5. [Simulation Correctness Verification](#5-simulation-correctness-verification)
6. [Safety Invariants](#6-safety-invariants)
7. [jDisco Integration Considerations](#7-jdisco-integration-considerations)
8. [Risk Assessment](#8-risk-assessment)
9. [Example Scenarios](#9-example-scenarios)
10. [Implementation Recommendations](#10-implementation-recommendations)

---

## 1. Problem Statement

### 1.1 Current Situation

The current architecture already implements static/dynamic separation for individual objects:

**Implemented Wrappers (Phase 4 - Issue #100):**
- `RailSwitch` -> `DynamicRailSwitch` (manages configuration: MAIN/BRANCH, lock state)
- `RailSemaphore` -> `DynamicRailSemaphore` (manages signal state: STOP/GO)
- `InOut` -> `DynamicInOut` (manages entry/exit point state)
- `TrackFacility` -> `DynamicTrack` (manages track state: FREE/RESERVED/OCCUPIED)

**Current Transformation Point:**
The transformation currently happens at `DefaultSimulationContext.run()` via `initializeDynamicMapping()`:

```kotlin
// DefaultSimulationContext.kt lines 312-393
private fun initializeDynamicMapping() {
    // Creates DynamicRailSwitch for each RailSwitch
    // Creates DynamicRailSemaphore for each RailSemaphore
    // Creates DynamicInOut for each InOut
    // Populates staticToDynamicMap (IdentityHashMap)
}
```

### 1.2 What is Missing for Parameterized Grids

The current implementation transforms objects **individually**. For parameterized grids (#131), we need:

1. **Grid-Level Transformation** - Transform the entire `Array2DMap<Cell>` structure
2. **Parameterized Network Structure** - Allow grid dimensions and cell positions to vary
3. **Path-Aware Transformation** - Ensure paths work correctly after transformation
4. **Topology Validation** - Verify network connectivity after parameterization

### 1.3 Goals of This Design

1. Document the complete grid transformation algorithm
2. Define how paths transition from static to dynamic references
3. Ensure simulation physics and timing are preserved
4. Maintain all railway safety invariants (SI-1 through SI-5)
5. Provide jDisco-compatible transformation semantics

---

## 2. Current Architecture Analysis

### 2.1 Grid Structure

The railway network grid is stored in `Array2DMap<Cell>`:

```kotlin
// util/Array2DMap.kt
class Array2DMap<V> : AbstractMap<Point, V>() {
    private val array = RelocableList<RelocableList<V>>()
    private val _keys: TreeSet<Point> = TreeSet(POINT_COMPARATOR)

    fun get(x: Int, y: Int): V?
    fun put(key: Point, value: V): V?
    fun getRow(y: Int): List<V>
}
```

**Characteristics:**
- Sparse 2D grid (not all positions occupied)
- Sorted by Point (Y first, then X) via `POINT_COMPARATOR`
- Supports efficient iteration and lookup
- Contains `NodeCell` objects (RailSwitch, RailSemaphore, InOut) and `TrackBlockPart` objects

### 2.2 Cell Types in Grid

```
Grid Cell Types:
+------------------+--------------------+-------------------+
| Type             | Interface          | Dynamic Wrapper   |
+------------------+--------------------+-------------------+
| RailSwitch       | PathSeparator      | DynamicRailSwitch |
| RailSemaphore    | OrientedPathSep    | DynamicRailSem    |
| InOut            | OrientedPathSep    | DynamicInOut      |
| TrackBlockPart   | Cell               | (none needed)     |
+------------------+--------------------+-------------------+
```

**Important:** `TrackBlockPart` cells are intermediate visual cells between NodeCells. They do not have dynamic state - they reference a `TrackBlock` which has state.

### 2.3 Graph Structure

The track topology is stored separately in `ExtendedUnorientedGraph`:

```kotlin
// DefaultEditingContext.kt
private val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment>
```

**Edge Structure:**
- Vertices: Grid positions (`Point`)
- Edges: `TrackBlock` objects (SimpleTrackBlock)
- Edge labels: `Cell.Segment` (A, B, C, D, E, F, G, H - 8 directions)

### 2.4 Current Mapping Infrastructure

```kotlin
// DefaultSimulationContext.kt
private val staticToDynamicMap: MutableMap<PathSeparator, DynamicPathSeparator> = IdentityHashMap()
private val staticTrackToDynamicMap: MutableMap<TrackFacility, DynamicTrack> = IdentityHashMap()
```

**Key Design Decision:** Uses `IdentityHashMap` to ensure:
- Identity-based mapping (`===` not `==`)
- Stable wrapper identity across the simulation
- O(1) lookup for static-to-dynamic conversion

---

## 3. Grid Transformation Algorithm

### 3.1 Algorithm Overview

```
Grid Transformation Pipeline:
+------------------+     +-------------------+     +------------------+
|   Static Grid    | --> | Transform Objects | --> |  Dynamic Grid    |
| (Array2DMap<Cell)|     | (initializeDynamic|     | (Logical - maps) |
|                  |     |     Mapping())    |     |                  |
+------------------+     +-------------------+     +------------------+
         |                        |                        |
         v                        v                        v
   NodeCells only          Create wrappers         staticToDynamicMap
   (RailSwitch,            for each cell           staticTrackToDynamicMap
    RailSemaphore,         type
    InOut)
```

### 3.2 Detailed Algorithm (Pseudocode)

```
ALGORITHM: GridTransformation

INPUT:
  - staticGrid: Array2DMap<Cell>  // From EditingContext
  - trackGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment>

OUTPUT:
  - staticToDynamicMap: IdentityHashMap<PathSeparator, DynamicPathSeparator>
  - staticTrackToDynamicMap: IdentityHashMap<TrackFacility, DynamicTrack>

PROCEDURE initializeDynamicMapping():
    // Phase 1: Create separator wrappers
    FOR EACH (position, cell) IN staticGrid:
        IF cell IS PathSeparator:
            dynamicWrapper = createDynamicWrapper(cell)
            staticToDynamicMap[cell] = dynamicWrapper

            // Special case: InOut has embedded semaphores
            IF cell IS InOut:
                staticToDynamicMap[cell.inSemaphore] = dynamicWrapper.inSemaphore
                staticToDynamicMap[cell.outSemaphore] = dynamicWrapper.outSemaphore

    // Phase 2: Create track wrappers
    FOR EACH trackBlock IN trackGraph.values():
        dynamicTrack = DynamicTrack(trackBlock)
        staticTrackToDynamicMap[trackBlock] = dynamicTrack

        // Handle internal sections if CompoundTrackBlock
        mapInternalSections(trackBlock)

    // Phase 3: Validate completeness
    validateDynamicMapping()

    RETURN (staticToDynamicMap, staticTrackToDynamicMap)

FUNCTION createDynamicWrapper(cell: PathSeparator) -> DynamicPathSeparator:
    MATCH cell:
        IS RailSwitch  -> RETURN DynamicRailSwitch(cell)
        IS RailSemaphore -> RETURN createDynamicInstance(cell)
        IS InOut -> RETURN createDynamicInOut(cell)
        DEFAULT -> THROW IllegalStateException("Unknown cell type")

FUNCTION createDynamicInOut(inOut: InOut) -> DynamicInOut:
    inSemaphore = createDynamicInstance(inOut.getInSemaphore())
    outSemaphore = createConstantInstance(inOut.getOutSemaphore(), Signal.FREE)
    RETURN DynamicInOut(inOut, inSemaphore, outSemaphore)

PROCEDURE mapInternalSections(trackBlock: TrackBlock):
    visited = Set<TrackSection>()

    FOR EACH end IN trackBlock.ends():
        currentSection = trackBlock.getNextTrackSection(end, null)

        WHILE currentSection != null AND currentSection NOT IN visited:
            visited.add(currentSection)

            IF currentSection === trackBlock:
                BREAK  // SimpleTrackBlock pattern

            IF currentSection IS TrackFacility:
                IF currentSection NOT IN staticTrackToDynamicMap:
                    dynamicSection = DynamicTrack(currentSection)
                    staticTrackToDynamicMap[currentSection] = dynamicSection

            nextSeparator = currentSection.getSecondEnd(end)
            currentSection = trackBlock.getNextTrackSection(nextSeparator, currentSection)

PROCEDURE validateDynamicMapping():
    unmappedSeparators = []
    unmappedTracks = []

    // Check all grid cells
    FOR EACH (position, cell) IN staticGrid:
        IF cell IS PathSeparator AND cell NOT IN staticToDynamicMap:
            unmappedSeparators.add("${cell.type} at $position")

    // Check all tracks
    FOR EACH trackBlock IN trackGraph.values():
        IF trackBlock NOT IN staticTrackToDynamicMap:
            unmappedTracks.add("TrackBlock ${trackBlock.id}")

    IF unmappedSeparators.isNotEmpty() OR unmappedTracks.isNotEmpty():
        THROW IllegalStateException("Dynamic mapping incomplete: $details")

    LOG "Validation passed: ${staticToDynamicMap.size} separators, ${staticTrackToDynamicMap.size} tracks"
```

### 3.3 Time Complexity Analysis

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Grid iteration | O(n) | n = number of cells |
| Wrapper creation | O(1) | Per cell |
| Map insertion | O(1) | IdentityHashMap |
| Internal section mapping | O(m) | m = sections per block |
| Validation | O(n + e) | n = cells, e = edges |
| **Total** | **O(n + e)** | Linear in network size |

### 3.4 Memory Overhead

```
Memory Analysis:
- DynamicRailSwitch: ~64 bytes (conf, locked, PropertyChangeSupport)
- DynamicRailSemaphore: ~48 bytes (signal enum)
- DynamicInOut: ~80 bytes (two semaphore references)
- DynamicTrack: ~96 bytes (state, occupant, reservedFrom)

For typical network (vyhybna.xml):
- 2 InOut + 6 RailSemaphore + 2 RailSwitch + 10 TrackBlocks
- Overhead: ~1.5 KB (negligible)
```

---

## 4. Path Reconstruction Strategy

### 4.1 Path Architecture

Paths in the simulation store references to `PathElement` objects:

```kotlin
interface Path : Track, MutableCollection<PathElement> {
    fun getFirst(): PathSeparator
    fun getLast(): OrientedPathSeparator
    fun iterator(): MutableIterator<PathElement>
}
```

**Path Element Types:**
1. `PathSeparator` - Nodes (switches, semaphores, InOuts)
2. `Track` - Edges (track sections between nodes)

### 4.2 Current Path Construction Flow

```
Path Construction (pathToNextSemaphore):
+-------------+     +------------------+     +---------------+
| Start Point | --> | Navigate Network | --> | ArrayPath     |
| (separator) |     | (getNextTrack    |     | (elements)    |
|             |     |  Section)        |     |               |
+-------------+     +------------------+     +---------------+
                           |
                           v
                    Elements added:
                    [sep1, track1, sep2, track2, ..., lastSem]
```

### 4.3 Static-to-Dynamic Transition in Paths

**Critical Insight:** The current implementation already handles this correctly!

```kotlin
// DefaultSimulationContext.pathToNextSemaphore() - line 706-748
override fun pathToNextSemaphore(sep: PathSeparator, nxt: TrackSection): Path? {
    var separator = sep  // May be static or dynamic
    var next: TrackSection? = nxt
    val path = ArrayPath(this)

    do {
        path.add(separator)  // Add current separator to path
        if (next != null) {
            path.add(next)   // Add track section

            // CRITICAL: Convert static result to dynamic wrapper
            val staticResult = next.getSecondEnd(staticSeparator)
            separator = staticToDynamicMap[staticResult]
                ?: throw IllegalStateException("No dynamic wrapper found")

            // Continue navigation with dynamic separator
            next = getNextTrackSection(separator, next)
        }
    } while (next != null)

    return path
}
```

**Key Point:** Path construction automatically uses dynamic wrappers via `staticToDynamicMap` lookup.

### 4.4 Path Operation Semantics

When path operations are invoked, they must use dynamic wrappers:

```kotlin
// AbstractPath.kt - path operations
private fun pathIterating(sep: PathSeparator, operationName: String,
                          trackOperation: (Track, PathSeparator) -> Boolean): Boolean {
    // Operations on tracks use DynamicTrack wrappers
    for (track in elements) {
        val dynamicTrack = context.toDynamic(track as TrackFacility)
        trackOperation(dynamicTrack, separator)  // State changes on wrapper
    }
}
```

### 4.5 Path Validation After Transformation

**Invariant P-1:** All PathSeparator references in constructed paths must have dynamic wrappers.

```
ALGORITHM: ValidatePathConsistency

INPUT: path: Path, staticToDynamicMap: Map

PROCEDURE validatePath():
    FOR EACH element IN path:
        IF element IS PathSeparator:
            IF element NOT IN staticToDynamicMap AND element NOT IS DynamicPathSeparator:
                THROW PathValidationException("Path contains unmapped static separator")

        IF element IS TrackFacility:
            // Tracks are wrapped on-demand, validated at use time
            CONTINUE

    RETURN true
```

### 4.6 Parameterized Path Reconstruction

For parameterized grids, paths must be reconstructed when:

1. **Grid dimensions change** - Positions may shift
2. **Cells are added/removed** - Network topology changes
3. **Track connections change** - Graph structure modified

**Strategy:** Paths should NOT be cached across parameterization changes. Always reconstruct paths using `pathToNextSemaphore()` after grid transformation.

---

## 5. Simulation Correctness Verification

### 5.1 Correctness Invariants

The transformation must preserve these invariants:

| ID | Invariant | Verification Method |
|----|-----------|---------------------|
| C-1 | Track lengths unchanged | Compare `static.length() == dynamic.static.length()` |
| C-2 | Speed limits unchanged | Compare `static.maxSpeed() == dynamic.static.maxSpeed()` |
| C-3 | Topology unchanged | Graph connectivity preserved |
| C-4 | Initial states valid | All tracks FREE, all semaphores STOP |
| C-5 | Wrapper identity stable | `context.toDynamic(x) === context.toDynamic(x)` |

### 5.2 Physics Preservation

**Train Physics Equations (from Train.kt):**

```
Position: dp/dt = v (velocity)
Velocity: dv/dt = a (acceleration)
Acceleration: a = ((targetSpeed - v)(targetSpeed + v)) / (2s)
    where s = distance to next semaphore
```

**Transformation Impact:** NONE

The physics calculations use:
- `distanceToSemaphore()` - Calculated from path length, uses `static.length()`
- `allowedSpeed()` - Read from `DynamicPathSeparator.allowedSpeed()`
- Track state - Managed by `DynamicTrack` wrapper

All these operations delegate to static objects for immutable properties.

### 5.3 Timing Preservation

**jDisco Event Timing:**
- Event scheduling uses simulation time (not wall clock)
- Transformation happens BEFORE simulation starts
- No events are scheduled during transformation

**Invariant T-1:** Transformation must complete before `Process.activate(mainProcess)` is called.

```kotlin
// DefaultSimulationContext.run() - correct ordering
override fun run() {
    // 1. Validation (before any simulation)
    if (getGraph().isEmpty() || ...) throw EmptyContextException()

    // 2. Transformation (before events scheduled)
    initializeDynamicMapping()   // <<< Grid transformation
    validateDynamicMapping()

    // 3. Create processes (no events yet)
    if (mainProcess == null) {
        mainProcess = processFactory.createMainProcess(this)
    }

    // 4. Create workers (no events yet)
    for (dynamicInOut in getInOuts()) {
        workers[dynamicInOut] = processFactory.createInOutWorker(this, dynamicInOut)
    }

    // 5. Start simulation (events begin here)
    Process.activate(mainProcess)  // <<< Events start after transformation
}
```

### 5.4 Determinism Verification

**Test Strategy:** Golden output testing

```kotlin
@Test
fun `transformation produces deterministic results`() {
    // Setup
    val context1 = loadFromXML("vyhybna.xml")
    val context2 = loadFromXML("vyhybna.xml")

    // Transform both
    context1.run()  // Includes transformation
    context2.run()

    // Verify identical wrapper mapping
    assertThat(context1.staticToDynamicMap.keys)
        .isEqualTo(context2.staticToDynamicMap.keys)

    // Verify identical initial states
    for (key in context1.staticToDynamicMap.keys) {
        val wrapper1 = context1.staticToDynamicMap[key]
        val wrapper2 = context2.staticToDynamicMap[key]
        assertThat(wrapper1.initialState()).isEqualTo(wrapper2.initialState())
    }
}
```

---

## 6. Safety Invariants

### 6.1 Railway Safety Properties

Based on interlocking principles, the following safety invariants must hold:

| ID | Invariant | Description | Enforcement |
|----|-----------|-------------|-------------|
| SI-1 | Block protection | Only one train per track block | `DynamicTrack.enter()` collision check |
| SI-2 | Signal protection | Train stops at red signal | `Train.semaphoreAction()` |
| SI-3 | Route locking | Reserved path cannot be modified | `DynamicTrack.state == RESERVED` |
| SI-4 | Flank protection | No conflicting switch positions | `DynamicRailSwitch.setUpPath()` |
| SI-5 | Switch locking | Switch cannot toggle during train movement | `DynamicRailSwitch.lock()` |

### 6.2 Safety Invariant Preservation During Transformation

**SI-1 Block Protection:**
```kotlin
// DynamicTrack.enter() enforces collision detection
fun enter(newOccupant: TrackOccupant) {
    if (occupant != null) {
        logger.error { "CONFLICT: collision! Existing=$occupant, new=$newOccupant" }
    }
    requireSimulation(occupant == null) { "Track occupant collision" }
    // ...
}
```

**SI-2 Signal Protection:**
```kotlin
// Train.Front.semaphoreAction() waits for allowing signal
if (semaphore.signal == Signal.STOP) {
    fireStop()
    waitUntil(allowingSignal(semaphore))  // Blocks until GREEN
}
```

**SI-3 Route Locking:**
```kotlin
// DynamicTrack state machine enforces transitions
// FREE -> RESERVED -> OCCUPIED -> FREE
// Cannot skip states or reverse order
```

**SI-4 Flank Protection:**
```kotlin
// DynamicRailSwitch.setUpPath() validates configuration
private fun getPathConfWithException(from: Segment?, to: Segment?): Conf {
    return static.confs.get(from, to)
        ?: throw PathSeparatorChangeException("switch doesn't join these segments", this)
}
```

**SI-5 Switch Locking:**
```kotlin
// DynamicRailSwitch.changeConf() checks lock state
fun changeConf() {
    if (locked) {
        throw IllegalStateException("Cannot change while locked (SI-5)")
    }
    // ...
}
```

### 6.3 Transformation Safety Checklist

Before simulation can start, verify:

- [ ] All PathSeparators have dynamic wrappers
- [ ] All TrackFacilities have dynamic wrappers
- [ ] All dynamic wrappers are in initial state (FREE, STOP, MAIN)
- [ ] No wrapper references are null
- [ ] IdentityHashMap contains all expected entries
- [ ] Graph connectivity unchanged from editing context

---

## 7. jDisco Integration Considerations

### 7.1 jDisco Process Model

jDisco uses a process-interaction worldview:

```
jDisco Entities:
- Process: Active simulation entity (Train, Generator, InOutWorker)
- Variable: Continuous state variable (position, velocity)
- Condition: Event trigger (signal change, position threshold)
- Reporter: Periodic state reporting
```

### 7.2 Transformation Timing Relative to jDisco

```
Timeline:
   |
   | Context creation (no jDisco involvement)
   v
   | initializeDynamicMapping() - Creates wrappers
   v
   | validateDynamicMapping() - Verifies completeness
   v
   | Process creation (Generator, InOutWorker)
   v
   | Process.activate(mainProcess) - jDisco scheduler starts
   |
   | === SIMULATION TIME BEGINS ===
   v
   | Events processed in time order
   | ...
```

**Key Point:** All transformation happens BEFORE jDisco scheduler activation.

### 7.3 Impact on jDisco Processes

**Generator Process:**
- Creates `Train` instances
- Uses `DynamicInOut` from `getInOuts()`
- No direct grid access

**InOutWorker Process:**
- Manages entry/exit point queues
- Uses `DynamicInOut` reference (passed at construction)
- No direct grid access

**Train Process:**
- Navigates network using `context.getNextTrackSection()`
- Converts separators via `context.toDynamic()`
- Uses `DynamicTrack.enter()/leave()` for state changes

**Conclusion:** jDisco processes use the transformation result (maps) not the grid directly.

### 7.4 Event Ordering Guarantees

jDisco guarantees:
1. Events at same time processed in FIFO order
2. Continuous variables integrated between discrete events
3. Conditions evaluated after each event

**Transformation Guarantee:** Grid transformation introduces no events. The simulation starts from a clean state.

### 7.5 Future jDisco Migration Considerations

When migrating to DSOL or Kalasim (per LONG_TERM_GOALS.md):

1. **Factory Pattern Already in Place** - `SimulationProcessFactory` abstracts process creation
2. **Wrapper Pattern Portable** - Static/dynamic split is framework-agnostic
3. **Maps Are Portable** - `IdentityHashMap` can be replaced with equivalent
4. **Validation Logic Portable** - Does not depend on jDisco

---

## 8. Risk Assessment

### 8.1 High-Risk Scenarios

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Missing wrapper for separator | Low | High (crash) | `validateDynamicMapping()` check |
| Inconsistent wrapper identity | Medium | High (wrong behavior) | `IdentityHashMap` + validation |
| Path contains static reference | Low | Medium (state not updated) | Path construction always uses `toDynamic()` |
| Transformation during simulation | Very Low | Critical | Transformation in `run()` before `activate()` |

### 8.2 Edge Cases

**Edge Case 1: InOut with same semaphore reference**
```
Scenario: InOut.getInSemaphore() === InOut.getOutSemaphore()
Current handling: putIfAbsent() prevents overwrite
Verification: Unit test in DynamicInOutTest
```

**Edge Case 2: Empty grid**
```
Scenario: Grid has no cells
Current handling: EmptyContextException before transformation
Verification: EmptyContextException test in DefaultContextTest
```

**Edge Case 3: Disconnected graph components**
```
Scenario: Track network has isolated segments
Current handling: Each component transformed independently
Risk: Trains cannot reach isolated sections (correct behavior)
```

**Edge Case 4: Self-loop track**
```
Scenario: Track starts and ends at same separator
Current handling: Graph allows it, transformation handles normally
Verification: Needs explicit test case
```

### 8.3 Failure Modes

| Failure | Symptom | Recovery |
|---------|---------|----------|
| Wrapper not found | `IllegalStateException` at `toDynamic()` | Fix initialization order |
| State machine violation | `TrackOperationException` | Debug state transitions |
| Physics divergence | Different simulation results | Compare with golden output |

---

## 9. Example Scenarios

### 9.1 Scenario: vyhybna.xml Transformation

**Input Network:**
```
vyhybna.xml (shunting loop):
- Grid: 100x100 (sparse, ~20 cells used)
- 2 InOut points (A, B)
- 6 RailSemaphores
- 2 RailSwitches
- 10 SimpleTrackBlocks
```

**Transformation Steps:**

```
Step 1: Iterate grid cells
  Position (11,8): InOut "A" -> DynamicInOut
    - Also maps A.inSemaphore -> DynamicRailSemaphore
    - Also maps A.outSemaphore -> ConstantSemaphore(FREE)
  Position (14,8): RailSemaphore -> DynamicRailSemaphore
  Position (15,8): RailSwitch -> DynamicRailSwitch (conf=MAIN)
  Position (16,8): RailSemaphore -> DynamicRailSemaphore
  Position (17,9): RailSemaphore -> DynamicRailSemaphore
  Position (24,9): RailSemaphore -> DynamicRailSemaphore
  Position (25,8): RailSemaphore -> DynamicRailSemaphore
  Position (26,8): RailSwitch -> DynamicRailSwitch (conf=MAIN)
  Position (27,8): RailSemaphore -> DynamicRailSemaphore
  Position (30,8): InOut "B" -> DynamicInOut

Step 2: Map track blocks
  TrackBlock (11,8)-(14,8) -> DynamicTrack (state=FREE)
  TrackBlock (14,8)-(15,8) -> DynamicTrack (state=FREE)
  TrackBlock (15,8)-(16,8) -> DynamicTrack (state=FREE)
  TrackBlock (16,8)-(25,8) -> DynamicTrack (state=FREE)
  TrackBlock (15,8)-(17,9) -> DynamicTrack (state=FREE)
  TrackBlock (17,9)-(24,9) -> DynamicTrack (state=FREE)
  TrackBlock (24,9)-(26,8) -> DynamicTrack (state=FREE)
  TrackBlock (25,8)-(26,8) -> DynamicTrack (state=FREE)
  TrackBlock (26,8)-(27,8) -> DynamicTrack (state=FREE)
  TrackBlock (27,8)-(30,8) -> DynamicTrack (state=FREE)

Step 3: Validate
  staticToDynamicMap: 12 entries (2 InOut + 6 Semaphore + 2 Switch + 2 extra semaphores from InOut)
  staticTrackToDynamicMap: 10 entries
  All validations pass
```

### 9.2 Scenario: Path Construction After Transformation

**Request:** Path from InOut A to first semaphore

```
pathToNextSemaphore(A, firstTrackSection):

  1. Start at A (DynamicInOut)
     path.add(A)

  2. Get next track: TrackBlock (11,8)-(14,8)
     path.add(track1)

  3. Get second end: Semaphore at (14,8)
     staticResult = track1.getSecondEnd(A) = static RailSemaphore
     separator = staticToDynamicMap[staticResult] = DynamicRailSemaphore

  4. Check if semaphore is in direction
     isSeparatorInDirection(semaphore, nextTrack, currentTrack) = true

  5. Return path: [DynamicInOut(A), track1, DynamicRailSemaphore]
```

### 9.3 Scenario: Parameterized Grid (Future)

**Use Case:** Same network structure, different dimensions

```
Parameterization Request:
  - Original: 100x100 grid, InOut A at (11,8)
  - New: 200x200 grid, InOut A at (22,16) (scaled 2x)

Transformation Changes:
  1. Grid positions updated in Array2DMap
  2. Graph vertices (Point) updated in ExtendedUnorientedGraph
  3. Static objects remain unchanged (same RailSwitch, etc.)
  4. Dynamic wrappers remain valid (wrap same static objects)
  5. Paths must be reconstructed (use new positions for navigation)

Critical: toDynamic() still works because IdentityHashMap uses
object identity, not position.
```

---

## 10. Implementation Recommendations

### 10.1 Phase 1: Documentation (This Document)

**Status:** COMPLETE

Deliverables:
- [x] Grid transformation algorithm pseudocode
- [x] Path reconstruction strategy
- [x] Safety invariant documentation
- [x] jDisco integration analysis
- [x] Risk assessment
- [x] Example scenarios

### 10.2 Phase 2: Test Coverage (Before Implementation)

**Recommended Tests:**

```kotlin
// Test 1: Transformation completeness
@Test
fun `initializeDynamicMapping creates wrappers for all cells`()

// Test 2: Wrapper identity stability
@Test
fun `toDynamic returns same wrapper on repeated calls`()

// Test 3: Path uses dynamic wrappers
@Test
fun `pathToNextSemaphore returns path with dynamic separators`()

// Test 4: Initial state correctness
@Test
fun `all dynamic wrappers start in initial state`()

// Test 5: Transformation determinism
@Test
fun `same input produces same transformation result`()

// Test 6: Edge case - empty grid
@Test
fun `empty grid throws EmptyContextException`()

// Test 7: Edge case - disconnected components
@Test
fun `disconnected network components transform independently`()
```

### 10.3 Phase 3: Parameterization Implementation

**After tests pass, implement:**

1. **Grid Position Parameterization**
   - Add grid dimension parameters to context factory
   - Add cell position scaling/translation functions
   - Update graph vertices when grid changes

2. **Path Reconstruction Trigger**
   - Clear any cached paths when grid parameters change
   - Force path reconstruction via `pathToNextSemaphore()`

3. **Validation Enhancement**
   - Add parameterization validation (bounds checking)
   - Add topology validation (connectivity after parameterization)

### 10.4 Conservative Change Guidelines

Per CLAUDE.md guidelines:

1. **DO NOT modify sim/ package** - Keep transformation in context/
2. **Tests first** - Write tests before changing existing code
3. **Backwards compatibility** - Existing XML files must continue to work
4. **No breaking changes** - API contracts must be preserved

### 10.5 Success Criteria

Grid transformation implementation is complete when:

- [ ] All existing tests pass (662 tests)
- [ ] New transformation tests pass (7+ tests)
- [ ] Golden output unchanged for vyhybna.xml
- [ ] ShuntingLoop example produces same results
- [ ] Documentation updated with implementation details

---

## Appendix A: Class Diagram

```
                    +-------------------+
                    |    PathSeparator  |
                    |    (interface)    |
                    +-------------------+
                           ^
           +---------------+---------------+
           |               |               |
    +------+-----+  +------+-----+  +------+-----+
    | RailSwitch |  |RailSemaphore|  |   InOut   |
    | (static)   |  |  (static)   |  | (static)  |
    +------+-----+  +------+-----+  +------+-----+
           |               |               |
           v               v               v
    +------+-----+  +------+-----+  +------+-----+
    |Dynamic     |  |Dynamic     |  |Dynamic    |
    |RailSwitch  |  |RailSemaphore| |InOut      |
    | - conf     |  | - signal   |  | - inSem   |
    | - locked   |  +------------+  | - outSem  |
    +------------+                  +------------+
           |                              |
           +------------------------------+
                          |
                          v
              +-----------------------+
              | DynamicPathSeparator  |
              |     (interface)       |
              | + setUpPath()         |
              | + cancelPathSetup()   |
              | + allowedSpeed()      |
              | + getFollowingSegment()|
              +-----------------------+
```

---

## Appendix B: Sequence Diagram - Transformation Flow

```
    Main            DefaultSim         staticToDynamic    Grid
     |              Context                 Map            |
     |                  |                    |             |
     |  run()           |                    |             |
     |----------------->|                    |             |
     |                  |                    |             |
     |                  | initializeDynamic  |             |
     |                  | Mapping()          |             |
     |                  |                    |             |
     |                  | FOR EACH cell      |             |
     |                  |<-------------------|------------>|
     |                  |                    |             |
     |                  | createDynamicWrapper()           |
     |                  |---------------->   |             |
     |                  |                    |             |
     |                  |    put(static, dynamic)          |
     |                  |------------------->|             |
     |                  |                    |             |
     |                  | validateDynamic    |             |
     |                  | Mapping()          |             |
     |                  |<------------------>|             |
     |                  |                    |             |
     |                  | Process.activate() |             |
     |                  |-------------------->             |
     |                  |                    |             |
     |   SIMULATION     |                    |             |
     |   RUNNING        |                    |             |
```

---

## Appendix C: References

1. **Existing Documentation:**
   - `STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Wrapper pattern details
   - `CONTEXT_REFACTORING_DESIGN.md` - Context split design
   - `FACTORY_PATTERN_IMPLEMENTATION.md` - Process factory design
   - `LONG_TERM_GOALS.md` - Future development roadmap

2. **Source Files:**
   - `/home/beda/work/interlockSim/src/main/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt`
   - `/home/beda/work/interlockSim/src/main/kotlin/cz/vutbr/fit/interlockSim/util/Array2DMap.kt`
   - `/home/beda/work/interlockSim/src/main/kotlin/cz/vutbr/fit/interlockSim/objects/cells/Dynamic*.kt`
   - `/home/beda/work/interlockSim/src/main/kotlin/cz/vutbr/fit/interlockSim/objects/paths/AbstractPath.kt`

3. **Test Files:**
   - `/home/beda/work/interlockSim/src/test/kotlin/cz/vutbr/fit/interlockSim/context/PathSeparatorDynamicMappingTest.kt`
   - `/home/beda/work/interlockSim/src/test/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackTest.kt`
   - `/home/beda/work/interlockSim/src/test/kotlin/cz/vutbr/fit/interlockSim/objects/cells/Dynamic*Test.kt`

---

**Document Version:** 1.0
**Created:** 2026-01-18
**Author:** traffic-simulation-expert (Claude Opus 4.5)
**Status:** DESIGN PHASE COMPLETE - Ready for review
