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

**Recent migrations (January 2026):** Ant→Gradle, Java 11→21 LTS, Java→Kotlin, Observable→PropertyChangeSupport, SLF4J→kotlin-logging, jDisco extracted to separate repo.

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

# Other tasks
./gradlew javadoc                 # Generate documentation
./gradlew dependencies            # Show dependency tree
```

For complete build system documentation including dependency management, GitHub Packages authentication, manual JAR execution, and Gradle configuration files, see **[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)** under "Build & Development Environment".

### Directory Structure

- `src/main/java/` - Main source code
- `src/test/java/` - Test source code
- `src/main/resources/` - Resource files (XML schemas, examples)
- `desktop-ui/docker-x11/` - SELinux policy modules for Docker X11 forwarding (Fedora)
- `docs/` - Project documentation
- `build/` - Compiled outputs, JARs, test results

## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build and runtime environment.

### Quick Start

```bash
# Build services
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
docker compose build app

# Run editor GUI
docker compose up app

# Run simulation example
docker compose run app java -jar interlockSim.jar example shuntingLoop 60

# Build thesis PDF
docker compose up text
```

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
   - Access: `EditingContext.getTopologyNavigator()` or `SimulationEnvironment.getTopologyNavigator()`
   - Use case: Editor validation, network analysis

2. **PathReservationService** - Dispatcher logic (find FREE paths, reserve atomically)
   - Access: `SimulationEnvironment.getPathReservationService()`
   - Use case: Interlocking path setup, atomic reservation

3. **TrainNavigationService** - Train-specific navigation (follow RESERVED paths only)
   - Access: `SimulationEnvironment.getTrainNavigationService()`
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
- Built on kDisco library (`cz.hovorka.kdisco:kdisco-core-api-jvm:0.2.0-SNAPSHOT`) — Kotlin wrapper over jDisco (Phase 1 migration complete 2026-03-04)
- kDisco repo: https://github.com/bedavs/kdisco; jDisco (original): https://github.com/bedavs/jDisco
- `sim/` package contains simulation processes (e.g., `ShuntingLoop`)

**GUI:**
- Swing-based editor in `gui/` package
- AnimatedSim: Real-time animated GUI simulation (Issue #268, milestone complete 2026-02-04)
  - Physics-accurate rendering with velocity/acceleration visualization
  - Visual train movement with smooth interpolation
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
- Full jDisco interoperability maintained

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

1. **sim/ package EXCLUDED** - No Koin injection in simulation classes until kDisco→Kalasim migration (Phase 2)
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

### Critical Restrictions (Until kDisco→Kalasim Migration, Phase 2)

**Simulation Core (`sim/` package):**
- **Minimal changes only** - Be extremely conservative with simulation logic
- **No refactoring** - Do not restructure working simulation code
- **Tests required** - Any changes MUST have comprehensive test coverage first
- **No unsolicited improvements** - Only make explicitly requested changes

**kDisco Library:**
- **Do not modify** - Maintained as separate project at https://github.com/bedavs/kdisco (wraps jDisco: https://github.com/bedavs/jDisco)

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
- **Unit tests** - Run with `./gradlew test` (excludes integration tests)
- **Integration tests** - Tagged with `@Tag("integration-test")`, run with `./gradlew integrationTest`

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

**Dual-level approach:**
- `detekt.yml` - Permissive rules for legacy Java→Kotlin converted code
- `detekt-strict.yml` - Strict rules for new Kotlin code written from scratch

**Run checks:**
```bash
./gradlew detekt              # Legacy/converted code
./gradlew detektStrict        # New Kotlin code
./gradlew ktlintCheck         # Formatting check
./gradlew ktlintFormat        # Auto-format (preserves tabs)
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

The project currently uses **kDisco** (Kotlin wrapper over jDisco, Phase 1 migration complete 2026-03-04). The long-term target is **Kalasim** (Phase 2, future). Research on modern alternatives is documented in `docs/jdisco-research.md`.

**Migration status:**
- ✅ **Phase 1 complete (2026-03-04):** Swapped jDisco for kDisco (`cz.hovorka.kdisco:kdisco-core-api-jvm:0.2.0`). See commit history on `feature/simulation-library-decision-round2`.
- 🆕 **Phase 2 (future):** Migrate from kDisco to Kalasim (native Kotlin coroutines-based discrete event simulation).

**Alternative frameworks researched (see `docs/jdisco-research.md`):**
1. **Kalasim** - Long-term target; native Kotlin with coroutines, discrete-only
2. **DSOL** (Distributed Simulation Object Library) - Best for combined discrete-continuous simulation (actively maintained, Java 17+, TU Delft)
3. **SSJ** (Stochastic Simulation in Java) - For stochastic/Monte Carlo simulation (Université de Montréal)

**Note:** Any migration from kDisco to modern frameworks is a future development goal and should follow the conservative approach outlined above - thorough testing required before any changes to existing simulation code.
