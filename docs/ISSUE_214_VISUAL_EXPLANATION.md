# Issue #214: Track Wrapping Strategy - Before and After

## Architecture Overview

This diagram illustrates the change in track wrapper creation strategy from lazy to eager.

### Before (Lazy Creation - INCONSISTENT)

```
┌─────────────────────────────────────────────────────────────┐
│                  DefaultSimulationContext                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  run() initialization:                                       │
│  ┌────────────────────────────────────────────────┐         │
│  │ initializeDynamicMapping()                     │         │
│  │                                                │         │
│  │ ✅ PathSeparators wrapped EAGERLY:            │         │
│  │    • InOut                                     │         │
│  │    • RailSemaphore                            │         │
│  │    • RailSwitch                               │         │
│  │    → staticToDynamicMap populated             │         │
│  │                                                │         │
│  │ ⚠️  Tracks wrapped EAGERLY TOO:               │         │
│  │    • Iterate through graph.values()           │         │
│  │    • Create DynamicTrack for each             │         │
│  │    → staticTrackToDynamicMap populated        │         │
│  └────────────────────────────────────────────────┘         │
│                                                              │
│  During simulation:                                          │
│  ┌────────────────────────────────────────────────┐         │
│  │ toDynamic(TrackFacility) - LAZY FALLBACK      │         │
│  │                                                │         │
│  │  if (track in map):                           │         │
│  │    return existing wrapper ✅                 │         │
│  │  else:                                        │         │
│  │    create new wrapper ⚠️  (INCONSISTENT!)    │         │
│  │    add to map                                 │         │
│  │    return new wrapper                         │         │
│  └────────────────────────────────────────────────┘         │
│                                                              │
└─────────────────────────────────────────────────────────────┘

Problems:
❌ Inconsistent: Some wrappers created eagerly, some lazily
❌ Unpredictable: Memory layout changes during simulation
❌ Hidden bugs: Missing initialization errors not caught
❌ Performance: Lazy creation adds overhead
```

### After (Eager Creation - CONSISTENT)

```
┌─────────────────────────────────────────────────────────────┐
│                  DefaultSimulationContext                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  run() initialization:                                       │
│  ┌────────────────────────────────────────────────┐         │
│  │ initializeDynamicMapping()                     │         │
│  │                                                │         │
│  │ ✅ PathSeparators wrapped EAGERLY:            │         │
│  │    • InOut                                     │         │
│  │    • RailSemaphore                            │         │
│  │    • RailSwitch                               │         │
│  │    → staticToDynamicMap populated             │         │
│  │                                                │         │
│  │ ✅ Tracks wrapped EAGERLY:                    │         │
│  │    • Iterate through graph.values()           │         │
│  │    • Create DynamicTrack for each             │         │
│  │    → staticTrackToDynamicMap populated        │         │
│  └────────────────────────────────────────────────┘         │
│                                                              │
│  During simulation:                                          │
│  ┌────────────────────────────────────────────────┐         │
│  │ toDynamic(TrackFacility) - EAGER LOOKUP       │         │
│  │                                                │         │
│  │  if (track in map):                           │         │
│  │    return existing wrapper ✅                 │         │
│  │  else:                                        │         │
│  │    throw IllegalStateException ⚠️             │         │
│  │    (track should have been wrapped!)          │         │
│  └────────────────────────────────────────────────┘         │
│                                                              │
└─────────────────────────────────────────────────────────────┘

Benefits:
✅ Consistent: All wrappers created at initialization
✅ Predictable: Memory layout stable after run()
✅ Fail-fast: Missing wrappers caught immediately
✅ Performance: No lazy creation overhead
```

## Timeline Comparison

### Before (Lazy Creation)

```
Time →
├─────────────────┼─────────────────┼─────────────────┤
│ Initialization  │   Simulation    │   Simulation    │
│                 │   (first use)   │   (later)       │
├─────────────────┼─────────────────┼─────────────────┤

PathSeparators:   [CREATE WRAPPER]  [USE WRAPPER]     [USE WRAPPER]
                       ↑                 ↑                 ↑
                     EAGER             FAST              FAST

Tracks:           [    SKIP      ]  [CREATE WRAPPER]  [USE WRAPPER]
                                         ↑                 ↑
                                       LAZY              FAST
                                      (SLOW)

Problem: First track access slower, inconsistent with separators
```

### After (Eager Creation)

```
Time →
├─────────────────┼─────────────────┼─────────────────┤
│ Initialization  │   Simulation    │   Simulation    │
│                 │   (first use)   │   (later)       │
├─────────────────┼─────────────────┼─────────────────┤

PathSeparators:   [CREATE WRAPPER]  [USE WRAPPER]     [USE WRAPPER]
                       ↑                 ↑                 ↑
                     EAGER             FAST              FAST

Tracks:           [CREATE WRAPPER]  [USE WRAPPER]     [USE WRAPPER]
                       ↑                 ↑                 ↑
                     EAGER             FAST              FAST

Benefit: All track accesses fast, consistent with separators
```

