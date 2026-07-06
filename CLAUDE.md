# CLAUDE.md

**Last Updated:** 2026-03-10

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status Marker Convention

Throughout this documentation, the following status markers are used:
- ✅ **COMPLETE** - Work finished and merged
- 🟡 **IN PROGRESS** - Active work underway
- ⏸️ **BLOCKED** - Waiting on dependencies
- 🆕 **OPEN/NEW** - Not started but planned
- ❌ **CANCELLED** - Work abandoned

## Agent Team Structure

@TEAM.md

For multi-agent development workflows, see **[TEAM.md](TEAM.md)** which defines 7 specialized agent roles:
- **traffic-simulation-expert** - Main leader, arbiter, simulation & physics expert
- **kotlin-tech-lead** - Technical architect, code reviewer, mentor
- **java-senior-dev** - Historical analysis expert, null safety advisor
- **kotlin-junior-dev** - Implementation developers, learners (unlimited)
- **agent-architect** - AI agent system designer, ML specialist, A2A protocol designer
- **railway-civil-engineer** - Railway domain expert, visioner, requirements definer
- **qa-engineer** - Quality assurance specialists, UX/UI experts (2-3 allowed)

TEAM.md includes decision authority hierarchy, collaboration patterns, and railway-inspired Agent-to-Agent (A2A) communication protocols.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of Technology that simulates railway interlocking systems with a graphical editor and discrete event simulation engine.

