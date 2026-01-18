# Static/Dynamic Separation Architecture

**Status:** ✅ COMPLETE (Issue #100)  
**Date:** 2026-01-18  
**Author:** Railway Interlocking Simulator Team

## Executive Summary

This document describes the complete static/dynamic separation architecture implemented in the Railway Interlocking Simulator. The architecture cleanly separates **static properties** (immutable configuration set during editing) from **dynamic properties** (mutable state managed during simulation) through a wrapper pattern applied consistently across all domain objects.

**Key Achievement:** Complete separation enables:
- **Editing contexts** to work with static objects only (no simulation state)
- **Simulation contexts** to manage dynamic state through wrapper objects
- **Stable identity** for object comparisons across state changes
- **Golden output testing** with deterministic simulation results

## Architecture Overview

### Design Principle

The architecture follows a fundamental principle:

> **Static objects** contain immutable configuration (topology, speeds, types).  
> **Dynamic wrappers** contain mutable simulation state (occupancy, signals, reservations).  
> **Contexts** manage the mapping between static objects and their dynamic wrappers.

This separation enables:
1. **Type safety** - Compile-time distinction between editing and simulation
2. **Stable identity** - Object identity based on static reference (===)
3. **Testability** - Easy to mock, verify, and validate
4. **Determinism** - Reproducible simulation results

## Architecture Evolution

### Before: Incomplete Architecture (Broken State)

```
┌─────────────────────────────────────────────────────────────────┐
│                        EDITING CONTEXT                          │
│  Uses: Static objects only (RailSwitch, RailSemaphore, etc.)   │
│  Purpose: Network topology configuration                       │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 │ Context.run()
                                 ↓
┌─────────────────────────────────────────────────────────────────┐
│                      SIMULATION CONTEXT                         │
│                                                                 │
│  Separators (Dynamic Wrappers):                                │
│    RailSwitch → DynamicRailSwitch ✅                            │
│    RailSemaphore → DynamicRailSemaphore ✅                      │
│    InOut → DynamicInOut ✅                                      │
│                                                                 │
│  Tracks (Mixed Static+Dynamic):                                │
│    SimpleTrack (static + dynamic mixed) ❌                      │
│    DynamicTrack (exists but unused) ⚠️                          │
│                                                                 │
│  Result: INCONSISTENT - Identity comparison failures           │
└─────────────────────────────────────────────────────────────────┘
```

**Problems:**
- SimpleTrack mixed static configuration with dynamic state
- Separators used dynamic wrappers, tracks did not
- Identity comparisons (===) broke across context boundaries
- Path operations sometimes used static, sometimes dynamic
- Test failures due to wrapper inconsistency

### After: Complete Architecture (Current State)

```
┌─────────────────────────────────────────────────────────────────┐
│                        EDITING CONTEXT                          │
│  Uses: Static objects only                                     │
│  - RailSwitch, RailSemaphore, InOut (separators)              │
│  - SimpleTrack, SimpleTrackBlock (tracks)                     │
│  Purpose: Network topology configuration                       │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 │ Context.run()
                                 │ initializeDynamicMapping()
                                 ↓
┌─────────────────────────────────────────────────────────────────┐
│                      SIMULATION CONTEXT                         │
│                                                                 │
│  Separators (Dynamic Wrappers):                                │
│    RailSwitch → DynamicRailSwitch ✅                            │
│    RailSemaphore → DynamicRailSemaphore ✅                      │
│    InOut → DynamicInOut ✅                                      │
│                                                                 │
│  Tracks (Dynamic Wrappers):                                    │
│    SimpleTrack (static only) ✅                                 │
│    SimpleTrackBlock → DynamicTrack (wrapper) ✅                 │
│                                                                 │
│  Mapping:                                                       │
│    staticToDynamicMap: IdentityHashMap<PathSeparator, Dynamic> │
│    staticTrackToDynamicMap: IdentityHashMap<Track, DynamicTrack>│
│                                                                 │
│  Result: CONSISTENT - Clean separation, stable identity        │
└─────────────────────────────────────────────────────────────────┘
```

**Benefits:**
- ✅ Consistent wrapper pattern for all domain objects
- ✅ Static objects immutable, wrappers manage state
- ✅ Identity comparisons work correctly (via `static` property)
- ✅ Context manages mapping via IdentityHashMap
- ✅ Lazy wrapper creation for tracks discovered during simulation
- ✅ All tests pass with deterministic results

## Data Flow Architecture

### Phase 1: Editing (Static Objects)

```
┌──────────────────────────────────────────────────────────────┐
│                    EDITING PHASE                             │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  User Actions:                                              │
│    • Load XML / Create new network                          │
│    • Add/remove cells (RailSwitch, RailSemaphore, InOut)   │
│    • Connect cells with tracks                              │
│    • Configure properties (speed, length, names)            │
│                                                              │
│  Objects Created:                                           │
│    • Static separators: RailSwitch, RailSemaphore, InOut   │
│    • Static tracks: SimpleTrack, SimpleTrackBlock           │
│                                                              │
│  Characteristics:                                            │
│    • Immutable configuration                                │
│    • No simulation state                                    │
│    • Topology operations only                               │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Phase 2: Context Initialization

```
┌──────────────────────────────────────────────────────────────┐
│              CONTEXT INITIALIZATION PHASE                    │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Context.run() called:                                      │
│    1. initializeDynamicMapping()                            │
│       • Creates DynamicRailSwitch for each RailSwitch       │
│       • Creates DynamicRailSemaphore for each RailSemaphore │
│       • Creates DynamicInOut for each InOut                 │
│       • Populates staticToDynamicMap (IdentityHashMap)      │
│                                                              │
│    2. validateDynamicMapping()                              │
│       • Ensures all separators have wrappers                │
│       • Catches initialization bugs early                   │
│                                                              │
│    3. Track wrappers created lazily                         │
│       • DynamicTrack created on first toDynamic(track) call │
│       • Cached in staticTrackToDynamicMap                   │
│                                                              │
│  Result:                                                     │
│    • All separators have Dynamic wrappers                   │
│    • Tracks wrapped on-demand during simulation             │
│    • IdentityHashMap ensures stable wrapper identity        │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Phase 3: Simulation Execution

```
┌──────────────────────────────────────────────────────────────┐
│                   SIMULATION PHASE                           │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Simulation Code (Train, Path, ShuntingLoop):              │
│                                                              │
│  1. Access static reference for identity:                   │
│     if (track.static === targetTrack) { ... }              │
│                                                              │
│  2. Call toDynamic() for state operations:                  │
│     val dynamic = context.toDynamic(track)                  │
│     dynamic.setUpPath(separator)  // State change           │
│     dynamic.enter(train)          // State change           │
│     dynamic.leave(train)          // State change           │
│                                                              │
│  3. State transitions managed by wrappers:                  │
│     FREE → RESERVED → OCCUPIED → FREE                       │
│                                                              │
│  4. Identity comparisons use static reference:              │
│     • train.where === separator.static                      │
│     • path contains static track objects                    │
│     • wrapper.static provides stable identity               │
│                                                              │
│  Result:                                                     │
│    • Clean separation: static vs. dynamic                   │
│    • Stable identity across state changes                   │
│    • Deterministic simulation behavior                      │
│    • Golden output testing works                            │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## Wrapper Pattern Implementation

### Separator Wrappers

All path separators (nodes where trains make routing decisions) have dynamic wrappers:

#### DynamicRailSwitch

```kotlin
class DynamicRailSwitch(val static: RailSwitch) : PathSeparator by static {
    // Dynamic properties:
    var conf: Conf = Conf.MAIN  // Current configuration (MAIN/BRANCH)
    var locked: Boolean = false  // Lock state
    
    // Delegates static properties to wrapped object:
    val type: RailSwitch.Type get() = static.type
    val name: String get() = static.getName()
    
    // Identity based on static object:
    override fun equals(other: Any?) = 
        other is DynamicRailSwitch && static === other.static
    override fun hashCode() = System.identityHashCode(static)
}
```

**Key Features:**
- Wraps static RailSwitch
- Manages mutable configuration (MAIN/BRANCH)
- Delegates immutable properties to static object
- Identity based on static object reference

#### DynamicRailSemaphore

```kotlin
class DynamicRailSemaphore(val static: RailSemaphore) : PathSeparator by static {
    // Dynamic properties:
    var signal: Signal = Signal.STOP  // Current signal state
    
    // Identity based on static object:
    override fun equals(other: Any?) = 
        other is DynamicRailSemaphore && static === other.static
    override fun hashCode() = System.identityHashCode(static)
}
```

**Key Features:**
- Wraps static RailSemaphore
- Manages mutable signal state (STOP/GO)
- Identity based on static object reference

#### DynamicInOut

```kotlin
class DynamicInOut(val static: InOut) : PathSeparator by static {
    // Dynamic properties:
    var lastTrain: Train? = null  // Last train processed
    
    // Identity based on static object:
    override fun equals(other: Any?) = 
        other is DynamicInOut && static === other.static
    override fun hashCode() = System.identityHashCode(static)
}
```

**Key Features:**
- Wraps static InOut (entry/exit point)
- Tracks last train processed
- Identity based on static object reference

### Track Wrapper

Tracks use the same wrapper pattern:

#### DynamicTrack

```kotlin
class DynamicTrack(val static: TrackFacility) {
    // Dynamic properties:
    var state: TrackFacility.State = TrackFacility.State.FREE
    var occupant: TrackOccupant? = null
    var reservedFrom: PathSeparator? = null
    
    // State transition methods:
    fun enter(newOccupant: TrackOccupant) { /* FREE → OCCUPIED */ }
    fun leave(leavingOccupant: TrackOccupant) { /* OCCUPIED → FREE */ }
    fun setUpPath(sep: PathSeparator) { /* FREE → RESERVED */ }
    fun cancelPathSetup(sep: PathSeparator) { /* RESERVED → FREE */ }
    
    // Query methods:
    fun isFreeFrom(sep: PathSeparator): Boolean
    fun isSetUpPath(sep: PathSeparator): Boolean
    fun getTrackOccupant(): TrackOccupant
    
    // Delegated static properties:
    val length: Double get() = static.length()
    val ends: Array<PathSeparator> get() = static.ends()
    
    // Identity based on static object:
    override fun equals(other: Any?) = 
        other is DynamicTrack && static === other.static
    override fun hashCode() = System.identityHashCode(static)
}
```

**Key Features:**
- Wraps static TrackFacility (SimpleTrackBlock)
- Manages complete state machine (FREE/RESERVED/OCCUPIED)
- Delegates immutable properties to static object
- Identity based on static object reference
- Comprehensive state validation and logging

### Static Objects

Static objects contain only immutable configuration:

#### SimpleTrack (Static Only)

```kotlin
abstract class SimpleTrack : AbstractTrack(), StaticTrack {
    // Static fields only:
    private val speeds: IdentityHashMap<PathSeparator, Double>
    private val ends: Array<PathSeparator>
    
    // Static methods only:
    override fun length(): Double
    override fun ends(): Array<PathSeparator>
    override fun maxSpeed(): Double
    override fun getSecondEnd(end: PathSeparator): PathSeparator
}
```

**Key Features:**
- No dynamic state
- Immutable configuration only
- Used in editing contexts
- Wrapped by DynamicTrack for simulation

## Context Architecture

### SimulationContext Interface

```kotlin
interface SimulationContext : Context {
    /**
     * Convert static PathSeparator to Dynamic wrapper.
     * Returns existing wrapper or original separator if not found.
     */
    fun toDynamic(separator: PathSeparator): PathSeparator
    
    /**
     * Convert static TrackFacility to DynamicTrack wrapper.
     * Creates wrapper lazily if not yet created.
     */
    fun toDynamic(track: TrackFacility): DynamicTrack
    
    // Other simulation methods...
}
```

### DefaultSimulationContext Implementation

```kotlin
class DefaultSimulationContext(
    cols: Int, 
    rows: Int,
    processFactory: SimulationProcessFactory
) : DefaultEditingContext(cols, rows), SimulationContext {
    
    // Wrapper caches:
    private val staticToDynamicMap: MutableMap<PathSeparator, DynamicPathSeparator> 
        = IdentityHashMap()
    private val staticTrackToDynamicMap: MutableMap<TrackFacility, DynamicTrack> 
        = IdentityHashMap()
    
    /**
     * Initialize all separator wrappers before simulation starts.
     * Called by run() before simulation begins.
     */
    private fun initializeDynamicMapping() {
        // Create wrappers for all separators
        for (cell in grid.getAllCells()) {
            when (cell) {
                is RailSwitch -> {
                    val dynamic = DynamicRailSwitch(cell)
                    staticToDynamicMap[cell] = dynamic
                }
                is RailSemaphore -> {
                    val dynamic = DynamicRailSemaphore(cell)
                    staticToDynamicMap[cell] = dynamic
                }
                is InOut -> {
                    val dynamic = DynamicInOut(cell)
                    staticToDynamicMap[cell] = dynamic
                }
            }
        }
    }
    
    /**
     * Convert separator to dynamic wrapper.
     * Returns wrapper if found, otherwise returns original (static).
     */
    override fun toDynamic(separator: PathSeparator): PathSeparator {
        return if (separator is DynamicPathSeparator) {
            separator  // Already dynamic
        } else {
            staticToDynamicMap[separator] ?: separator
        }
    }
    
    /**
     * Convert track to dynamic wrapper.
     * Creates wrapper lazily if not yet created.
     */
    override fun toDynamic(track: TrackFacility): DynamicTrack {
        // Return existing wrapper if already mapped
        staticTrackToDynamicMap[track]?.let { return it }
        
        // Create new wrapper for unmapped track (lazy initialization)
        val dynamicTrack = DynamicTrack(track)
        staticTrackToDynamicMap[track] = dynamicTrack
        return dynamicTrack
    }
}
```

**Key Features:**
- **IdentityHashMap** ensures identity-based mapping (=== not ==)
- **Eager initialization** for separators (before simulation)
- **Lazy initialization** for tracks (on first use)
- **Stable identity** via static object reference
- **Validation** checks completeness before simulation

## Usage Patterns

### Pattern 1: Path Operations (AbstractPath)

```kotlin
// AbstractPath stores static tracks
class ArrayPath(private val elements: List<Track>) : AbstractPath() {
    
    // Wrap on-demand for state operations:
    override fun isFreeFrom(separator: PathSeparator): Boolean {
        for (track in elements) {
            if (track is TrackFacility) {
                val dynamic = context.toDynamic(track)
                if (!dynamic.isFreeFrom(separator)) {
                    return false
                }
            }
        }
        return true
    }
    
    override fun setUpPath(separator: PathSeparator) {
        for (track in elements) {
            if (track is TrackFacility) {
                val dynamic = context.toDynamic(track)
                dynamic.setUpPath(separator)
            }
        }
    }
}
```

**Benefits:**
- Path contains static tracks (immutable topology)
- Dynamic wrappers created on-demand
- Clear separation at call site
- Easy to audit and review

### Pattern 2: Train Operations (Train)

```kotlin
class Train(
    private val context: SimulationContext,
    private var where: PathSeparator
) {
    fun move() {
        // Get next track from path
        val next = getNextTrack()
        
        // Wrap for state changes:
        if (next is TrackFacility) {
            context.toDynamic(next).enter(this)
        }
        
        if (current is TrackFacility) {
            context.toDynamic(current).leave(this)
        }
        
        // Update separator (wrap if needed):
        where = context.toDynamic(next.getSecondEnd(where))
    }
}
```

**Benefits:**
- Explicit wrapping at point of use
- Clear state change operations
- Type-safe API
- Stable identity via static reference

### Pattern 3: ShuntingLoop State Checks

```kotlin
class ShuntingLoop(private val context: SimulationContext) {
    
    fun checkOneEnd(block: SimpleTrackBlock, to: DynamicRailSemaphore): Boolean {
        // Wrap once for multiple operations:
        val dynamicBlock = context.toDynamic(block)
        
        // Multiple state queries use same wrapper:
        if (dynamicBlock.state == TrackFacility.State.FREE) return false
        if (dynamicBlock.state != TrackFacility.State.OCCUPIED) return false
        
        val train = dynamicBlock.getTrackOccupant()
        // ... use train ...
        
        return dynamicBlock.isSetUpPath()
    }
}
```

**Benefits:**
- Efficient: single wrapper for multiple operations
- Clear: static for topology, dynamic for state
- Safe: type-checked at compile time

## Testing Strategy

### Unit Tests

**DynamicTrack Tests:**
```kotlin
class DynamicTrackTest {
    @Test
    fun `state transitions work correctly`() {
        val static = SimpleTrackBlock(end1, end2, 100.0, 80.0)
        val track = DynamicTrack(static)
        
        // Initial state
        assertThat(track.state).isEqualTo(TrackFacility.State.FREE)
        
        // Reserve
        track.setUpPath(end1)
        assertThat(track.state).isEqualTo(TrackFacility.State.RESERVED)
        
        // Occupy
        track.enter(train)
        assertThat(track.state).isEqualTo(TrackFacility.State.OCCUPIED)
        
        // Free
        track.leave(train)
        assertThat(track.state).isEqualTo(TrackFacility.State.FREE)
    }
    
    @Test
    fun `identity based on static object`() {
        val static = SimpleTrackBlock(end1, end2, 100.0, 80.0)
        val wrapper1 = DynamicTrack(static)
        val wrapper2 = DynamicTrack(static)
        
        // Same static object → equal wrappers
        assertThat(wrapper1).isEqualTo(wrapper2)
        assertThat(wrapper1.hashCode()).isEqualTo(wrapper2.hashCode())
    }
}
```

**Context Mapping Tests:**
```kotlin
class TrackDynamicMappingTest {
    @Test
    fun `toDynamic returns same wrapper for same track`() {
        val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
        
        val wrapper1 = context.toDynamic(track)
        val wrapper2 = context.toDynamic(track)
        
        // Same wrapper instance (cached)
        assertThat(wrapper1).isSameAs(wrapper2)
    }
    
    @Test
    fun `toDynamic creates wrapper lazily`() {
        val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
        
        // First call creates wrapper
        val wrapper = context.toDynamic(track)
        assertThat(wrapper).isNotNull()
        
        // Wrapper has correct static reference
        assertThat(wrapper.static).isSameAs(track)
    }
}
```

### Integration Tests

**ShuntingLoop Operational Tests:**
```kotlin
class ShuntingLoopOperationalTest {
    @Test
    fun `complete simulation cycle works`() {
        val context = loadContextFromXML("vyhybna.xml")
        
        // Run simulation
        context.run()
        
        // Verify trains moved correctly
        assertThat(trainsCompleted).isEqualTo(expectedCount)
    }
}
```

### Golden Output Testing

Golden output testing validates deterministic simulation results:

```kotlin
class GoldenOutputTest {
    @Test
    fun `shunting loop produces expected output`() {
        val context = loadContextFromXML("vyhybna.xml")
        val capturedOutput = captureSimulationOutput {
            context.run()
        }
        
        val expectedOutput = loadGoldenOutput("shuntingLoop.golden")
        assertThat(capturedOutput).isEqualTo(expectedOutput)
    }
}
```

**Benefits:**
- Detects unintended behavior changes
- Validates state machine correctness
- Ensures consistent wrapper behavior
- Catches identity comparison bugs

## Benefits Summary

### 1. Clean Separation ✅

**Before:** Mixed static/dynamic in SimpleTrack  
**After:** Clean separation via wrapper pattern

- Editing contexts work with static objects only
- Simulation contexts use dynamic wrappers
- No simulation state in editing phase

### 2. Stable Identity ✅

**Before:** Identity comparison failures  
**After:** Stable identity via static reference

- `wrapper.static === target` always works
- IdentityHashMap uses stable keys
- Collections (Set, Map) work correctly

### 3. Type Safety ✅

**Before:** Runtime errors from state access  
**After:** Compile-time type checking

- `context.toDynamic()` explicit and type-safe
- Cannot access dynamic state from static objects
- Clear API contracts

### 4. Testability ✅

**Before:** Hard to test mixed state  
**After:** Easy to mock and verify

- Mock contexts provide test wrappers
- State transitions testable in isolation
- Golden output validation works

### 5. Maintainability ✅

**Before:** Unclear when objects are static vs dynamic  
**After:** Explicit wrapper pattern

- Clear code intent at call sites
- Easy to audit wrapper usage
- Well-documented pattern

## Migration Guide

### For Developers

**Old pattern (broken):**
```kotlin
// Direct dynamic method calls on SimpleTrack
val track = SimpleTrackBlock(...)
track.setUpPath(separator)  // UnsupportedOperationException!
```

**New pattern (correct):**
```kotlin
// Wrap track before calling dynamic methods
val staticTrack = SimpleTrackBlock(...)
val dynamicTrack = context.toDynamic(staticTrack)
dynamicTrack.setUpPath(separator)  // ✅ Works
```

### For Identity Comparisons

**Old pattern:**
```kotlin
if (track1 == track2) { ... }  // Broken with wrappers
```

**New pattern:**
```kotlin
if (track1.static === track2.static) { ... }  // ✅ Works with wrappers
```

### For Path Construction

**Correct pattern (no change needed):**
```kotlin
// Paths contain static tracks
val path = ArrayPath(listOf(staticTrack1, staticTrack2))

// Wrap on-demand for operations
path.setUpPath(separator)  // AbstractPath wraps internally
```

## Future Work

### Completed ✅

- ✅ Phase 1: Remove dynamic state from SimpleTrack
- ✅ Phase 2: Integrate DynamicTrack into simulation
- ✅ Phase 3: Update all simulation code to use wrappers
- ✅ Golden output testing validates deterministic results
- ✅ All 242 tests passing

### Not Planned

The following were considered but not needed:

- ❌ Separate EditingContext and SimulationContext classes
  - Current DefaultSimulationContext extends DefaultEditingContext
  - Works well, no need to split

- ❌ Wrapper pattern for all domain objects
  - Only separators and tracks need wrappers
  - Other objects (Train, Path) don't have static/dynamic split

- ❌ DSL for wrapper creation
  - `context.toDynamic()` is clear and simple
  - No need for additional abstraction

## Conclusion

The static/dynamic separation architecture successfully achieves:

1. **Complete separation** of static configuration from dynamic state
2. **Consistent wrapper pattern** across all domain objects (separators + tracks)
3. **Stable identity** for reliable object comparisons
4. **Type-safe API** for explicit wrapping at call sites
5. **Deterministic simulation** validated by golden output tests

The architecture is **production-ready** and has been validated by:
- ✅ 242 unit tests passing
- ✅ Integration tests passing
- ✅ End-to-end simulation examples working
- ✅ Golden output validation

This architecture provides a solid foundation for future development and ensures maintainable, testable code throughout the simulator.

---

**References:**
- Issue #100: Static/Dynamic Separation Implementation
- `PHASE1_BREAKING_CHANGES.md`: Phase 1 documentation
- `ISSUE_100_5_SUMMARY.md`: Phase 2 documentation
- `CONTEXT_REFACTORING_DESIGN.md`: Context refactoring design
- `FACTORY_PATTERN_IMPLEMENTATION.md`: Factory pattern implementation
