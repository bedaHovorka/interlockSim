# Array2DMap Pathfinding Extensions - Usage Examples

This document demonstrates how to use the new Kotlin-idiomatic Array2DMap extensions for pathfinding algorithms.

## Overview

The Array2DMap extensions provide a clean, functional API for:
- Ordered navigation through grid cells
- Finding neighbors for graph-based search
- Spatial range queries
- Distance-based searches

All methods are implemented as Kotlin extension functions, keeping the core `Array2DMap` class unchanged.

## Basic Navigation

### First and Last Points

```kotlin
val grid: Array2DMap<Cell> = getRailwayGrid()

// Get the first point in grid order (lowest y, then lowest x)
val startPoint = grid.firstPoint() // e.g., Point(0, 0)

// Get the last point in grid order (highest y, then highest x)
val endPoint = grid.lastPoint() // e.g., Point(50, 50)
```

### Neighbor Queries for Pathfinding

```kotlin
// Get 4-connected neighbors for pathfinding
for (neighbor in grid.neighbors4(current)) {
    val cell = grid[neighbor]!!
    if (isTraversable(cell)) {
        exploreNeighbor(neighbor)
    }
}

// Get 8-connected neighbors (including diagonals)
for (neighbor in grid.neighbors8(current)) {
    checkNeighbor(neighbor)
}
```

## Distance-Based Queries

### Manhattan Distance Search

```kotlin
// Find all cells within a certain movement distance
val nearby = grid.pointsWithinManhattan(center, maxDistance)
    .filter { point -> isAccessible(grid[point]) }
    .toList()
```

### Rectangular Region Query

```kotlin
// Get all cells in a rectangular bounding box
val regionCells = grid.pointsInRegion(minX, minY, maxX, maxY)
```

## See Also

- `Array2DMap.kt` - Core grid implementation
- `Array2DMapExtensionsTest.kt` - Comprehensive test suite with examples
- LONG_TERM_GOALS.md Goal 2 - Automatic Path Finding feature using these extensions