[![Gradle Build with Java 21](https://github.com/bedavs/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedavs/interlockSim/actions/workflows/gradle-java21.yml)
[![SonarQube Analysis](https://github.com/bedavs/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedavs/interlockSim/actions/workflows/sonarqube.yml)

## Build System

This project uses Gradle with Kotlin DSL. Java 21 LTS is required.

**Recent migrations (January 2026):** Ant→Gradle, Java 11→21 LTS, Java→Kotlin, Observable→PropertyChangeSupport, SLF4J→kotlin-logging, kDisco extracted to separate repo.

### Common Build Commands

```bash
# Build and test
./gradlew clean build
./gradlew test                    # Unit tests only
./gradlew integrationTest         # Integration tests only

# Run application
./gradlew runSim                  # Pre-configured shunting loop
./gradlew runEditor               # Launch editor GUI
./gradlew runExampleGui           # Animated GUI simulation (Issue #268, milestone complete 2026-02-04)

# Goal 7 speed-control shortcuts (simulation mode only)
# 1-5    -> 0.5x, 1x, 2x, 5x, 10x
# +/-    -> multiply or divide speed by 1.5
# Space  -> pause/resume toggle (Goal 8 integration point)

# Other tasks
./gradlew javadoc                 # Generate documentation
./gradlew dependencies            # Show dependency tree
```

For complete build system documentation including dependency management, GitHub Packages authentication, manual JAR execution, and Gradle configuration files, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Build & Development Environment".

### Running Simulation with Speed Control

Use the animated GUI when you need live speed changes:

```bash
# Built-in animated example with speed controls
./gradlew runExampleGui

# Equivalent manual JAR launch
java -jar build/libs/interlockSim.jar exampleGui shuntingLoop 300
```

For XML files loaded from the desktop UI, use **Simulation → Start...** and then adjust speed with:

- the speed slider (`0.1x` to `10.0x`)
- preset buttons/menu items (`0.1x`, `0.5x`, `1x`, `2x`, `5x`, `10x`, `50x`)
- global keyboard shortcuts (`1`-`5`, `+`, `-`, `Space`)

The status bar shows `Speed: X.Xx` whenever the speed differs from `1.0x`.

### Directory Structure

- `core/` - KMP `:core` subproject (domain model, simulation engine, XML)
- `desktop-ui/` - JVM `:desktop-ui` subproject (GUI, DI bootstrap, Main entry)
- `fast-sim/` - native `:fast-sim` subproject (linuxX64 CLI binary, **requires Linux host**)
  - `desktop-ui/src/main/kotlin/` - Main source code
  - `desktop-ui/src/test/kotlin/` - Test source code
  - `desktop-ui/src/main/resources/` - Resource files (XML examples)
  - `desktop-ui/docker-x11/` - SELinux policy modules for Docker X11 forwarding (Fedora)
- `docs/` - Project documentation
- `desktop-ui/build/` - Compiled outputs, JARs, test results

## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build and runtime environment.

### Quick Start

```bash
# Build services
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
docker compose build app          # App image only -- runs NO tests

# Run full test suite + quality gates (tests execute DURING the image build;
# there is no runtime test entry point)
docker compose --profile test build test

# Run editor GUI
docker compose up app

# Run simulation example
docker compose run app java -jar interlockSim.jar example shuntingLoop 60

# Build thesis PDF
docker compose up text
```

**Security note:** `GITHUB_TOKEN` is passed to the Docker build as a BuildKit secret (`--mount=type=secret`), not as a build `ARG`. The token is never interpolated into Dockerfile `RUN` command strings, so it cannot leak into build logs or image history when a build step fails.

**Tests are decoupled from the app image:** `docker compose build app` compiles and packages only (`shadowJar`) — it never runs tests, so a broken test suite cannot block image production. The full suite plus quality gates (`check` = test/detekt/ktlintCheck/purity, plus `integrationTest` and `:core:allTests`) runs in the separate `test-runner` Docker stage, invoked with `docker compose --profile test build test`. Tests execute *during* that image build (BuildKit cache mounts and secrets only exist at build time — `docker compose run test` would not run tests). The `test` profile keeps the service invisible to plain `docker compose up --build`.

**Non-root builds:** The Gradle build and tests run as an unprivileged `builder` user (UID/GID 1001). Cache mounts and the project directory are owned by this user, so generated files are not owned by `root`. The builder/test stages *must* stay non-root: the test suite includes filesystem-permission tests that are auto-skipped under root (e.g. `@DisabledIfSystemProperty(matches = "root")`), so running tests as root would silently drop coverage. The final **runtime** image runs as a non-root `app` user whose UID/GID is set at build time via `RUNTIME_UID`/`RUNTIME_GID` build ARGs (default 1000/1000). When the ARGs match the host user's UID/GID, the container user can read the 0600 X11 auth cookie bind-mounted from the host without needing root — this is the standard matching-UID pattern for X11-forwarding dev containers. Set `RUNTIME_UID=$(id -u)` and `RUNTIME_GID=$(id -g)` in your `.env` file or export them before building.

**fast-sim runtime must stay glibc-based:** The `fast-sim` runtime image uses `debian:12-slim`, not Alpine. Kotlin/Native linuxX64 binaries are linked against glibc; running them on Alpine's musl via the `gcompat` shim causes an intermittent all-threads futex deadlock in the Kotlin/Native runtime that hung ~20-30% of fast-sim runs (Issue #685, fixed 2026-07-03). Do not switch the runtime stage back to Alpine/musl.

**Offline builds:** If GitHub Packages is unreachable, build kDisco locally first (`./gradlew :kdisco-core:publishToMavenLocal` in the kDisco repo), then run `docker compose build` without `GITHUB_TOKEN`. `mavenLocal()` satisfies the kDisco dependency before GitHub Packages is consulted. See **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Dependency Management" for full instructions.

For X11 forwarding troubleshooting, authentication setup, SELinux configuration (Fedora), and Docker architecture details, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Build & Development Environment".

## Architecture

### Core Components

**Main entry point:** `cz.vutbr.fit.interlockSim.Main` - handles three modes: `sim`, `edit`, `example`

**Context system:**
- `Context<out C : Cell>` - Base abstraction for railway network configuration
- `EditingContext : Context<NodeCell>` - Interface for editing operations
- `SimulationContext : Context<Cell>, SimulationEnvironment` - Interface for simulation execution
- `BaseContext` - Abstract base class with shared infrastructure (257 lines)
- `DefaultEditingContext` / `DefaultSimulationContext` - Implementations extending BaseContext independently
- `ContextTransformer` - Factory for editing→simulation transformation
- `XMLContextFactory` - Creates contexts from XML files (schema: `data.xsd`)

**Context Refactoring History:**
- **Issue #98 (2026-01-14):** DefaultContext split into DefaultEditingContext and DefaultSimulationContext
- **Issue #153 (2026-01-20):** Composition over inheritance refactoring - ✅ **Phases 1-5 COMPLETE**
  - BaseContext abstraction (257 lines shared code)
  - Interface Segregation Principle enforced (SimulationContext no longer extends EditingContext)
  - Context transformation (ContextTransformer factory for editing→simulation conversion)
  - Grid parameterization (static cells vs dynamic wrappers)
  - Runtime immutability enforcement (freeze/isFrozen/checkNotFrozen)
  - **Timeline:** 8 days actual vs 18 estimated (70% faster), zero regressions across 927+ tests
  - **Status:** Phase 5.5 (#182) 🆕 PENDING, Phase 6 (#167) ✅ COMPLETE, (#166) ⏸️ IN PROGRESS, (#168) ⏸️ BLOCKED
- **Issue #94 (2026-01-21) ✅ COMPLETE:** SimulationEnvironment facade interface for DSOL/Kalasim migration readiness

**For detailed context refactoring documentation:**
- `docs/CONTEXT_REFACTORING_DESIGN.md` - Architecture design and implementation history
- `docs/ISSUE_153_RETROSPECTIVE.md` - Phases 1-5 detailed retrospective (2026-01-20)
- `docs/CONTEXT_REFACTORING_PHASE6_SUMMARY.md` - Phase 6 status and completion plan (2026-02-05)
- `docs/CONTEXT_INHERITANCE_INCOMPATIBILITY.md` - Problem analysis and solution design

**Navigation Services (Issue #292 Phases 1-5, 2026-01-11 to 2026-02-04) - COMPLETED:**

Three specialized services replaced the removed `pathToNextSemaphore()` API:

1. **TopologyNavigator** - Static topology navigation (pure graph traversal)
   - Access: `EditingContext.getTopologyNavigator()` or `SimulationEnvironment.getRoutingServices().getTopologyNavigator()`
   - Use case: Editor validation, network analysis

2. **PathReservationService** - Dispatcher logic (find FREE paths, reserve atomically)
   - Access: `SimulationEnvironment.getRoutingServices().getPathReservationService()`
   - Use case: Interlocking path setup, atomic reservation

3. **TrainNavigationService** - Train-specific navigation (follow RESERVED paths only)
   - Access: `SimulationEnvironment.getRoutingServices().getTrainNavigationService()`
   - Use case: Train requesting next track section

4. **PathReservationRegistry** - Bidirectional train↔block ownership tracking
   - O(1) queries, scoped lifetime (one per context), shared by all services

For complete navigation services architecture, Koin DI integration patterns, and usage examples, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Project Architecture Context".

**Additional architecture documentation:**
- `docs/PATH_DISCOVERY_ARCHITECTURE.md` - Design rationale, trade-offs, implementation phases (808 lines)
- `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` - Migration examples with before/after code (547 lines)
- `docs/PATH_RESERVATION_ARCHITECTURE.md` - Atomic reservation algorithm and graph theory (1069 lines)
- `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Static/dynamic wrapper pattern

**Object model:**
- `objects/tracks/` - Track facilities, blocks, occupants
- `objects/cells/` - Grid-based spatial representation (uses `Array2DMap`)
- `objects/paths/` - Route management

**Simulation engine:**
- Built on kDisco library (`cz.hovorka.kdisco:kdisco-core-jvm:0.6.0`) — replaces jDisco entirely (Phase 1 migration complete 2026-03-20)
- kDisco repo: https://github.com/bedaHovorka/kdisco
- `sim/` package contains simulation processes (e.g., `ShuntingLoop`)

**GUI:**
- Swing-based editor in `gui/` package
- AnimatedSim: Real-time animated GUI simulation (Issue #268, milestone complete 2026-02-04)
  - Physics-accurate rendering with velocity/acceleration visualization
  - Visual train movement with smooth interpolation
  - Goal 7 speed control is built around `SimulationRunner`, which applies wall-clock throttling without changing event semantics
  - `SimulationController` owns lifecycle, persists the selected speed, and reapplies it on the next simulation start
  - `SimulationControlPanel` provides a `0.1x`-`10.0x` slider plus preset buttons up to `50x`
  - `StatusBar.updateSpeedIndicator()` shows the live multiplier whenever speed is not `1.0x`
  - `SimulationKeyBindings` installs global shortcuts (`1`-`5`, `+`, `-`, `Space`) while the frame is in simulation mode
  - `Space` currently toggles `SimulationRunner.isPaused` directly as Goal 8 pause-feature groundwork
  - See `docs/ANIMATION_ARCHITECTURE.md` for technical details

**XML Configuration:**
- Railway networks defined in XML format (schema: `src/main/resources/.../data.xsd`)
- Example: `vyhybna.xml` (shunting loop), `praha.xml`
- Elements: RailSwitch, RailSemaphore, InOut (entry/exit points)

### InOut Elements

**Minimum Requirement:** Every railway network must have at least 1 InOut element (entry/exit point).

**Rationale:**
- With bidirectional train operation (PR #356), a single InOut can serve as both entry and exit
- Train can enter, travel through the network, reverse direction, and exit through the same InOut
- XML validation enforces this constraint via XMLContextFactory

**Validation:**
- Editor: GUI prevents saving contexts with < 1 InOuts (Issue #80)
- XML loading: XMLContextFactory validates during parse
- Test coverage: See InOutValidationTest (Issue #79)

### Package Structure

```
src/
├── main/java/cz/vutbr/fit/interlockSim/
│   ├── Main.java              - Application entry point
│   ├── context/               - Context management and factories
│   ├── gui/                   - Graphical editor
│   ├── objects/               - Domain model
│   ├── sim/                   - Simulation scenarios
│   ├── util/                  - Utilities
│   └── xml/                   - XML parsing
└── test/java/cz/vutbr/fit/interlockSim/
    ├── context/               - Context tests
    ├── sim/                   - Simulation tests
    ├── testutil/              - Test utilities (TestFixtures, TestTopologies)
    └── xml/                   - XML validation tests
```

## Kotlin Migration History

**Completed:** January 2026 (100% of 94 files migrated)
- Conservative structure-preserving approach with full test parity
- Physics calculations validated against Java baseline
- Full kDisco interoperability maintained

## Dependency Injection with Koin

**Status:** Migration complete (2026-01-12)
**Framework:** Koin 3.5.6 (Kotlin-native, lightweight ~1MB)

### Quick Start

```kotlin
// Inject dependencies
class MyClass {
    private val dependency: MyDependency by inject()
}

// Or constructor injection
class MyClass(private val dependency: MyDependency)
```

### Critical DI Rules

1. **sim/ package**: Koin injection is now allowed. The restriction was tied to the jDisco→kDisco migration (Phase 1, complete 2026-03-20). kDisco is now the stable engine with native support.
2. **Contexts are NOT singletons** - Use `factory` or `scope`, never `single`
3. **Preserve factory patterns** - Inject factories, not products

For complete Koin documentation including module organization, scope-per-context pattern, navigation services integration, testing patterns, and common issues, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Dependency Injection with Koin".

## Code Style

Follows `.editorconfig` configuration:
- Java/Kotlin files: tabs (width 4), max line length 120
- XML files: 2 spaces
- UTF-8 encoding, LF line endings

See **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** for complete coding conventions.

## Code Modification Guidelines

**Conservative approach differentiated by component type:**

### Restrictions for sim/ Package (Logic Only — Koin Now Allowed)

**Simulation Core (`sim/` package):**
- **Minimal changes only** - Be extremely conservative with simulation logic
- **No refactoring** - Do not restructure working simulation code
- **Tests required** - Any changes MUST have comprehensive test coverage first
- **No unsolicited improvements** - Only make explicitly requested changes
- **Koin injection allowed** - The Koin restriction was lifted 2026-03-20 (kDisco Phase 1 complete)

**kDisco Library:**
- **Do not modify** - Maintained as separate project at https://github.com/bedaHovorka/kdisco

### Flexible Development (Other Components)

**GUI (`gui/`), Editor, Utilities, Context System:**
- **Modernization allowed** - Can refactor, improve, and apply Kotlin idioms
- **Tests required** - Must have test coverage before and after changes
- **Alignment required** - Changes must align with LONG_TERM_GOALS.md

### General Rules for All Changes

1. **Tests are mandatory** - Modified code MUST be covered by tests
2. **Align with goals** - Support LONG_TERM_GOALS.md objectives
3. **No breaking changes** - Maintain backward compatibility
4. **Document decisions** - Update relevant documentation
5. **Quality gates** - Must pass: `./gradlew build detekt ktlintCheck test`

For detailed examples of allowed/restricted/prohibited changes, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Code Modification Guidelines".

## Testing

Comprehensive JUnit 5.11.4 test suite with AssertK assertions.

**Test organization:**
- **Unit tests** - Run with `./gradlew test` (excludes integration and heavy tests)
- **Integration tests** - Tagged with `@Tag("integration-test")`, run with `./gradlew integrationTest`
- **Heavy tests** - Tagged with `@Tag("heavy-test")`, run with `./gradlew heavyTest` (see below)

**Heavy tests (`@Tag("heavy-test")`):**

Heavy tests are high-repetition stress tests that verify simulation stability under many repeated runs. They are **excluded from regular `test` and `integrationTest` builds** to keep CI fast.

**When to run heavy tests:**
- After changes to simulation logic (path reservation, train physics, kDisco event scheduling)
- After changes to concurrency primitives or `waitUntil`/`hold` patterns
- When investigating intermittent deadlocks, race conditions, or resource leaks
- Before merging branches that touch `sim/`, `context/navigation/`, or kDisco integration

```bash
# Run all heavy tests
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :core:heavyTest

# Run heavy tests in desktop-ui
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :desktop-ui:heavyTest
```

**Repetition limits:**
- `test` and `integrationTest` tasks: max **50** repetitions per test method
- `heavyTest` task: up to **1000** repetitions (e.g., `ThreeTrainLoopRaceHeavyTest`)

**Tagging convention:**
```kotlin
// Integration test (max 50 @RepeatedTest repetitions in integrationTest)
@Tag("integration-test")
@RepeatedTest(50)

// Heavy test (1000 repetitions, only in heavyTest target)
@Tag("heavy-test")
@RepeatedTest(1000)
```

**Test coverage (February 2026):**
- **1840 tests total** (1836 passing, 4 skipped, 0 failing)
- **51% code coverage** (8,824/17,070 instructions)
- **145 test classes**

**Test utilities:**
- `TestFixtures` - Centralized XML fixture loading
- `TestTopologies` - Reusable network topologies
- `TestContextBuilder` - Fluent API for building test contexts
- `AssertKExtensions` - Custom assertions

For complete testing documentation including tagging patterns, test utilities usage, and fixture management, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Test Fixtures and Utilities".

## Code Quality Analysis

### SonarQube Integration

Provides code smells detection, security vulnerabilities, coverage analysis (JaCoCo), and complexity metrics.

**Quick Start:**
```bash
# SonarCloud (recommended)
./gradlew clean test jacocoTestReport sonar \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.organization=<your-org> \
  -Dsonar.token=<your-token>

# Code coverage
./gradlew test jacocoTestReport  # View at build/reports/jacoco/test/html/index.html
```

For complete SonarQube configuration, quality gates setup, CI/CD integration, and JaCoCo configuration, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Code Quality Enforcement".

### Kotlin Code Quality (Detekt and Ktlint)

**RULE: Disabling ktlint is forbidden.** Never set `enabled = false` on ktlint tasks or add workaround comments to bypass enforcement. Fix violations; don't silence the checker.

**Dual-level approach:**
- `detekt.yml` - Permissive rules for legacy Java→Kotlin converted code
- `detekt-strict.yml` - Strict rules for new Kotlin code written from scratch

**Run checks:**
```bash
./gradlew detekt              # Legacy/converted code
./gradlew detektStrict        # New Kotlin code
./gradlew ktlintCheck         # Formatting check
./gradlew ktlintFormat        # Auto-format
```

For complete Detekt/Ktlint configuration, rule details, and quality enforcement levels, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Code Quality Enforcement Levels".

## Logging

Uses **kotlin-logging** (SLF4J wrapper) with Logback backend.

**In Kotlin code:**
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.debug { "Message with $variable" }  // Lambda-based lazy evaluation
```

For complete logging configuration, runtime log level override, and output configuration, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Logging Configuration".

## Continuous Integration

GitHub Actions workflow (`.github/workflows/gradle-java21.yml`) runs on push/PR to main/develop branches. Compiles with Java 21, runs tests, packages JAR (90-day artifact retention), caches dependencies.

View build status: [GitHub Actions](https://github.com/bedavs/interlockSim/actions)

## Documentation

**Thesis:** LaTeX sources in `text/`, build with `docker compose up text` (outputs to `artifacts/text/bakalarka.pdf`)
**JavaDoc:** Generate with `./gradlew javadoc` (outputs to `build/docs/javadoc/`)

**Architecture Documentation (docs/):**
- `KOTLIN_STYLE_GUIDE.md` - Kotlin coding conventions, DI patterns, build environment (2253 lines)
- `PATH_DISCOVERY_ARCHITECTURE.md` - Navigation services design (808 lines)
- `PATH_DISCOVERY_MIGRATION_GUIDE.md` - Migration guide with examples (547 lines)
- `PATH_RESERVATION_ARCHITECTURE.md` - Reservation service design (1069 lines)
- `CONTEXT_REFACTORING_DESIGN.md` - Context system design (Issue #98, #153)
- `CONTEXT_REFACTORING_PHASE6_SUMMARY.md` - Phase 6 status and completion plan (2026-02-05)
- `ISSUE_153_RETROSPECTIVE.md` - Phases 1-5 detailed retrospective (927 lines)
- `CONTEXT_INHERITANCE_INCOMPATIBILITY.md` - Problem analysis and solution design
- `STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Static/dynamic wrapper pattern (Issue #100)
- `GRID_PARAMETERIZATION_INDEX.md` - Overview and document roadmap
- `GRID_PARAMETERIZATION_DESIGN.md` - Complete design specification (1591 lines)
- `GRID_PARAMETERIZATION_IMPLEMENTATION.md` - Implementation notes
- `GRID_PARAMETERIZATION_SUMMARY.md` - Implementation status
- `ANIMATION_ARCHITECTURE.md` - AnimatedSim real-time GUI architecture
- `ANIMATED_SIM_MILESTONE_COMPLETE.md` - AnimatedSim milestone summary
- `CZECH_RAILWAY_TERMINOLOGY.md` - Czech terminology verification and translation guide (373 lines)
- `TRAIN_PASSIVATION_FIX.md` - Train physics passivation fix (Issue #291)
- `KOIN_SCOPE_LIFECYCLE_TESTS.md` - Koin scope lifecycle test documentation (Issue #220)

## Known Issues

**Critical:** None. All critical SonarQube bugs fixed.

**Notable issues:**
- **SIM-004:** ShuntingLoop hardcoded for `vyhybna.xml` configuration only
- **DEFERRED-001:** XMLContextFactoryTest missing exception type predicates (9 occurrences)
- Minor simulation issues (SIM-001 to SIM-006) documented in code comments

**Test coverage:** 1840 tests (1836 passing, 4 skipped), 51% code coverage.

## Future Development Considerations

The project currently uses **kDisco** (replaces jDisco, Phase 1 migration complete 2026-03-04). The long-term target is **Kalasim** (Phase 2, future). Research on modern alternatives is documented in `docs/jdisco-research.md`.

**Migration status:**
- ✅ **Phase 1 complete (2026-03-20):** Swapped jDisco for kDisco (`cz.hovorka.kdisco:kdisco-core-jvm:0.5.0`). See commit history on `feature/simulation-library-decision-round2`.
- 🆕 **Phase 2 (future):** Migrate from kDisco to Kalasim (native Kotlin coroutines-based discrete event simulation).

**Alternative frameworks researched (see `docs/jdisco-research.md`):**
1. **Kalasim** - Long-term target; native Kotlin with coroutines, discrete-only
2. **DSOL** (Distributed Simulation Object Library) - Best for combined discrete-continuous simulation (actively maintained, Java 17+, TU Delft)
3. **SSJ** (Stochastic Simulation in Java) - For stochastic/Monte Carlo simulation (Université de Montréal)

**Note:** Any migration from kDisco to modern frameworks is a future development goal and should follow the conservative approach outlined above - thorough testing required before any changes to existing simulation code.
