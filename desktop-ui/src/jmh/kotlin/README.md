# JMH Performance Benchmarks

This directory contains Java Microbenchmark Harness (JMH) benchmarks for the Railway Interlocking Simulator.

## Prerequisites

### kDisco Dependency

Benchmarks require the kDisco simulation library to compile. Two options:

**Option 1: GitHub Packages (Recommended for CI/CD)**

Set environment variables:
```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
```

**Option 2: Local Maven Install (Recommended for Development)**

```bash
# Clone kDisco repository
cd ~/work
git clone https://github.com/bedaHovorka/kdisco.git
cd kdisco

# Build and install to local Maven repository
./gradlew :kdisco-core:publishToMavenLocal

# Return to interlockSim
cd ~/work/interlockSim
```

Verify installation:
```bash
ls ~/.m2/repository/cz/hovorka/kdisco/kdisco-core-jvm/0.3.0-SNAPSHOT/
# Should show: kdisco-core-jvm-0.3.0-SNAPSHOT.jar, kdisco-core-jvm-0.3.0-SNAPSHOT.pom
```

## Overview

JMH is the industry-standard tool for accurate microbenchmarking in Java/Kotlin applications. It handles:
- JIT compiler warmup cycles
- Dead code elimination prevention
- Statistical analysis with confidence intervals
- Protection against common benchmarking pitfalls

## Available Benchmarks

### Koin DI Performance (Issue #219)

**File:** `di/KoinPerformanceBenchmark.kt`

Measures the performance overhead introduced by Koin dependency injection:

1. **Railway Network Loading**
   - `railwayNetworkLoading_WithoutDI()` - Baseline (direct instantiation)
   - `railwayNetworkLoading_WithKoin()` - With DI container
   - **Target:** < 10ms overhead

2. **Train Simulation Setup**
   - `trainSimulationSetup_WithoutDI()` - Baseline
   - `trainSimulationSetup_WithKoin()` - With DI container
   - **Target:** < 5% increase

3. **Factory Resolution**
   - `factoryResolution_RepeatedLookups()` - DI resolution performance
   - **Target:** Sub-microsecond after warmup

**Railway Context:**  
In real railway interlocking systems, timing is critical for safety. These benchmarks ensure our DI framework doesn't introduce unacceptable delays.

## Running Benchmarks

### Run All Benchmarks

```bash
./gradlew jmh
```

### Run Specific Benchmark

```bash
./gradlew jmh --includes="KoinPerformanceBenchmark"
```

### Run Specific Method

```bash
./gradlew jmh --includes="KoinPerformanceBenchmark.railwayNetworkLoading.*"
```

### Custom JMH Options

```bash
./gradlew jmh --includes="KoinPerformanceBenchmark" \
  -Pjmh.warmupIterations=5 \
  -Pjmh.iterations=10 \
  -Pjmh.fork=5
```

## Viewing Results

### Human-Readable Format

```bash
cat build/reports/jmh/results.txt
```

### JSON Format (for CI/CD integration)

```bash
cat build/reports/jmh/results.json
```

### Sample Output

```
Benchmark                                                Mode  Cnt  Score   Error  Units
KoinPerformanceBenchmark.railwayNetworkLoading_WithoutDI  avgt   15  8.234 ± 0.421   ms
KoinPerformanceBenchmark.railwayNetworkLoading_WithKoin   avgt   15  9.107 ± 0.538   ms
KoinPerformanceBenchmark.factoryResolution_RepeatedLookups avgt  15  0.312 ± 0.024   μs
```

**Analysis:** Koin adds ~0.9ms overhead (10.6% increase), which is acceptable for non-real-time simulation.

## Benchmark Configuration

Default settings in `build.gradle.kts`:

- **Warmup:** 3 iterations × 1 second
- **Measurement:** 5 iterations × 2 seconds
- **Forks:** 3 (separate JVM invocations)
- **JVM Args:** `-Xmx2g -Xms2g`
- **GC:** Forced between iterations

These settings balance accuracy with execution time (~5-10 minutes per benchmark class).

## Adding New Benchmarks

1. Create benchmark class in appropriate package under `src/jmh/kotlin/`
2. Annotate class with `@State(Scope.Benchmark)`
3. Annotate methods with `@Benchmark`
4. Use `Blackhole` parameter to consume results (prevents DCE)

Example:

```kotlin
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class MyBenchmark {

    @Benchmark
    fun myOperation(blackhole: Blackhole) {
        val result = expensiveOperation()
        blackhole.consume(result)
    }
}
```

## CI/CD Integration

Benchmarks can be run in CI but are typically executed separately from unit tests:

```yaml
# .github/workflows/benchmarks.yml
- name: Run Performance Benchmarks
  run: ./gradlew jmh
  
- name: Upload Results
  uses: actions/upload-artifact@v4
  with:
    name: jmh-results
    path: build/reports/jmh/
```

## Related Issues

- Issue #216: Array2DMap performance benchmarks (TODO)
- Issue #219: Koin DI performance benchmarks (COMPLETE)

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [JMH Gradle Plugin](https://github.com/melix/jmh-gradle-plugin)
- [Avoiding Benchmarking Pitfalls](https://shipilev.net/blog/2014/nanotrusting-nanotime/)
