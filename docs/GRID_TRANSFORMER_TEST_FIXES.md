# GridTransformerTest Compilation Fixes Needed

## Summary
The GridTransformerTest.kt file has compilation errors due to incorrect constructor signatures and method names. This document lists all fixes needed.

## Global Changes

### 1. Grid Creation
**Wrong:**
```kotlin
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
```
**Correct:**
```kotlin
val staticGrid = DefaultRailWayNetGrid(10, 10)
```
Reason: DefaultRailWayNetGrid is not generic - hardcoded to Cell type.

### 2. Adding Cells to Grid
**Wrong:**
```kotlin
staticGrid.putCell(point, cell)
```
**Correct:**
```kotlin
staticGrid.put(point, cell)
```
Reason: Method is named `put`, not `putCell`.

### 3. RailSwitch Constructor
**Wrong:**
```kotlin
RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
```
**Correct:**
```kotlin
RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
```
or use the simpler 2-arg constructor:
```kotlin
RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
```

Full constructor signature:
```kotlin
constructor(
    spatialType: Cell.SpatialType,
    type: Type,
    mainSpeed: Double,
    branchSpeed: Double
)
```

Reason: 
- Parameters are in different order (spatialType first, type second)
- No `NORMAL` type - use `SIMPLE_RIGHT_FALSE`, `SIMPLE_RIGHT_TRUE`, `SIMPLE_LEFT_FALSE`, or `SIMPLE_LEFT_TRUE`

### 4. RailSemaphore Constructor
**Wrong:**
```kotlin
RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
```
**Correct:**
```kotlin
RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
```
This one is actually correct!

Constructor signature:
```kotlin
open class RailSemaphore(
    orientation: Boolean,
    spatialType: Cell.SpatialType
)
```

### 5. InOut Constructor
**Wrong:**
```kotlin
val inOut = InOut(true)
```
**Correct:**
```kotlin
val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
```

Constructor signature:
```kotlin
class InOut(
    name: String,
    orientation: Boolean,
    spatialType: Cell.SpatialType
)
```

Reason: InOut requires name, orientation, and spatialType.

### 6. SimpleTrackBlock Constructor
**Wrong:**
```kotlin
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, Cell.Segment.A)
```
**Correct:**
```kotlin
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, 100.0, 80.0, 80.0)
```

Constructor signature:
```kotlin
constructor(
    end1: PathSeparator, 
    end2: PathSeparator, 
    length: Double, 
    maxSpeed1: Double, 
    maxSpeed2: Double
)
```

Reason: SimpleTrackBlock takes length and speeds, not a Segment.

### 7. AssertK isInstanceOf Usage
**Wrong:**
```kotlin
assertThat(dynamicCell).isInstanceOf(DynamicRailSwitch::class)
```
**Correct:**
```kotlin
assertThat(dynamicCell).isInstanceOf(DynamicRailSwitch::class.java)
```

Reason: AssertK expects a Java Class, not KClass.

## Line-by-Line Fixes

### Line 65
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
```

### Line 83-86
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSwitch = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
railSwitch.setName("switch1")
staticGrid.putCell(Point(5, 5), railSwitch)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
railSwitch.setName("switch1")
staticGrid.put(Point(5, 5), railSwitch)
```

### Line 94
```kotlin
// Before
assertThat(dynamicCell).isInstanceOf(DynamicRailSwitch::class)
// After
assertThat(dynamicCell).isInstanceOf(DynamicRailSwitch::class.java)
```

### Line 108-111
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
railSemaphore.setName("sem1")
staticGrid.putCell(Point(3, 3), railSemaphore)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
railSemaphore.setName("sem1")
staticGrid.put(Point(3, 3), railSemaphore)
```

### Line 119
```kotlin
// Before
assertThat(dynamicCell).isInstanceOf(DynamicPathSeparator::class)
// After
assertThat(dynamicCell).isInstanceOf(DynamicPathSeparator::class.java)
```

### Line 130-133
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val inOut = InOut(true)
inOut.setName("A")
staticGrid.putCell(Point(2, 2), inOut)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
staticGrid.put(Point(2, 2), inOut)
```

### Line 141
```kotlin
// Before
assertThat(dynamicCell).isInstanceOf(DynamicInOut::class)
// After
assertThat(dynamicCell).isInstanceOf(DynamicInOut::class.java)
```

### Line 203-209
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSwitch = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, Cell.Segment.A)
val trackBlockPart = TrackBlockPart(trackBlock, Cell.Segment.A)
staticGrid.putCell(Point(1, 1), railSwitch)
staticGrid.putCell(Point(2, 2), trackBlockPart)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, 100.0, 80.0, 80.0)
val trackBlockPart = TrackBlockPart(trackBlock, Cell.Segment.A)
staticGrid.put(Point(1, 1), railSwitch)
staticGrid.put(Point(2, 2), trackBlockPart)
```

### Line 230-234
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSwitch = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
staticGrid.putCell(Point(1, 1), railSwitch)
staticGrid.putCell(Point(2, 2), railSemaphore)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
staticGrid.put(Point(1, 1), railSwitch)
staticGrid.put(Point(2, 2), railSemaphore)
```

### Line 251-253
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSwitch = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
staticGrid.putCell(Point(5, 5), railSwitch)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
staticGrid.put(Point(5, 5), railSwitch)
```

### Line 261
```kotlin
// Before
assertThat(dynamicCell).isInstanceOf(DynamicPathSeparator::class)
// After
assertThat(dynamicCell).isInstanceOf(DynamicPathSeparator::class.java)
```

### Line 329
```kotlin
// Before
assertThat(dynamicInOut).isInstanceOf(DynamicInOut::class)
// After
assertThat(dynamicInOut).isInstanceOf(DynamicInOut::class.java)
```

### Line 363-376
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
// Add 100 cells across the grid
for (i in 0 until 100) {
    val x = i % 100
    val y = i / 100
    val cell = if (i % 3 == 0) {
        RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
    } else if (i % 3 == 1) {
        RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
    } else {
        InOut(true)
    }
    staticGrid.putCell(Point(x, y), cell as Cell)
}

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
// Add 100 cells across the grid
for (i in 0 until 100) {
    val x = i % 100
    val y = i / 100
    val cell = if (i % 3 == 0) {
        RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
    } else if (i % 3 == 1) {
        RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
    } else {
        InOut("InOut$i", true, Cell.SpatialType.HORIZONTAL)
    }
    staticGrid.put(Point(x, y), cell as Cell)
}
```

### Line 397-401
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val railSwitch = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, Cell.Segment.A)
val trackBlockPart = TrackBlockPart(trackBlock, Cell.Segment.A)
staticGrid.putCell(Point(1, 1), trackBlockPart)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, 100.0, 80.0, 80.0)
val trackBlockPart = TrackBlockPart(trackBlock, Cell.Segment.A)
staticGrid.put(Point(1, 1), trackBlockPart)
```

### Line 419-423
```kotlin
// Before
val staticGrid = DefaultRailWayNetGrid<Cell>(10, 10)
val topLeft = RailSwitch(RailSwitch.Type.NORMAL, Cell.SpatialType.HORIZONTAL)
val bottomRight = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
staticGrid.putCell(Point(0, 0), topLeft)
staticGrid.putCell(Point(9, 9), bottomRight)

// After
val staticGrid = DefaultRailWayNetGrid(10, 10)
val topLeft = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val bottomRight = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
staticGrid.put(Point(0, 0), topLeft)
staticGrid.put(Point(9, 9), bottomRight)
```

## Notes
- All fixes are straightforward find-and-replace operations
- No logic changes needed
- After fixing, tests should compile and run successfully
