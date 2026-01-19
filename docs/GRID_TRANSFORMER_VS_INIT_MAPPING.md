# GridTransformer vs initializeDynamicMapping

## Overview

This document clarifies the relationship between `GridTransformer.transformGrid()` and `DefaultSimulationContext.initializeDynamicMapping()`.

## Current State

### GridTransformer.transformGrid()
- **Purpose**: Create a NEW grid with dynamic wrappers (Phase 5/9 of grid parameterization)
- **Output**: New `DefaultRailWayNetGrid` + `IdentityHashMap<NodeCell, DynamicPathSeparator>`
- **Use case**: Static/dynamic grid separation architecture
- **Status**: New implementation (Issue #131.5)

### DefaultSimulationContext.initializeDynamicMapping()
- **Purpose**: Create mapping of static cells to dynamic wrappers  
- **Output**: `IdentityHashMap<NodeCell, DynamicPathSeparator>` (no new grid)
- **Use case**: Backward compatibility - maintains single grid with mapping
- **Status**: Existing implementation

## Code Duplication

Both implementations contain similar logic for creating dynamic wrappers:
```kotlin
when (cell) {
    is RailSwitch -> DynamicRailSwitch(cell)
    is RailSemaphore -> createDynamicInstance(cell)
    is InOut -> createDynamic(cell)
}
```

## Future Refactoring

As part of the grid parameterization effort (#131), `DefaultSimulationContext` should eventually be refactored to use `GridTransformer`:

```kotlin
// Future implementation
private fun initializeDynamicMapping() {
    val result = GridTransformer.transformGrid(getRailWayNetGrid())
    // Use result.dynamicGrid as the new grid
    // Use result.staticToDynamicMap for mapping
    staticToDynamicMap = result.staticToDynamicMap
    // Additional context-specific initialization...
}
```

This refactoring should be done in a later phase when:
1. Grid is fully parameterized (Phase 9/9)
2. DefaultSimulationContext can work with `RailwayNetGrid<DynamicPathSeparator>`
3. All simulation code has been updated to use the dynamic grid

## Current Decision

For Issue #131.5, we keep both implementations separate:
- `GridTransformer` provides the new transformation capability
- `initializeDynamicMapping()` remains unchanged to avoid breaking existing code
- Future phases will consolidate the implementations

## Link

This document is referenced from:
- [GRID_TRANSFORMER_TEST_FIXES.md](GRID_TRANSFORMER_TEST_FIXES.md)
- [GRID_TRANSFORMATION_DESIGN.md](GRID_TRANSFORMATION_DESIGN.md)