## Code Comparison

### Before (Lazy Creation with Fallback)

```kotlin
override fun toDynamic(track: TrackFacility): DynamicTrack {
    // Return existing wrapper if already mapped
    staticTrackToDynamicMap[track]?.let { return it }

    // Create new wrapper for unmapped track (lazy initialization)
    val dynamicTrack = DynamicTrack(track)
    staticTrackToDynamicMap[track] = dynamicTrack
    logger.debug { "Lazy-created DynamicTrack wrapper for track ${System.identityHashCode(track)}" }
    return dynamicTrack
}
```

**Issues:**
- ⚠️  Hides initialization bugs (creates wrapper on-demand)
- ⚠️  Inconsistent with PathSeparator strategy
- ⚠️  Adds overhead to first track access

### After (Eager Creation with Error)

```kotlin
override fun toDynamic(track: TrackFacility): DynamicTrack {
    // All tracks should be wrapped during initialization
    return staticTrackToDynamicMap[track]
        ?: throw IllegalStateException(
            "Dynamic wrapper not found for track: ${System.identityHashCode(track)} " +
                "(${track.javaClass.simpleName}). " +
                "Map contains ${staticTrackToDynamicMap.size} entries. " +
                "This indicates the track was not registered during initialization. " +
                "Ensure initializeDynamicMapping() completed successfully before simulation starts."
        )
}
```

**Benefits:**
- ✅ Catches initialization bugs immediately
- ✅ Consistent with PathSeparator strategy
- ✅ Fast lookup (no lazy creation overhead)

## Initialization Flow

### initializeDynamicMapping() - Already Eager!

```kotlin
internal fun initializeDynamicMapping() {
    // ... PathSeparator wrapping (lines 682-733) ...
    
    // Track wrapping (lines 736-763)
    var trackMappedCount = 0
    val graph = getGraph()
    for (trackBlock in graph.values()) {
        val trackFacility = trackBlock as TrackFacility
        
        // Skip if already mapped
        if (staticTrackToDynamicMap.containsKey(trackFacility)) {
            continue
        }
        
        // ✅ CREATE WRAPPER EAGERLY
        val dynamicTrack = DynamicTrack(trackFacility)
        staticTrackToDynamicMap[trackFacility] = dynamicTrack
        trackMappedCount++
        
        // Recursively map internal sections
        mapInternalSections(trackBlock)
    }
    logger.debug { "Initialized $trackMappedCount dynamic track wrappers" }
}
```

**Key Point:** This method was **already wrapping all tracks eagerly**! The issue was that `toDynamic()` had a lazy fallback that was never actually needed.

## Impact on Simulation Flow

### Normal Case (Track in Context)

```
┌─────────────┐
│ Simulation  │
│   Code      │
└──────┬──────┘
       │
       │ context.toDynamic(track)
       │
       ▼
┌─────────────────────────────┐
│ staticTrackToDynamicMap     │
│                             │
│ track1 → DynamicTrack1      │
│ track2 → DynamicTrack2  ← ✅ FOUND
│ track3 → DynamicTrack3      │
└─────────────────────────────┘
       │
       │ return DynamicTrack2
       ▼
┌─────────────┐
│ Simulation  │
│   Code      │
└─────────────┘
```

**Before:** Lookup in map → Found → Return
**After:** Lookup in map → Found → Return
**Impact:** No change ✅

### Error Case (Track Not in Context)

```
┌─────────────┐
│ Simulation  │
│   Code      │
└──────┬──────┘
       │
       │ context.toDynamic(orphanTrack)
       │
       ▼
┌─────────────────────────────┐
│ staticTrackToDynamicMap     │
│                             │
│ track1 → DynamicTrack1      │
│ track2 → DynamicTrack2      │
│ track3 → DynamicTrack3      │
│                             │
│ orphanTrack → ❌ NOT FOUND  │
└─────────────────────────────┘
       │
       ▼
Before: Create wrapper lazily (hides bug)
After:  Throw exception (catches bug) ✅
```

**Impact:** Bugs caught earlier, easier to debug ✅

## Summary

### The Change

**One-line summary:** Removed lazy creation fallback from `toDynamic(TrackFacility)`

### Why It Works

1. **Tracks already wrapped** in `initializeDynamicMapping()` (lines 736-763)
2. **Lazy fallback never needed** - all tracks in graph are wrapped
3. **Only affects error case** - tracks not in context now throw exception

### Benefits

1. ✅ **Consistency** - Same strategy as PathSeparators
2. ✅ **Predictability** - All wrappers exist after initialization
3. ✅ **Performance** - No lazy creation overhead
4. ✅ **Debugging** - Fail-fast error detection
5. ✅ **Simplicity** - Less complex code

### Backward Compatibility

✅ **Fully compatible** - Only error case behavior changes (from lazy creation to exception)
