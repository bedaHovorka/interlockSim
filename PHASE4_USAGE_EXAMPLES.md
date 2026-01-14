# Phase 4: Static/Dynamic Property Separation - Usage Examples

This document demonstrates how the new Dynamic* wrapper classes are used.

## Overview

The Dynamic* wrapper classes separate **static properties** (editing-time, immutable) from **dynamic properties** (simulation-time, mutable state).

## Key Design Principles

### 1. Static Object as Identity

All Dynamic* wrappers use the wrapped static object for stable identity:

```kotlin
val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
val dynamic1 = DynamicRailSemaphore(staticSemaphore)
val dynamic2 = DynamicRailSemaphore(staticSemaphore)

// Same static object -> equal
assert(dynamic1 == dynamic2)
assert(dynamic1.hashCode() == dynamic2.hashCode())

// Can be used in hash-based collections
val set = mutableSetOf<DynamicRailSemaphore>()
set.add(dynamic1)
set.add(dynamic2)  // Does not increase size
assert(set.size == 1)
```

### 2. State Independence

Dynamic state changes don't affect equality:

```kotlin
val dynamic1 = DynamicRailSemaphore(staticSemaphore)
val dynamic2 = DynamicRailSemaphore(staticSemaphore)

dynamic1.setSignal(Signal.S60)
dynamic2.setSignal(Signal.FREE)

// Still equal despite different signals
assert(dynamic1 == dynamic2)
assert(dynamic1.hashCode() == dynamic2.hashCode())
```

### 3. Stable Hash Code

Hash code doesn't change when state changes:

```kotlin
val dynamic = DynamicRailSemaphore(staticSemaphore)
val initialHash = dynamic.hashCode()

// Change state multiple times
dynamic.setSignal(Signal.S30)
assert(dynamic.hashCode() == initialHash)

dynamic.setSignal(Signal.S60)
assert(dynamic.hashCode() == initialHash)
```

## Usage Examples

### Example 1: DynamicRailSemaphore

```kotlin
// Create static semaphore (editing-time configuration)
val staticSemaphore = RailSemaphore(
    orientation = true,
    spatialType = Cell.SpatialType.HORIZONTAL
)

// Create dynamic wrapper for simulation
val dynamicSemaphore = DynamicRailSemaphore(staticSemaphore)

// Initially STOP for safety
assert(dynamicSemaphore.getSignal() == Signal.STOP)

// During simulation: change signal state
dynamicSemaphore.setSignal(Signal.S60)
assert(dynamicSemaphore.getSignal() == Signal.S60)

// Access static properties via wrapped object
val orientation = dynamicSemaphore.static.orientation
```

### Example 2: DynamicRailSwitch

```kotlin
// Create static switch (editing-time configuration)
val staticSwitch = RailSwitch(
    spatialType = Cell.SpatialType.HORIZONTAL,
    type = Type.SIMPLE_LEFT_FALSE
)

// Create dynamic wrapper for simulation
val dynamicSwitch = DynamicRailSwitch(staticSwitch)

// Initially MAIN configuration, unlocked
assert(dynamicSwitch.getConf() == Conf.MAIN)
assert(!dynamicSwitch.isLocked())

// During simulation: lock switch before train enters
dynamicSwitch.lock()
assert(dynamicSwitch.isLocked())

// Cannot change configuration while locked (safety SI-5)
try {
    dynamicSwitch.changeConf()
    fail("Should not allow configuration change when locked")
} catch (e: IllegalStateException) {
    // Expected - safety constraint enforced
}

// Unlock after train passes
dynamicSwitch.unlock()

// Now can change configuration
dynamicSwitch.changeConf()
assert(dynamicSwitch.getConf() == Conf.BRANCH)
```

### Example 3: DynamicTrack

```kotlin
// Create static track (editing-time configuration)
val separator1 = OrientedPathSeparator(null, null)
val separator2 = OrientedPathSeparator(null, null)
val staticTrack = SimpleTrackBlock(
    end1 = separator1,
    end2 = separator2,
    length = 100.0,
    maxSpeed1 = 30.0,
    maxSpeed2 = 30.0
)

// Create dynamic wrapper for simulation
val dynamicTrack = DynamicTrack(staticTrack)

// Initially FREE
assert(dynamicTrack.getState() == State.FREE)
assert(dynamicTrack.isFreeFrom(separator1))

// Reserve track for train path
dynamicTrack.setUpPath(separator1)
assert(dynamicTrack.getState() == State.RESERVED)
assert(dynamicTrack.isSetUpPath(separator1))

// Train enters
val train = createTrain()
dynamicTrack.enter(train)
assert(dynamicTrack.getState() == State.OCCUPIED)
assert(dynamicTrack.getTrackOccupant() == train)

// Train leaves
dynamicTrack.leave(train)
assert(dynamicTrack.getState() == State.FREE)
```

### Example 4: DynamicInOut

```kotlin
// Create static InOut (editing-time configuration)
val staticInOut = InOut(
    name = "North Station",
    orientation = true,
    spatialType = Cell.SpatialType.HORIZONTAL
)

// Create dynamic wrappers for embedded semaphores
val dynamicInSemaphore = DynamicRailSemaphore(staticInOut.getInSemaphore())
val dynamicOutSemaphore = DynamicRailSemaphore(staticInOut.getOutSemaphore())

// Create dynamic InOut wrapper
val dynamicInOut = DynamicInOut(
    static = staticInOut,
    dynamicInSemaphore = dynamicInSemaphore,
    dynamicOutSemaphore = dynamicOutSemaphore
)

// Access dynamic semaphores
val inSem = dynamicInOut.getInSemaphore()
val outSem = dynamicInOut.getOutSemaphore()

// Control signals during simulation
inSem.setSignal(Signal.S60)  // Allow train to enter at 60 km/h
```

