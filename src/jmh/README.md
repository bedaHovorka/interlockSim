# Performance Benchmarks (JMH)

This directory contains performance benchmarks for the Railway Interlocking Simulator using the Java Microbenchmark Harness (JMH).

## Overview

Performance tests have been migrated from the JUnit test suite to JMH to provide more accurate and reliable performance measurements. JMH provides:

- **Proper warmup**: JVM warm-up iterations to stabilize JIT compilation
- **Statistical analysis**: Multiple measurement iterations with confidence intervals
- **Fork isolation**: Separate JVM forks to avoid cross-benchmark interference
- **Dead code elimination prevention**: Ensures the JVM doesn't optimize away benchmark code

## Running Benchmarks

### Run all benchmarks
```bash
./gradlew jmh
```

### Results
Benchmark results are saved to:
- Text report: `build/results/jmh/results.txt`
- JSON report: `build/reports/jmh/results.json` (if configured)

## Available Benchmarks

### GridStoragePerformance
**Location**: `cz.vutbr.fit.interlockSim.benchmarks.GridStoragePerformance`  
**Purpose**: Compares Array2DMap vs TreeMap performance for railway grid cell lookups

**Benchmarks**:
- `measureCustomGridLookup()` - Tests Array2DMap lookup performance
- `measureStandardTreeLookup()` - Baseline TreeMap performance comparison

**Original Issue**: #216 - Migrated from `Array2DMapTest.testSpeed()`

**Historical Context**: The original Java implementation showed that Array2DMap provides minimal speedup over TreeMap for bulk lookups, but eliminates Point object creation when using direct `get(Int, Int)` access.

**Recent Results** (2026-02-06):
```
Array2DMap:   3,106 ns/op ± 25 ns  (5x faster)
TreeMap:     15,359 ns/op ± 527 ns (baseline)
```

## Adding New Benchmarks

1. Create a new Kotlin file in `src/jmh/kotlin/cz/vutbr/fit/interlockSim/benchmarks/`
2. Use JMH annotations:
   - `@State(Scope.Thread)` for benchmark state
   - `@Benchmark` for benchmark methods
   - `@Setup` for initialization
   - `@BenchmarkMode`, `@OutputTimeUnit`, `@Measurement`, `@Warmup`, `@Fork` for configuration

3. Follow the naming convention: `*Benchmark.kt`

Example:
```kotlin
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class MyBenchmark {
    
    @Setup(Level.Trial)
    fun setup() {
        // Initialize test data
    }
    
    @Benchmark
    fun measureSomething(): Int {
        // Your benchmark code
        return result
    }
}
```

## JMH Configuration

JMH configuration is defined in `build.gradle.kts`:

- **Forks**: 2 separate JVM processes
- **Warmup**: 3 iterations (1 second each)
- **Measurement**: 5 iterations (1 second each)
- **Time Unit**: Nanoseconds
- **Result Format**: Text (can be changed to JSON for analysis)

## Best Practices

1. **Avoid I/O in benchmarks** - Focus on computational performance
2. **Use consistent test data** - Seed random generators for reproducibility
3. **Return benchmark results** - Prevents dead code elimination
4. **Run multiple forks** - Minimizes measurement noise
5. **Warm up properly** - Allow JIT compiler to stabilize

## References

- JMH Documentation: https://github.com/openjdk/jmh
- JMH Samples: https://github.com/openjdk/jmh/tree/master/jmh-samples
- Issue #216: Migrate performance tests to JMH benchmarks
