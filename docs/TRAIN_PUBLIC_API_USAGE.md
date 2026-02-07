# Train Public API - Usage Guide

**Added:** 2026-02-06
**Related Issue:** Public Train API for animation and external observation

## Overview

The Train class now provides a dual API for accessing train state:
1. **Java-style getters** (existing, backward-compatible)
2. **Kotlin property-style accessors** (new, idiomatic Kotlin)

Both APIs provide identical functionality - the property accessors delegate to the getters.

## Available Properties

| Kotlin Property | Java Getter | Type | Description |
|----------------|-------------|------|-------------|
| `trainNumber` | `getNumber()` | `Int` | Unique train identifier (sequential, starts at 1) |
| `trainVelocity` | `getVelocity()` | `Double` | Current velocity in m/s |
| `trainAcceleration` | `getAcceleration()` | `Double` | Current acceleration in m/s² |
| `trainLength` | `getLength()` | `Double` | Train length in meters |
| `totalDistance` | `getTotalDistance()` | `Double` | Total distance traveled since departure (meters) |
| `frontSection` | `getFrontSection()` | `TrackSection?` | Track section where front is located (null if not started) |
| `frontPosition` | `getFrontPosition()` | `Double` | Distance along current section (0.0 to section length) |
| `originInOut` | `getOriginInOut()` | `DynamicInOut` | Entry point for color coding |
| `trainEntrySeparator` | `getEntrySeparator()` | `DynamicPathSeparator?` | Separator where train entered current section |

## Usage Examples

### Example 1: Java-Style Getters (Existing Code)

```kotlin
// From AnimationStateCapture.kt (existing code, unchanged)
private fun captureTrainState(train: Train, ...): TrainState {
    val trainNumber = train.getNumber()
    val position = train.getTotalDistance()
    val velocity = train.getVelocity()
    val acceleration = train.getAcceleration()
    val length = train.getLength()
    
    val currentSection = train.getFrontSection()
    val frontPosition = train.getFrontPosition()
    
    return TrainState(
        trainNumber = trainNumber,
        position = position,
        velocity = velocity,
        acceleration = acceleration,
        length = length,
        // ...
    )
}
```

### Example 2: Kotlin Property-Style Accessors (New, Idiomatic)

```kotlin
// Equivalent to Example 1, but more idiomatic Kotlin
private fun captureTrainState(train: Train, ...): TrainState {
    return TrainState(
        trainNumber = train.trainNumber,
        position = train.totalDistance,
        velocity = train.trainVelocity,
        acceleration = train.trainAcceleration,
        length = train.trainLength,
        frontGridLocation = calculateLocation(
            train.frontSection,
            train.frontPosition
        ),
        travelingRight = train.originInOut.name == "B"
    )
}
```

### Example 3: Mixed Usage (Both Styles Work)

```kotlin
// You can mix both styles - they're equivalent
fun monitorTrain(train: Train) {
    println("Train #${train.trainNumber} status:")  // Kotlin property
    println("  Velocity: ${train.getVelocity()} m/s")  // Java getter
    println("  Position: ${train.totalDistance} m")  // Kotlin property
    println("  Length: ${train.getLength()} m")  // Java getter
}
```

### Example 4: Animation Rendering

```kotlin
// Render train overlay on canvas
fun renderTrainOverlay(g: Graphics2D, train: Train) {
    val x = calculateX(train.frontPosition, train.frontSection)
    val y = calculateY(train.frontPosition, train.frontSection)
    
    // Draw train body
    g.color = getTrainColor(train.originInOut)
    g.fillRect(x, y, train.trainLength.toInt(), 10)
    
    // Draw velocity indicator
    if (train.trainVelocity > 0) {
        drawVelocityArrow(g, x, y, train.trainVelocity)
    }
    
    // Draw train number
    g.drawString("T${train.trainNumber}", x, y - 5)
}
```

### Example 5: Statistics Collection

```kotlin
// Collect train performance statistics
data class TrainStatistics(
    val trainId: Int,
    val averageSpeed: Double,
    val maxSpeed: Double,
    val distanceTraveled: Double
)

class TrainMonitor {
    private val speedSamples = mutableListOf<Double>()
    
    fun recordSnapshot(train: Train) {
        speedSamples.add(train.trainVelocity)
    }
    
    fun getStatistics(train: Train): TrainStatistics {
        return TrainStatistics(
            trainId = train.trainNumber,
            averageSpeed = speedSamples.average(),
            maxSpeed = speedSamples.maxOrNull() ?: 0.0,
            distanceTraveled = train.totalDistance
        )
    }
}
```

### Example 6: Logging and Debugging

```kotlin
// Debug logging with property accessors
fun logTrainState(train: Train, logger: Logger) {
    logger.debug {
        """
        Train ${train.trainNumber} state:
          Position: ${train.totalDistance}m (section: ${train.frontSection?.name ?: "none"})
          Velocity: ${train.trainVelocity}m/s
          Acceleration: ${train.trainAcceleration}m/s²
          Length: ${train.trainLength}m
          Origin: ${train.originInOut.name}
          Entry separator: ${train.trainEntrySeparator?.name ?: "none"}
        """.trimIndent()
    }
}
```

## Migration Guide

### Existing Code (Java Getters)
No migration required! All existing code using Java-style getters continues to work without any changes.

### New Code (Kotlin Properties)
For new Kotlin code, prefer property-style accessors for better readability:

```kotlin
// OLD (Java-style, still works)
if (train.getVelocity() > maxSpeed) {
    println("Train ${train.getNumber()} exceeding speed limit")
}

// NEW (Kotlin-style, preferred for new code)
if (train.trainVelocity > maxSpeed) {
    println("Train ${train.trainNumber} exceeding speed limit")
}
```

## Design Rationale

### Why Dual API?

1. **Backward Compatibility**: Preserves existing code using Java-style getters
2. **Kotlin Idioms**: Provides idiomatic Kotlin property accessors
3. **Zero Breaking Changes**: Purely additive API enhancement
4. **Single Source of Truth**: Properties delegate to getters (no code duplication)

### Why Property Names?

- `trainNumber` (not `number`) - Avoids collision with private field
- `trainVelocity` (not `velocity`) - Avoids collision with private Variable field
- `trainAcceleration` (not `acceleration`) - Avoids collision with private Variable field
- `trainLength` (not `length`) - Avoids collision with private field
- `trainEntrySeparator` (not `entrySeparator`) - Avoids collision with private field
- Other properties use simple names where no collision exists

### Thread Safety

All properties are read-only and delegate to existing thread-safe getters. The animation system uses these properties from the Swing EDT thread while the simulation runs on a separate jDisco thread, with proper synchronization through PropertyChangeEvents.

## Testing

See `TrainPublicAPITest.kt` for comprehensive test coverage:
- Property accessor delegation validation
- Consistency between getters and properties
- Null safety for optional properties (frontSection, trainEntrySeparator)

## Related Documentation

- `docs/ANIMATION_ARCHITECTURE.md` - Animation system architecture
- `src/main/kotlin/cz/vutbr/fit/interlockSim/gui/animation/AnimationStateCapture.kt` - Real-world usage
- `src/test/kotlin/cz/vutbr/fit/interlockSim/sim/TrainPublicAPITest.kt` - Test examples