## Integration with SimulationContext

Future work will add Dynamic* object management to SimulationContext:

```kotlin
interface SimulationContext : Context {
    /**
     * Get dynamic wrapper for a static semaphore
     */
    fun getDynamic(static: RailSemaphore): DynamicRailSemaphore
    
    /**
     * Get dynamic wrapper for a static switch
     */
    fun getDynamic(static: RailSwitch): DynamicRailSwitch
    
    /**
     * Get dynamic wrapper for a static track
     */
    fun getDynamic(static: TrackFacility): DynamicTrack
    
    /**
     * Get dynamic wrapper for a static InOut
     */
    fun getDynamic(static: InOut): DynamicInOut
}
```

Implementation example:

```kotlin
class DefaultSimulationContext(...) : SimulationContext {
    // Maps to manage Dynamic* instances
    private val dynamicSemaphores = IdentityHashMap<RailSemaphore, DynamicRailSemaphore>()
    private val dynamicSwitches = IdentityHashMap<RailSwitch, DynamicRailSwitch>()
    private val dynamicTracks = IdentityHashMap<TrackFacility, DynamicTrack>()
    private val dynamicInOuts = IdentityHashMap<InOut, DynamicInOut>()
    
    override fun getDynamic(static: RailSemaphore): DynamicRailSemaphore {
        return dynamicSemaphores.computeIfAbsent(static) {
            DynamicRailSemaphore(static)
        }
    }
    
    // Similar for other types...
}
```

## Benefits

### 1. Type Safety
```kotlin
// Editing code uses static objects only
fun editNetwork(context: EditingContext, semaphore: RailSemaphore) {
    // Cannot accidentally change signal state
    // semaphore.setSignal() doesn't exist on static object
}

// Simulation code uses dynamic wrappers
fun simulateTrain(context: SimulationContext, dynamicSem: DynamicRailSemaphore) {
    // Can change signal state
    dynamicSem.setSignal(Signal.S60)
}
```

### 2. Clear Separation of Concerns
```kotlin
// Static properties: set during editing, immutable during simulation
val length = track.length()       // Never changes
val maxSpeed = track.maxSpeed()   // Never changes

// Dynamic properties: change during simulation
val state = dynamicTrack.getState()       // Changes: FREE -> RESERVED -> OCCUPIED
val occupant = dynamicTrack.occupant      // Changes as trains enter/leave
```

### 3. Stable Collections
```kotlin
// Can use Dynamic* objects in hash-based collections
val occupiedTracks = mutableSetOf<DynamicTrack>()

// Add when occupied
dynamicTrack.enter(train)
occupiedTracks.add(dynamicTrack)

// State changes don't break collection
dynamicTrack.leave(train)  // State changes, but still in set
assert(occupiedTracks.contains(dynamicTrack))  // Still found
```

### 4. Testing Independence
```kotlin
// Test static structure without simulation state
@Test
fun testTrackTopology() {
    val track = SimpleTrackBlock(sep1, sep2, 100.0, 30.0, 30.0)
    assert(track.length() == 100.0)
    assert(track.ends().contains(sep1))
    // No simulation state to worry about
}

// Test dynamic behavior independently
@Test
fun testTrackStateTransitions() {
    val dynamicTrack = DynamicTrack(staticTrack)
    dynamicTrack.setUpPath(sep1)
    assert(dynamicTrack.getState() == State.RESERVED)
    // Tests only dynamic behavior
}
```

## Migration Strategy

### Phase 1: Current (Completed)
- ✅ Create Dynamic* wrapper classes
- ✅ Add comprehensive tests
- ✅ Document usage patterns
- ✅ Keep existing code working

### Phase 2: Context Integration (Next)
- [ ] Add Dynamic* management to SimulationContext
- [ ] Create IdentityHashMap mappings for each type
- [ ] Implement getDynamic() methods
- [ ] Update simulation initialization

### Phase 3: Gradual Migration (Future)
- [ ] Update simulation code to use Dynamic* objects
- [ ] Keep existing code working for backwards compatibility
- [ ] Migrate one subsystem at a time
- [ ] Add deprecation warnings to old API

### Phase 4: Static-Only Domain Objects (Future)
- [ ] Remove dynamic properties from domain objects
- [ ] Make domain objects truly immutable
- [ ] Force all simulation code to use Dynamic* wrappers
- [ ] Full type safety enforced

## Testing the Implementation

All Dynamic* classes have comprehensive test coverage:

- **DynamicRailSemaphoreTest**: 16 tests
- **DynamicRailSwitchTest**: 19 tests
- **DynamicTrackTest**: 18 tests

Tests verify:
- Dynamic state management
- Stable identity (equals/hashCode)
- State independence
- Hash-based collection compatibility
- Safety constraints (e.g., SI-5 for switches)

Run tests with:
```bash
./gradlew test --tests "Dynamic*Test"
```

## References

- Issue: bedaHovorka/interlockSim#92 (Phase 4)
- Design doc: PHASE4_STATIC_DYNAMIC_DESIGN.md
- Context refactoring: CONTEXT_REFACTORING_DESIGN.md
