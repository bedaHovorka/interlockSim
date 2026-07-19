# Path Discovery Migration Guide

**Issue #292 Phase 5** - Migration from deprecated mixed-concern APIs to specialized path discovery services.

**Date**: 2026-01-27
**Status**: Active
**Audience**: Developers migrating code from deprecated `pathToNextSemaphore()` and `getNextTrackSection()` methods

---

## Table of Contents

1. [Overview](#overview)
2. [Why Migrate?](#why-migrate)
3. [Three New APIs](#three-new-apis)
4. [Migration Scenarios](#migration-scenarios)
5. [Before/After Examples](#beforeafter-examples)
6. [Troubleshooting](#troubleshooting)
7. [Testing](#testing)

---

## Overview

**Deprecated APIs** (mixed concerns, fragile):
- `SimulationEnvironment.pathToNextSemaphore(separator, next)`
- `SimulationEnvironment.getNextTrackSection(separator, current)`

**New APIs** (clean separation):
- `TopologyNavigator` - Static topology navigation (editing, validation)
- `PathReservationService` - Dispatcher logic (find FREE paths, reserve atomically)
- `TrainNavigationService` - Train-specific navigation (follow RESERVED paths)

**Deprecation Level**: `WARNING` (code compiles with warnings)

---

## Why Migrate?

### Problems with Old APIs

1. **Mixed Concerns**: Single method handles static topology + dynamic state + ownership validation
2. **Ambiguous Ownership**: Unclear who owns blocks, leading to race conditions (Issue #291)
3. **Fragile Validation**: Workaround code required to prevent trains from entering unowned blocks (Issue #282)
4. **Manual Path Construction**: Complex code in ShuntingLoop (~100 lines) to build paths manually
5. **False "Path Blocked" Errors**: Retry logic needed due to temporary block state inconsistencies

### Benefits of New APIs

1. **Clear Separation**: Static topology, dynamic reservation, and train navigation are distinct operations
2. **Explicit Ownership**: PathReservationRegistry tracks train↔block ownership bidirectionally
3. **Atomic Reservation**: All-or-nothing path reservation prevents partial reservations
4. **Simplified Code**: ShuntingLoop reduced by ~100 lines (no manual path construction)
5. **Type Safety**: Separate interfaces prevent mixing incompatible operations

---

## Three New APIs

### 1. TopologyNavigator

**Purpose**: Pure graph traversal without state dependencies

**Use Cases**:
- Network validation during editing
- Path visualization in editor
- Static topology analysis

**Access**:
```kotlin
// EditingContext
val navigator = context.getTopologyNavigator()

// SimulationEnvironment (via SimulationContext)
val navigator = env.getTrainNavigationService().navigator // Internal
```

**Key Methods**:
```kotlin
interface TopologyNavigator {
    fun findPath(start: PathSeparator, target: PathSeparator): Path?
    fun getNextTrackSection(separator: PathSeparator, current: TrackSection?): TrackSection?
    fun findPathToNextSemaphore(separator: PathSeparator, next: TrackSection): Path?
}
```

### 2. PathReservationService

**Purpose**: Dispatcher logic for finding and reserving FREE paths

**Use Cases**:
- Dispatcher finding available routes for trains
- Interlocking systems reserving paths before train entry
- Capacity planning and conflict resolution

**Access**:
```kotlin
val service = env.getPathReservationService()
```

**Key Methods**:
```kotlin
interface PathReservationService {
    fun reservePath(trainId: String, start: PathSeparator, target: PathSeparator): ReservationResult
    fun releasePath(trainId: String)
    fun findReservablePaths(start: PathSeparator, target: PathSeparator): List<Path>
}
```

### 3. TrainNavigationService

**Purpose**: Train-specific navigation through RESERVED paths

**Use Cases**:
- Train requesting next track section (only through owned blocks)
- Train checking if path is reserved for it
- Train navigating semaphore-to-semaphore

**Access**:
```kotlin
val service = env.getTrainNavigationService()
```

**Key Methods**:
```kotlin
interface TrainNavigationService {
    fun findReservedPathForTrain(trainId: String, separator: PathSeparator, next: TrackSection): Path?
    fun isPathReservedForTrain(trainId: String, start: PathSeparator, target: PathSeparator): Boolean
    fun getReservedBlocks(trainId: String): Set<DynamicTrackBlock>
}
```

---

## Migration Scenarios

### Scenario 1: Static Topology Navigation (Editing)

**Before** (deprecated):
```kotlin
// In editor validation code
val path = context.pathToNextSemaphore(separator, next)
```

**After** (TopologyNavigator):
```kotlin
val navigator = context.getTopologyNavigator()
val path = navigator.findPathToNextSemaphore(separator, next)
```

**Rationale**: Editing context doesn't have dynamic state, only topology matters.

---

### Scenario 2: Dispatcher Finding Available Routes

**Before** (deprecated + manual checks):
```kotlin
// In ShuntingLoop or Interlocking
val path = context.pathToNextSemaphore(start, next)
if (path != null && path.blocks.all { it.getState() == TrackFacility.State.FREE }) {
    path.blocks.forEach { it.reserveFrom(semaphore) }
    // ... setup switches ...
}
```

**After** (PathReservationService):
```kotlin
val service = env.getPathReservationService()
val result = service.reservePath(trainId = "Train1", start = start, target = target)
when (result) {
    is ReservationResult.Success -> {
        // Path reserved atomically, switches configured automatically
        val path = result.path
    }
    is ReservationResult.Failure -> {
        // All blocks are FREE, or explain why reservation failed
    }
}
```

**Rationale**: Atomic reservation prevents partial reservations, automatic switch configuration.

---

### Scenario 3: Train Navigation Through Reserved Blocks

**Before** (deprecated + manual ownership validation):
```kotlin
// In Train.Front.semaphoreAction()
val path = env.pathToNextSemaphore(semaphore, next)
if (path == null) {
    // Path blocked, but why? State validation mixed in.
    fireStop()
    return
}
// Hope path is reserved for us...
accelerateToSignal(semaphore, path)
```

**After** (TrainNavigationService):
```kotlin
val service = env.getTrainNavigationService()
val path = service.findReservedPathForTrain(
    trainId = toString(),
    separator = semaphore,
    next = next
)
if (path == null) {
    // Path not reserved for THIS train, wait
    fireStop()
    waitUntil { service.isPathReservedForTrain(toString(), semaphore, target) }
} else {
    // Path is reserved for us, continue safely
    accelerateToSignal(semaphore, path)
}
```

**Rationale**: Explicit ownership checking prevents trains from entering unowned blocks.

---

## Before/After Examples

### Example 1: ShuntingLoop Path Setup

**Before** (manual path construction):
```kotlin
// ShuntingLoop.kt lines 164-189 (before Issue #296)
private fun constructPath(
    context: SimulationContext,
    entry: DynamicInOut,
    semaphore1: DynamicRailSemaphore,
    semaphore2: DynamicRailSemaphore,
    innerBlock: DynamicTrackBlock,
    semaphore3: DynamicRailSemaphore
): Path? {
    val path1 = context.pathToNextSemaphore(entry, entryTrack) ?: return null
    val path2 = context.pathToNextSemaphore(semaphore1, innerTrack) ?: return null
    val path3 = context.pathToNextSemaphore(semaphore2, exitTrack) ?: return null

    // Merge paths manually
    val combinedPath = mergePaths(path1, path2, path3)

    // Reserve blocks manually
    combinedPath.blocks.forEach { it.reserveFrom(entry) }

    // Configure switches manually
    configureSwitches(combinedPath)

    return combinedPath
}
```

**After** (PathReservationService):
```kotlin
// ShuntingLoop.kt (after Issue #296)
private fun setupPathForTrain(train: Train): Boolean {
    val service = context.getPathReservationService()
    val result = service.reservePath(
        trainId = train.toString(),
        start = train.currentPosition,
        target = exitSemaphore
    )

    return when (result) {
        is ReservationResult.Success -> {
            // Path reserved atomically, switches configured automatically
            logger.info { "Path reserved for ${train}: ${result.path.length()}m" }
            true
        }
        is ReservationResult.Failure -> {
            logger.debug { "Path reservation failed for ${train}: ${result.reason}" }
            false
        }
    }
}
```

**Lines Removed**: ~100 lines of manual path construction and switch configuration

---

### Example 2: Train Approaching Semaphore

**Before** (ambiguous ownership):
```kotlin
// Train.kt Front.semaphoreAction() (before Issue #292)
override fun semaphoreAction() {
    val path = env.pathToNextSemaphore(semaphore, next)
    if (path == null) {
        // Why is path null? Block state? Topology? Ownership?
        fireStop()
        return
    }
    accelerateToSignal(semaphore, path)
}
```

**After** (explicit ownership):
```kotlin
// Train.kt Front.semaphoreAction() (after Issue #292)
override fun semaphoreAction() {
    val service = env.getTrainNavigationService()
    val path = service.findReservedPathForTrain(
        trainId = train.toString(),
        separator = semaphore,
        next = next
    )
    if (path == null) {
        // Clear reason: path not reserved for THIS train
        logger.debug { "${train}: Path not reserved, waiting at $semaphore" }
        fireStop()
        // Wait until path is reserved for us
        waitUntil {
            service.isPathReservedForTrain(train.toString(), semaphore, targetSemaphore)
        }
        return
    }
    accelerateToSignal(semaphore, path)
}
```

**Benefits**: Clear ownership semantics, no false "path blocked" errors

---

### Example 3: Editor Path Validation

**Before** (mixed with simulation logic):
```kotlin
// Editor validation (before Issue #292)
fun validateNetworkConnectivity(context: EditingContext): Boolean {
    val simContext = ContextTransformer.transform(context) // Force conversion
    val path = simContext.pathToNextSemaphore(start, next)
    simContext.close() // Cleanup
    return path != null
}
```

**After** (clean separation):
```kotlin
// Editor validation (after Issue #292)
fun validateNetworkConnectivity(context: EditingContext): Boolean {
    val navigator = context.getTopologyNavigator()
    val path = navigator.findPathToNextSemaphore(start, next)
    return path != null
}
```

**Benefits**: No need to convert to SimulationContext, cleaner API

---

## Troubleshooting

### Problem: "Path not reserved for this train"

**Symptom**: Train stops at semaphore, logs show "Path not reserved"

**Cause**: Dispatcher didn't reserve path before train arrival, or reservation was released prematurely

**Solution**:
```kotlin
// In Interlocking/ShuntingLoop: reserve BEFORE train enters network
val service = env.getPathReservationService()
service.reservePath(trainId, start, target) // Atomic reservation

// Train will now see path as reserved
```

---

### Problem: "TopologyNavigator not found in scope"

**Symptom**: `NoSuchElementException: TopologyNavigator not found`

**Cause**: Context scope not created or navigator not registered

**Solution**:
```kotlin
// Verify Koin module loaded
startKoin {
    modules(interlockSimModule) // Includes navigationModule
}

// Verify context scope exists
val context = factory.createEmptyContext()
println(context.scope) // Should not be null

// For tests: close context in @AfterEach
@AfterEach
fun tearDown() {
    context.close() // Cleans up scope
}
```

---

### Problem: "Deprecated API warnings in build"

**Symptom**: Compiler warnings about `pathToNextSemaphore()` usage

**Solution**: Migrate to new APIs (see scenarios above). Deprecation level is `WARNING`, code still compiles.

---

### Problem: "PathReservationRegistry state inconsistency"

**Symptom**: Train thinks path is reserved, but blocks show FREE

**Cause**: Direct block reservation without registry update

**Solution**: Always use PathReservationService for reservations:
```kotlin
// ❌ WRONG: Direct block manipulation
block.reserveFrom(semaphore)

// ✅ CORRECT: Use service for atomic reservation
val service = env.getPathReservationService()
service.reservePath(trainId, start, target)
```

---

## Testing

### Unit Tests

**Test TopologyNavigator**:
```kotlin
@Test
fun `TopologyNavigator finds path without state dependencies`() {
    val context = buildEditingContext()
    val navigator = context.getTopologyNavigator()

    val path = navigator.findPathToNextSemaphore(start, next)

    assertThat(path).isNotNull()
    assertThat(path!!.length()).isGreaterThan(0.0)
}
```

**Test PathReservationService**:
```kotlin
@Test
fun `PathReservationService reserves path atomically`() {
    val context = buildSimulationContext()
    val service = context.getPathReservationService()

    val result = service.reservePath("Train1", start, target)

    assertThat(result).isInstanceOf(ReservationResult.Success::class)
    val path = (result as ReservationResult.Success).path
    assertThat(path.blocks).allMatch { it.getState() == TrackFacility.State.RESERVED }
}
```

**Test TrainNavigationService**:
```kotlin
@Test
fun `TrainNavigationService only returns paths reserved for train`() {
    val context = buildSimulationContext()
    val pathService = context.getPathReservationService()
    val trainService = context.getTrainNavigationService()

    // Reserve path for Train1
    pathService.reservePath("Train1", start, target)

    // Train1 should see path
    val path1 = trainService.findReservedPathForTrain("Train1", start, next)
    assertThat(path1).isNotNull()

    // Train2 should NOT see path
    val path2 = trainService.findReservedPathForTrain("Train2", start, next)
    assertThat(path2).isNull()
}
```

### Integration Tests

**Test Scenario: Two Trains, Two Tracks**:
```kotlin
@Test
fun `Shunting loop uses both tracks with new APIs`() {
    val context = loadXML("vyhybna.xml")
    val simulation = ShuntingLoop(context, endTime = 1024)

    simulation.run()

    // Verify both tracks (k1 and k2) were used
    assertThat(simulation.trackUsage["k1"]).isGreaterThan(0)
    assertThat(simulation.trackUsage["k2"]).isGreaterThan(0)
}
```

### Golden Output Validation

**Ensure migration doesn't change behavior**:
```bash
# Before migration
./gradlew runExample -PexampleName=shuntingLoop -PendTime=1024 > baseline.log

# After migration
./gradlew runExample -PexampleName=shuntingLoop -PendTime=1024 > migrated.log

# Compare (allow timing variations)
diff -u baseline.log migrated.log | grep -v "Time:"
```

---

## Summary

### Migration Checklist

- [ ] Identify deprecated API usage (`pathToNextSemaphore`, `getNextTrackSection`)
- [ ] Classify usage: Static topology, dispatcher logic, or train navigation
- [ ] Replace with appropriate service: TopologyNavigator, PathReservationService, or TrainNavigationService
- [ ] Update tests to use new APIs
- [ ] Verify no behavior changes (golden output validation)
- [ ] Remove deprecated API warnings from build

### Quick Reference

| Old API | Use Case | New API |
|---------|----------|---------|
| `pathToNextSemaphore()` | Editing validation | `context.getTopologyNavigator().findPathToNextSemaphore()` |
| `pathToNextSemaphore()` | Dispatcher reservation | `env.getPathReservationService().reservePath()` |
| `pathToNextSemaphore()` | Train navigation | `env.getTrainNavigationService().findReservedPathForTrain()` |
| `getNextTrackSection()` | Static topology | `context.getTopologyNavigator().getNextTrackSection()` |
| `getNextTrackSection()` | Train navigation | `env.getTrainNavigationService().findReservedPathForTrain()` |

---

## Related Documentation

- [PATH_DISCOVERY_ARCHITECTURE.md](PATH_DISCOVERY_ARCHITECTURE.md) - Design rationale and trade-offs
- [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) - Reservation service architecture
- [CONTEXT_REFACTORING_DESIGN.md](CONTEXT_REFACTORING_DESIGN.md) - Context system design
- [Issue #292](https://github.com/bedaHovorka/interlockSim/issues/292) - Path Discovery Restructuring (parent issue)

---

**Migration Status**: ⚠️ In Progress (Phase 5)
**Last Updated**: 2026-01-27
**Next Steps**: Migrate Train.kt and InOutWorker.kt to new APIs
