# Design Spec: :fast-sim — Native linuxX64 Simulation CLI

**Date:** 2026-03-21
**Status:** Draft
**Author:** Claude Code (brainstorming session)

## Problem

The interlockSim project completed KMP purification (PR #399, #402) with a linuxX64 target configured in `:core`. The simulation engine, XML parsing, and domain model are all in `commonMain`. However, no native simulation has actually *run* yet — only 8 compilation sanity tests exist in `NativeSanityTest`.

There is no way to run a railway simulation without the JVM. The JVM adds startup overhead and requires a ~200MB runtime. A native binary would provide instant startup, smaller deployment footprint, and validate that the KMP architecture works end-to-end on native.

## Solution

A new `:fast-sim` Gradle subproject that compiles to a linuxX64 native executable. It runs railway simulations from the command line, producing human-readable text output. No GUI, no real-time sync.

The existing JVM path (`:desktop-ui`) remains unchanged for GUI, editor, and animated simulation.

## Architecture

### Subproject Structure

```
fast-sim/
├── build.gradle.kts
└── src/
    └── nativeMain/
        └── kotlin/cz/vutbr/fit/interlockSim/fastsim/
            ├── Main.kt
            ├── NativeContextFactory.kt
            ├── NativeExampleRegistry.kt
            ├── EmbeddedResources.kt
            └── TextReporter.kt
```

**Build configuration:** `kotlin("multiplatform")` plugin with `linuxX64` executable target. Depends on `:core` (which provides simulation engine, XML parsing, domain model).

**Settings:** Add `include(":fast-sim")` to `settings.gradle.kts`.

### CLI Interface

```bash
# Run built-in example scenario (uses embedded XML)
./fast-sim example shuntingLoop 60

# Run simulation from XML file
./fast-sim sim /path/to/vyhybna.xml 60

# Version info
./fast-sim --version

# Help
./fast-sim --help
```

**Output verbosity flags** (combinable with any mode):

| Flag | Effect |
|------|--------|
| _(none)_ | Normal output — one summary line per event |
| `--verbose` | Full detail per event (timestamp, all fields) |
| `--quiet` | Suppress per-event lines; print only the final summary |
| `--debug` | Enable `DEBUG`-level kotlin-logging output to **stderr** (simulation results stay on **stdout**) |

```bash
# Verbose output
./fast-sim --verbose example shuntingLoop 60

# Quiet mode — final summary only
./fast-sim --quiet sim /path/to/vyhybna.xml 60

# Debug logging to stderr, simulation output to stdout
./fast-sim --debug example shuntingLoop 60
./fast-sim --debug --verbose sim network.xml 300 2>debug.log
```

**Exit codes:** 0 = success, 1 = simulation/runtime error, 2 = invalid arguments, 130 = interrupted (SIGINT).

### Components

**Main.kt** — Entry point. Parses command-line arguments, initializes Koin DI, creates context, runs simulation, prints results. Handles `example` and `sim` modes. Error handling: catches `ContextCreationException`, `SimulationException`, prints to stderr, returns appropriate exit code.

**NativeContextFactory.kt** — Creates `EditingContext` from XML string or file path. Pipeline:
1. XML string → `XmlContextReader.parse()` (commonMain) → `DefaultEditingContext`
2. `DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)` (commonMain) — `processFactory` determines which main process (e.g., `ShuntingLoop`) will be created lazily inside `run()`
3. `simulationContext.initializeDynamicMapping()` (commonMain)
4. `simulationContext.run()` — creates main process via factory and executes simulation

This replicates the `ContextTransformer` logic (which is JVM-only at `core/src/jvmMain/.../ContextTransformer.kt`). ContextTransformer itself is trivial — it calls `fromEditingContext()` + `initializeDynamicMapping()` + logging — so inlining in `NativeContextFactory` is cleaner than promoting it to commonMain. `DefaultSimulationContextFactory` (JVM-only) is NOT needed — it wraps File/InputStream operations that don't apply to native.

**NativeExampleRegistry.kt** — Maps example names to factory functions, analogous to `desktop-ui`'s `ExampleRegistry`. Uses `EmbeddedResources` for XML instead of `MyResourceBundle.getFile()`. Console examples only (no GUI examples). This duplication with `ExampleRegistry` is intentional — `ExampleRegistry` uses JVM-only `InputStream`/`SimulationContextFactory`; a future commonMain `ExampleRegistry` can be considered when the APIs converge.

**EmbeddedResources.kt** — Embedded XML configurations as string constants. Initially contains `vyhybna.xml` (the shunting loop network). Pattern follows `CommonTestFixtures.VYHYBNA_XML` in `core/src/commonTest/`. For the `sim` mode (file-based loading), #403 platform-agnostic file I/O is used instead.

**TextReporter.kt** — Subscribes to simulation events and prints human-readable text to stdout. Reports: train creation, path reservations, semaphore changes, train arrivals/departures, simulation completion. Output is semantically equivalent to JVM `example` mode but not byte-identical. Logging output (kotlin-logging) goes to stderr (see Logging section), keeping stdout clean for structured output.

### Context Creation Pipeline (Critical Path)

The JVM path uses several JVM-only classes that will NOT be promoted:

| JVM Component | Location | Native Equivalent |
|---|---|---|
| `ContextTransformer` | `core/src/jvmMain/` | Inlined in `NativeContextFactory` |
| `DefaultSimulationContextFactory` | `core/src/jvmMain/` | Not needed (no File/InputStream) |
| `SimulationContextFactory` interface | `core/src/jvmMain/` | Not needed (direct construction) |
| `XMLContextFactory` | `core/src/jvmMain/` | `XmlContextReader` (commonMain) + `XmlSchemaValidator` (native actual) |
| `ExampleRegistry` | `desktop-ui/` | `NativeExampleRegistry` (fast-sim) |
| `MyResourceBundle` | `core/src/jvmMain/` | `EmbeddedResources` (fast-sim) |

All core transformation logic (`DefaultSimulationContext.fromEditingContext()`, `initializeDynamicMapping()`, `XmlContextReader`, `XmlSchemaValidator`) is already in commonMain and works on native.

### Key Design Decisions

1. **No real-time sync.** ShuntingLoop's `enableRealTimeSync` parameter is set to `false`. Simulation runs as fast as possible.
2. **Shared simulation code.** All simulation logic lives in `:core` commonMain. `:fast-sim` only provides the native entry point and text output formatting.
3. **Koin DI.** Uses the same `CoreModule` as `:core` tests and `:desktop-ui`. Koin 3.5.6 supports Kotlin/Native.
4. **No Swing/AWT imports.** Pure native — the binary has zero JVM dependencies.
5. **Inline over promote.** ContextTransformer logic is inlined in NativeContextFactory rather than promoted to commonMain. The JVM path has additional concerns (streams, files) that don't apply to native.
6. **Embedded XML for examples.** Built-in examples use compiled-in XML strings. File-based `sim` mode uses #403 file I/O.

### Logging on Native

kotlin-logging uses `println` as the backend on Kotlin/Native (no SLF4J). Since stdout is the primary output channel for `TextReporter`, simulation logging must not pollute it.

**Implemented strategy:**
- By default, logging is suppressed (`Level.OFF`) — simulation output on stdout is clean.
- Pass `--debug` to enable `DEBUG`-level output routed to **stderr** via a custom `StderrAppender` (POSIX `fprintf(stderr, ...)`).
- `TextReporter` output goes to stdout; all logging (when enabled) goes to stderr.
- Redirect with `2>debug.log` to capture debug output separately from simulation results.

### Error Handling

| Scenario | Behavior |
|---|---|
| XML file not found | Print error to stderr, exit code 2 |
| XML schema validation failure | Print validation errors to stderr, exit code 1 |
| Simulation exception (kDisco) | Print exception message to stderr, exit code 1 |
| Koin initialization failure | Print error to stderr, exit code 1 |
| Invalid CLI arguments | Print usage to stderr, exit code 2 |
| SIGINT (Ctrl+C) | Graceful shutdown: stop simulation loop, print partial results summary, exit code 130 |

## Prerequisites

### Blocking

1. **kDisco native validation** (new kdisco repo issue) — Must confirm that `kdisco-core` linuxX64 artifact can run a complete simulation. Known risks:
   - Threading model (JVM threads vs K/N single-threaded)
   - Coroutine dispatcher behavior on native
   - `@Synchronized` removal in KMP (#404)
   - Random number generation determinism
   - `Variable.isActive()` and `Continuous.isActive` behavior

2. **#403 Platform-agnostic file I/O** — NativeContextFactory needs to read XML files from the filesystem for `sim` mode. Currently `XMLContextFactory` uses `java.io.File`. Issue #403 proposes `okio` or custom `expect/actual` for cross-platform file reading. Note: `example` mode works without #403 (uses embedded XML).

### Related (not blocking)

- **#400** Decouple :core tests from :desktop-ui working directory
- **#401** Migrate remaining jvmTest to commonTest
- **#404** Thread safety: @Synchronized removed without KMP replacement — affects kDisco native
- **#409** PlatformIdentity hash semantics — may affect simulation determinism if any logic depends on hash-based iteration order
- **#410** Increase native test coverage — fastSim smoke test contributes here
- **#411** PlatformTime clock_gettime return value — affects timing accuracy

## Smoke Test Design

**Goal:** Verify that native simulation produces the same results as JVM simulation.

**Method:**
1. Run JVM: `java -jar interlockSim.jar example shuntingLoop 60` → capture stdout
2. Run native: `./fast-sim example shuntingLoop 60` → capture stdout
3. Compare simulation outcomes

**Comparison strategy:** Since output is semantically equivalent but not byte-identical, direct diff won't work. Parse text output with regex to extract key simulation events (train ID, event type, simulation time, track block), then compare extracted event lists.

**Implementation:** Shell script or Gradle task that runs both binaries and compares parsed events. Could also be a `@Tag("integration-test")` test.

**Future enhancement:** A `--json` output flag could simplify comparison and enable downstream tooling (parameter sweeps, CI regression). Out of scope for v1.

**PlatformIdentity risk:** If HashMap iteration order differs between JVM and native (due to different `identityHashCode` implementations, #409), simulation event ordering may diverge even with correct logic. The smoke test must account for this — either by sorting events by simulation time or by ensuring deterministic iteration in simulation code.

## Performance Benchmark

**Metrics:**
- Startup time (time to first simulation event)
- Simulation wall-clock time (complete run of `shuntingLoop 60`)
- Peak memory usage

**Comparison:** JVM cold start, JVM warm (after JIT), native.

**Expected outcome:** Native faster on startup and short simulations. JVM may approach native speed on long runs due to JIT optimization.

## Docker Image

**Dockerfile.fast-sim:**
```dockerfile
# Build stage (Eclipse Temurin JDK 21 on Ubuntu 24.04 LTS Noble + libxml2-dev for cinterop)
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble AS builder
RUN apt-get update && apt-get install -y --no-install-recommends git libxml2-dev libicu-dev
COPY . /build
WORKDIR /build
RUN ./gradlew :fast-sim:linkReleaseExecutableLinuxX64

# Runtime stage (Alpine — no ICU dependency, ~20MB total)
FROM --platform=linux/amd64 alpine:3.21
RUN apk add --no-cache gcompat libxml2
COPY --from=builder /build/fast-sim/build/bin/linuxX64/releaseExecutable/fast-sim.kexe /usr/local/bin/fast-sim
ENTRYPOINT ["fast-sim"]
```

**Notes:**
- Runtime base is `alpine:3.21` (~8MB) with `gcompat` for glibc ABI compatibility
- Alpine's `libxml2` does NOT depend on `libicu` (unlike Debian/Ubuntu's ~30MB transitive dependency)
- Builder is pinned to `eclipse-temurin:21-jdk-noble` (Ubuntu 24.04 LTS) because its `libxml2.so.2` SONAME matches Alpine 3.21
- `gcompat` provides `/lib64/ld-linux-x86-64.so.2` and glibc symbol wrappers for K/N binary
- No XML files copied to image — built-in examples use embedded XML; `sim` mode requires bind-mounting files
- Image size target: < 30MB (Alpine ~8MB + gcompat ~2MB + libxml2 ~5MB + binary ~4.4MB)
- Build artifact path (`fast-sim.kexe`) must be verified against actual Kotlin/Native output
- **Previous approach (Debian bookworm-slim)** resulted in 174MB due to libicu72 transitive dependency (Issue #421)

**docker-compose.yml addition:**
```yaml
fast-sim:
  build:
    context: .
    dockerfile: Dockerfile.fast-sim
  entrypoint: ["fast-sim"]
  # For sim mode with external XML:
  # volumes:
  #   - ./networks:/data:ro
```

## CI/CD

Add to existing `gradle-java21.yml` or create `.github/workflows/fast-sim.yml`:
- Build native binary
- Run smoke test (native vs JVM output comparison)
- Upload native binary as build artifact
- Optionally build Docker image

## Code Quality

- Apply detekt/ktlint to `:fast-sim` following `:core` configuration patterns
- No `checkKdisco` dependency needed (`:fast-sim` is an application, not a library)
- Tests: nativeTest source set for unit tests of NativeContextFactory, TextReporter, CLI arg parsing

## Future Considerations

- **macOS/Windows:** Current scope is linuxX64 only. Adding `macosArm64` or `mingwX64` targets is straightforward but out of scope.
- **Common ExampleRegistry:** If JVM and native example registries diverge significantly, consider promoting shared logic to commonMain.
- **`--json` output flag:** Would simplify smoke test comparison and enable batch/CI tooling. Deferred to future enhancement.
- **Batch mode:** The CLI architecture supports future batch simulation runs, but batch is out of scope for the initial implementation.

## Issue Plan

### Epic (interlockSim)

**#413:** `feat: :fast-sim — native linuxX64 simulation CLI`

Contains sub-issues #414-#420 for subproject setup, context factory, TextReporter, smoke test, benchmark, Docker, CI/CD.

### Sub-issues (interlockSim)

1. `:fast-sim` Gradle subproject setup (linuxX64 executable, depends on :core)
2. NativeContextFactory + NativeExampleRegistry + EmbeddedResources
3. TextReporter — human-readable simulation output
4. Smoke test: native vs JVM semantic parity
5. Performance benchmark: native vs JVM
6. Docker image (Dockerfile.fast-sim, Alpine 3.21 + gcompat)
7. CI/CD: build & test fast-sim in GitHub Actions

### New Issue (kdisco)

**kdisco#12:** `feat: validate kdisco-core linuxX64 native simulation execution`

Scope: Create minimal native test that runs a complete simulation (Model + Process + Continuous). Document any required changes. Test random number determinism across platforms.

### Sub-issues (interlockSim)

- **#414** `:fast-sim` Gradle subproject setup
- **#415** NativeContextFactory + NativeExampleRegistry + EmbeddedResources
- **#416** TextReporter — human-readable simulation output
- **#417** Smoke test: native vs JVM semantic parity
- **#418** Performance benchmark: native vs JVM
- **#419** Docker image (Dockerfile.fast-sim)
- **#420** CI/CD: build & test fast-sim in GitHub Actions

### Prerequisite (existing)

**#403** Platform-agnostic file I/O — prerequisite for fast-sim `sim` mode.

### Dependency Graph

```
kDisco native validation (kdisco#12, BLOCKING) ──┐
                                                  ├──→ :fast-sim setup ──→ NativeContextFactory ──→ TextReporter ──→ Smoke test ──→ Benchmark
#403 file I/O (BLOCKING for sim mode) ──────────┘          │                                            │
                                                  EmbeddedResources                                      ├──→ Docker
                                                  (unblocks example mode)                                └──→ CI/CD
```

### Links to Existing Issues

- **Prerequisite:** #403 (file I/O), kDisco native validation (new)
- **Related:** #400, #401, #404, #409, #410, #411

## Success Criteria

1. `./fast-sim example shuntingLoop 60` runs to completion on linuxX64
2. Simulation output is semantically equivalent to JVM `example` mode
3. Native binary starts faster than JVM (measurably)
4. Docker image < 50MB
5. CI builds and tests the native binary on every push
6. kotlin-logging works on native without polluting stdout
