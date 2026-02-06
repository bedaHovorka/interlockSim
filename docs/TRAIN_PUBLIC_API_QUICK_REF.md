# Train Public API - Quick Reference

## 🎯 What Changed?

Added **Kotlin property-style accessors** to Train class for more idiomatic Kotlin code.

## 📊 API Comparison

### Core Properties

| Description | Java-style (existing) | Kotlin-style (new) |
|-------------|----------------------|-------------------|
| Train identifier | `train.getNumber()` | `train.trainNumber` |
| Current velocity | `train.getVelocity()` | `train.trainVelocity` |
| Current acceleration | `train.getAcceleration()` | `train.trainAcceleration` |
| Train length | `train.getLength()` | `train.trainLength` |

### Position Properties

| Description | Java-style (existing) | Kotlin-style (new) |
|-------------|----------------------|-------------------|
| Total distance traveled | `train.getTotalDistance()` | `train.totalDistance` |
| Current track section | `train.getFrontSection()` | `train.frontSection` |
| Position in section | `train.getFrontPosition()` | `train.frontPosition` |

### Origin Properties

| Description | Java-style (existing) | Kotlin-style (new) |
|-------------|----------------------|-------------------|
| Entry point | `train.getOriginInOut()` | `train.originInOut` |
| Entry separator | `train.getEntrySeparator()` | `train.trainEntrySeparator` |

## 💡 Quick Examples

### Before (Java-style only)
```kotlin
println("Train ${train.getNumber()}: velocity=${train.getVelocity()}, distance=${train.getTotalDistance()}")
```

### After (Both styles available)
```kotlin
// Option 1: Java-style (still works)
println("Train ${train.getNumber()}: velocity=${train.getVelocity()}, distance=${train.getTotalDistance()}")

// Option 2: Kotlin-style (new, more idiomatic)
println("Train ${train.trainNumber}: velocity=${train.trainVelocity}, distance=${train.totalDistance}")
```

## ✅ Key Benefits

1. **Backward Compatible** - All existing code continues to work unchanged
2. **Idiomatic Kotlin** - Property syntax is more natural in Kotlin
3. **Zero Risk** - Properties just delegate to existing getters
4. **Well Tested** - 13 unit tests validate behavior
5. **Well Documented** - Usage guide with 6 examples

## 📚 Full Documentation

- **Usage Guide:** `docs/TRAIN_PUBLIC_API_USAGE.md` (6 examples, migration guide)
- **Implementation:** `docs/TRAIN_PUBLIC_API_IMPLEMENTATION.md` (design rationale, alternatives)
- **Tests:** `src/test/kotlin/.../sim/TrainPublicAPITest.kt` (13 test cases)

## 🚀 Migration (Optional)

No migration required! Both APIs work identically. Use whichever style you prefer:

```kotlin
// Old code (keep it, still works)
if (train.getVelocity() > 20.0) { /* ... */ }

// New code (optional, more idiomatic)
if (train.trainVelocity > 20.0) { /* ... */ }
```

## 🎨 Real-World Usage

### Animation State Capture
```kotlin
// Current implementation (AnimationStateCapture.kt)
TrainState(
    trainNumber = train.getNumber(),        // Can use: train.trainNumber
    velocity = train.getVelocity(),         // Can use: train.trainVelocity
    acceleration = train.getAcceleration(), // Can use: train.trainAcceleration
    position = train.getTotalDistance(),    // Can use: train.totalDistance
    length = train.getLength()              // Can use: train.trainLength
)
```

### Train Monitoring
```kotlin
fun monitorTrain(train: Train) {
    logger.info { 
        "Train ${train.trainNumber} at ${train.totalDistance}m, " +
        "speed ${train.trainVelocity}m/s" 
    }
}
```

### Performance Statistics
```kotlin
data class TrainStats(
    val id: Int = train.trainNumber,
    val maxSpeed: Double = train.trainVelocity,
    val distance: Double = train.totalDistance
)
```

## ⚡ Performance

- **Runtime Cost:** Negligible (inline delegation)
- **Memory Cost:** Zero (computed on access)
- **Thread Safety:** Maintained (delegates to thread-safe getters)

## 🔍 See Also

- Issue: "Add public Train API for animation and external observation"
- Related: AnimatedSim Milestone (Issue #268) - Complete 2026-02-04
- Related: Train Overlay Rendering (Issue #203) - Complete
