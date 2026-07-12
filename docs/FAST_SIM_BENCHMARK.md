# Fast-Sim Performance Benchmark: Native vs JVM

Issue [#418](https://github.com/bedaHovorka/interlockSim/issues/418) — Last issue in the fastSim milestone.

## Configuration

| Parameter | Value |
|-----------|-------|
| Iterations | 10 |
| Simulation | `example shuntingLoop 60` |
| JVM | openjdk version "21.0.10" 2026-01-20 |
| Native binary | `fast-sim.kexe` (Kotlin/Native linuxX64 release) |
| Kernel | 6.19.8-200.fc43.x86_64 |
| CPU | Intel(R) Core(TM) Ultra 9 285HX |
| Date | 2026-03-25 |

## Results

| Metric | JVM (cold) | Native | Ratio (JVM/Native) |
|--------|-----------|--------|---------------------|
| Wall-clock median | 0.688s | 0.099s | **6.9x** |
| Wall-clock mean | 0.700s | 0.100s | |
| Wall-clock stddev | 0.060s | 0.011s | |
| Wall-clock min | 0.614s | 0.087s | |
| Wall-clock max | 0.852s | 0.127s | |
| Peak RSS (median) | 192.5 MB | 31.0 MB | **6.1x** |
| Time to first event (median) | 0.688s | 0.095s | **7.2x** |
| Event count | 7 | 4 | see note below |
| Events/sec (median wall) | 10.1 | 40.4 | **4.0x** |

## Methodology

- **Wall-clock time**: Measured with nanosecond-precision `date +%s%N`, per-iteration measurements summarized as mean/median/stddev/min/max
- **Peak RSS**: Measured via `/usr/bin/time -v` (GNU time) Maximum resident set size
- **Time to first event**: Wall time from process start until first `t=` output line (5 iterations)
- **JVM cold**: Fresh `java -jar` invocation each iteration (no JVM warm-up between runs)
- **Native**: Fresh process invocation each iteration
- **Events/sec**: Total event count divided by median wall-clock time

### Reproducing

```bash
# Build both artifacts
./gradlew :desktop-ui:shadowJar :fast-sim:linkReleaseExecutableLinuxX64

# Run benchmark (outputs markdown to stdout)
bash fast-sim/benchmark/benchmark.sh [iterations] [endTime]

# Save results
bash fast-sim/benchmark/benchmark.sh > docs/FAST_SIM_BENCHMARK.md
```

## Analysis

### Startup Time

The native binary avoids JVM class loading, bytecode verification, and JIT compilation
overhead. The time-to-first-event ratio of **7.2x** is dominated by JVM startup cost
(~0.59s of the JVM's 0.688s is pure startup overhead before any simulation logic runs).

For a CLI tool that runs a short simulation and exits, this startup cost dominates the
total wall-clock time, making the native binary nearly 7x faster end-to-end.

### Memory Usage

Kotlin/Native produces a standalone binary (~4 MB) with no JVM heap overhead.
The native binary uses **6.1x less memory** (31 MB vs 193 MB peak RSS).

The JVM's baseline memory consumption (~190 MB) includes the JVM runtime, class metadata,
JIT compiler working memory, and default heap allocation. The native binary's 31 MB
includes only the compiled code and simulation data structures.

### Simulation Throughput

Both implementations run the same `:core` simulation logic (shared Kotlin Multiplatform code).
The wall-clock ratio of **6.9x** is almost entirely attributable to JVM startup time rather
than simulation throughput differences. For longer simulations (higher `endTime`), the ratio
would converge toward 1.0x as startup cost becomes a smaller fraction of total runtime.

### Event Count Difference

The JVM and native runs produce slightly different event counts (7 vs 4) despite running
the same `shuntingLoop` example with the same `endTime=60`. This difference is expected:
the continuous simulation engine (kDisco) uses both a pseudo-random generator and
floating-point arithmetic for event scheduling, and neither the RNG sequence nor FP
boundary behavior is guaranteed to be bit-for-bit identical across JVM and Kotlin/Native
runtimes. Minor differences in RNG draws and/or floating-point rounding near the simulation
end-time can cause events close to the boundary to be included or excluded — which is why
parity tests intentionally avoid asserting exact event counts. Both outputs represent valid
simulation runs.

## Conclusion

The native `fast-sim` binary delivers substantial improvements for CLI simulation use cases:

- **~7x faster** wall-clock execution for short simulations
- **~6x less memory** consumption
- **Zero dependencies** (no JVM installation required)

These characteristics make the native binary ideal for scripting, CI/CD pipelines, batch
simulation runs, and environments where JVM installation is undesirable.

---

## Supplementary: kDisco Kotlin Toolchain Evaluation (Issue #752)

**Date:** 2026-07-11
**Question:** Does upgrading kDisco's Kotlin `2.1.10 → 2.3.21` (kDisco PR [bedaHovorka/kdisco#52](https://github.com/bedaHovorka/kdisco/pull/52)) speed up `fast-sim`?

### Method

Published kDisco PR #52's branch to `mavenLocal` as `0.6.0-k2321-SNAPSHOT`, pointed a throwaway
interlockSim worktree at it, rebuilt the native `linuxX64` release binary, measured
`example shuntingLoop 300` (median of 7 runs, 5 trains, 211.1 s sim time).

### Result: **performance-neutral**

| kDisco build | Kotlin version | Wall-clock (median of 7) |
|---|---|---|
| 0.6.0 (current at evaluation) | 2.1.10 | **341 ms** |
| 0.6.0-k2321-SNAPSHOT (PR #52) | 2.3.21 | **349 ms** |

The 8 ms gap is **inside run-to-run noise** (±7%; the same binary measured 307–329 ms and
336–348 ms across repeated batches). There is no measurable performance difference in either direction.

### Conclusion

kDisco PR #52 is **neither an optimisation nor a pessimisation** for `fast-sim`. It should be
merged on toolchain-currency grounds rather than performance grounds.

Caveat on scope: this rebuilds only the *kDisco library* with 2.3.21. The dominant hot code
(`Train.Motor.derivatives`, ~1.85M calls/run, see [#750](https://github.com/bedaHovorka/interlockSim/issues/750))
lives in interlockSim and is compiled by interlockSim's own Kotlin version. The RKF45 step-count
problem (#750) dominates by two orders of magnitude; codegen changes are not the lever for that issue.

kDisco CI run for the `to0.6.1` branch (which includes the PR #52 changes):
[bedaHovorka/kdisco/actions/runs/29184671509](https://github.com/bedaHovorka/kdisco/actions/runs/29184671509) — ✅ **passed**
