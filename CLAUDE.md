# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

This project uses Gradle with Kotlin DSL for building. Java 21 LTS is the minimum required version.

**Recent migrations (January 2026):** Ant→Gradle, Java 11→21 LTS, Java→Kotlin, Observable→PropertyChangeSupport, SLF4J→kotlin-logging, jDisco extracted to separate repo.

### Dependency Management

Dependencies are managed via Gradle with fallback strategy:
- **jDisco 1.2.0** - Discrete event simulation library (external Maven dependency, Java 6 compatible)
  - Repository: https://github.com/bedaHovorka/jdisco
  - Published to GitHub Packages: `https://maven.pkg.github.com/bedaHovorka/jdisco`
  - Fallback order: `mavenLocal()` (cache) → GitHub Packages → build fails
  - Requires GitHub authentication for package download (see below)
- **JUnit 5.11.4** - Testing framework (JUnit Jupiter API and Engine)
- **AssertK 0.28.1** - Fluent Kotlin assertion library
- **MockK 1.13.14** - Kotlin-native mocking framework (supports sealed classes, coroutines)
- **Mockito 5.21.0** - Java mocking framework (deprecated, being phased out in favor of MockK)
- **kotlin-logging-jvm 7.0.3** - Kotlin logging wrapper (lambda-based lazy evaluation)
- **SLF4J 2.0.17** + **Logback 1.5.23** - Logging backend (used by kotlin-logging)
- **Koin 3.5.6** - Kotlin-native dependency injection framework (adopted 2026-01-12, migration complete)

Gradle automatically downloads dependencies during the build. Configuration files:
- `build.gradle.kts` - Build configuration and dependency declarations
- `settings.gradle.kts` - Project settings
- `gradle.properties` - Version management and build properties

**GitHub Packages Authentication:**

To download jDisco from GitHub Packages, set these environment variables:
```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
```

Or create `~/.gradle/gradle.properties`:
```properties
gpr.user=your-github-username
gpr.key=your-personal-access-token
```

**Note:** In GitHub Actions CI/CD, authentication is automatic via `GITHUB_TOKEN`.

### Common Build Commands

**Clean and build (includes tests and uber JAR):**
```bash
./gradlew clean build
```

**Build only (compiles, tests, creates JAR):**
```bash
./gradlew build
```

**Run tests only:**
```bash
./gradlew test
```

**Run integration tests only:**
```bash
./gradlew integrationTest
```

**Run all tests (unit + integration):**
```bash
./gradlew test integrationTest
```

**Create uber JAR (all dependencies included):**
```bash
./gradlew shadowJar
```

**Run simulation (pre-configured shunting loop example):**
```bash
./gradlew runSim
```

**Run editor GUI:**
```bash
./gradlew runEditor
```

**Run custom example:**
```bash
./gradlew runExample -PexampleName=shuntingLoop -PendTime=300
```

**Generate JavaDoc documentation:**
```bash
./gradlew javadoc
```

**Clean everything:**
```bash
./gradlew clean
```

**Show dependency tree:**
```bash
./gradlew dependencies
```

### Directory Structure

The project follows Gradle standard directory layout:
- `src/main/java/` - Main source code
- `src/test/java/` - Test source code
- `src/main/resources/` - Resource files (XML schemas, examples)
- `docker-x11/` - SELinux policy modules for Docker X11 forwarding (Fedora)
- `docs/` - Project documentation
- `build/classes/java/main/` - Compiled main classes
- `build/classes/java/test/` - Compiled test classes
- `build/libs/` - JAR output directory
- `build/test-results/` - Test results (XML and HTML)

### Running Manually

After building with `./gradlew shadowJar`, run from the project root:

**Simulation mode:**
```bash
java -jar build/libs/interlockSim.jar sim [xmlFile]
```

**Editor mode:**
```bash
java -jar build/libs/interlockSim.jar edit [xmlFile]
```

**Built-in examples:**
```bash
java -jar build/libs/interlockSim.jar example [exampleName] [endTime]
```

To list available examples, run:
```bash
java -jar build/libs/interlockSim.jar example
```


## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build and runtime environment with no host dependencies.

The project includes Docker support for both the Java application and LaTeX thesis compilation. This eliminates the need to install Java 21, Gradle, or LaTeX tools on the host machine.

### Prerequisites

Docker/Docker Compose and X11 server (Linux: usually installed; macOS: XQuartz; Windows: VcXsrv/Xming)

### Docker Services

**app:** Java application with X11 GUI support | **text:** LaTeX thesis compilation

### Common Docker Commands

**Build services:**
```bash
# Set GitHub credentials for jDisco download
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token

# Build app (jDisco downloaded from GitHub Packages or uses local cache)
docker compose build app

# Build thesis
docker compose build text
```

**Run editor GUI:**
```bash
# Method 1 (Recommended): Use .Xauthority file (more secure)
docker compose up app

# Method 2: If you get authorization errors, allow X11 connections from Docker
xhost +local:docker
docker compose up app

# When done with Method 2, revoke access for security:
xhost -local:docker
```

**Run simulation example:**
```bash
docker compose run app java -jar interlockSim.jar example shuntingLoop 60
```

**Run simulation with custom XML:**
```bash
docker compose run -v $(pwd)/myfile.xml:/app/myfile.xml app java -jar interlockSim.jar sim myfile.xml
```

**Build thesis PDF:**
```bash
docker compose up text
# PDF will be available in artifacts/text/bakalarka.pdf
```

**Extract compiled JAR:**
```bash
docker compose build app
# JAR will be available in artifacts/app/interlockSim.jar
```

### Docker Architecture

Multi-stage Dockerfile: Builder stage (Temurin 21 JDK, compiles, tests, creates uber JAR) → Runner stage (Temurin 21 JRE, X11 support). `text/Dockerfile` for LaTeX thesis compilation (Debian Bookworm, TeX Live).

### X11 Forwarding Notes

The Docker setup mounts `/tmp/.X11-unix` socket and passes `DISPLAY` environment variable to enable GUI display from the container. The `network_mode: host` setting allows the container to access the host's X11 server.

**Authentication:** The container now mounts your `.Xauthority` file for secure X11 authentication, eliminating the need for `xhost` in most cases.

**Troubleshooting X11 Authorization Errors:**

If you encounter `java.awt.AWTError: Can't connect to X11 window server`:

1. **Check DISPLAY variable:**
   ```bash
   echo $DISPLAY
   # Should show something like :0, :1, or :0.0
   ```

2. **Verify X11 is running:**
   ```bash
   xdpyinfo | head
   # Should show display information
   ```

3. **Use xhost as fallback:**
   ```bash
   xhost +local:docker
   docker compose up app
   # When done:
   xhost -local:docker
   ```

4. **Check .Xauthority permissions:**
   ```bash
   ls -la ~/.Xauthority
   # File should exist and be readable
   ```

5. **For Wayland users (Fedora 43+):**
   ```bash
   # Wayland uses a different socket location
   export DISPLAY=:0
   # Or set XDG_SESSION_TYPE=x11 to force X11 session
   ```

6. **For Fedora with SELinux (Fedora 43+):**
   ```bash
   # If you encounter AVC denial errors, configure SELinux:
   # See docs/FEDORA_DOCKER_X11_SETUP.md for detailed instructions

   # Option 1: Use pre-generated policy (fastest)
   sudo semodule -i docker-x11/docker-x11-complete.pp

   # Option 2: Generate from audit log
   xhost +local:docker
   sudo ausearch -c 'java' --raw | sudo audit2allow -M docker-x11-complete
   sudo semodule -i docker-x11-complete.pp
   ```

### Artifacts

Build outputs copied to `./artifacts/`: `app/interlockSim.jar`, `text/bakalarka.pdf`

### Koin Integration

Koin 3.5.6 fully compatible with Docker (verified 2026-01-12). See `docs/KOTLIN_STYLE_GUIDE.md` for Koin DI guide.

## Architecture

### Core Components

**Main entry point:** `cz.vutbr.fit.interlockSim.Main` - handles three modes:
- `sim` - Run simulation from XML file
- `edit` - Launch graphical editor
- `example` - Run built-in examples (uses reflection to find methods annotated with `@Example`)

**Context system:**
- `Context<out C : Cell>` - Base abstraction for railway network configuration (parameterized over cell type)
- `EditingContext : Context<NodeCell>` - Interface for editing operations on railway network
- `SimulationEnvironment` - Facade interface for simulation operations (11 methods):
  - **Purpose**: Decouples sim/ classes from full SimulationContext contract
  - **Benefits**: Simpler test doubles, DSOL migration readiness, clear simulation process contract
  - **Method groups**: Network queries (4), dynamic state management (3), simulation control (4)
  - **Used by**: Train, InOutWorker, Generator (fully), Interlocking (base class)
  - **Special case**: ShuntingLoop uses SimulationContext (needs getGraph/getRailWayNetGrid during initialization)
- `SimulationContext : Context<Cell>, SimulationEnvironment` - Interface for simulation execution (separate from EditingContext, follows Interface Segregation Principle)
- `RailwayNetGrid<out T : Cell>` - Parameterized grid interface for type-safe cell access
- `AbstractRailwayNetGrid<out T : Cell>` - Parameterized base implementation using `Array2DMap<T>`
- `BaseContext` - Abstract base class with shared infrastructure (257 lines):
  - Grid and graph storage
  - Property change notification (PropertyChangeSupport)
  - Configuration management (maxSpeed, trackLength, nameString)
  - InOut list management
  - Immutability enforcement (freeze/isFrozen/checkNotFrozen)
  - Comprehensive KDoc with thread-safety notes
- `DefaultEditingContext : BaseContext, EditingContext` - Implementation of editing operations (102 lines of domain logic + inherited infrastructure):
  - Extends BaseContext (composition over inheritance)
  - Provides mutable network editing: putCell, removeCell, moveCell, joinCells, removeLine
  - Returns `RailwayNetGrid<NodeCell>` (editing works with node cells only)
- `DefaultSimulationContext : BaseContext, SimulationContext` - Implementation of simulation operations (829 lines):
  - Extends BaseContext directly (does NOT extend DefaultEditingContext)
  - Network structure is immutable (frozen after initialization)
  - Provides simulation-specific operations: run, stop, pathToNextSemaphore, toDynamic
  - Returns `RailwayNetGrid<Cell>` (simulation needs both NodeCell and TrackBlockPart)
  - Uses SimulationProcessFactory for dependency injection
- `ContextTransformer` - Factory for transforming EditingContext to SimulationContext:
  - Stateless singleton object
  - Copies network structure, configuration, and InOut elements
  - Uses GridTransformer for static-to-dynamic cell transformation
  - Enables workflow: edit network → save → load → simulate
- `DefaultContext` - **DEPRECATED** backward-compatibility wrapper extending DefaultSimulationContext (74 lines)
- `SimulationProcessFactory` - Factory interface for creating simulation processes (decouples context from concrete sim/ classes)
- `DefaultSimulationProcessFactory` - Default factory implementation using jDisco-based processes (Generator, InOutWorker)
- `EditingContextFactory` / `SimulationContextFactory` - Factory pattern for context creation
- `XMLContextFactory` - Creates contexts from XML files (defined by `data.xsd` schema)

**Context Refactoring History:**

- **Issue #98 (2026-01-14):** DefaultContext split into DefaultEditingContext and DefaultSimulationContext. See `docs/CONTEXT_REFACTORING_DESIGN.md` and `docs/FACTORY_PATTERN_IMPLEMENTATION.md`.

- **Issue #153 (2026-01-20):** Composition over inheritance refactoring:
  - Extracted BaseContext abstract base class (257 lines of shared infrastructure)
  - DefaultSimulationContext no longer extends DefaultEditingContext
  - SimulationContext no longer extends EditingContext (Interface Segregation Principle)
  - Both contexts extend BaseContext independently (composition pattern)
  - Network immutability enforcement via freeze() mechanism
  - ContextTransformer factory for editing→simulation transformation
  - All 927 tests passing, zero regressions
  - See `docs/CONTEXT_INHERITANCE_INCOMPATIBILITY.md` and `docs/ISSUE_153_RETROSPECTIVE.md`

- **Issue #94 (2026-01-21):** SimulationEnvironment interface decoupling:
  - Created SimulationEnvironment facade interface with 11 essential simulation methods
  - SimulationContext now extends SimulationEnvironment (Liskov substitution)
  - Updated simulation classes: Train, InOutWorker, Generator, Interlocking use SimulationEnvironment
  - ShuntingLoop hybrid approach: accepts SimulationContext (needs getGraph/getRailWayNetGrid for initialization)
  - Factory pattern: SimulationProcessFactory signatures updated to accept SimulationEnvironment
  - All 1321 tests passing, zero behavior changes
  - Enables future DSOL/Kalasim migration via adapter pattern

**Factory Pattern (Phase 2, 2026-01-14):**
DefaultSimulationContext uses dependency injection to obtain a `SimulationProcessFactory` rather than directly instantiating simulation classes. This:
- Follows Dependency Inversion Principle (depends on abstraction, not concrete classes)
- Enables testing with mock factories
- Prepares for jDisco→DSOL/Kalasim migration

**Static/Dynamic Separation (Issue #100):** Wrapper pattern separates static configuration from dynamic state. Use `context.toDynamic(track)` before state operations. See `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md`.

**Grid Parameterization (Issue #131):** Type-safe grid with parameterized cell types (`RailwayNetGrid<out T : Cell>`, `Context<out C : Cell>`). See `docs/GRID_PARAMETERIZATION_*.md` for detailed documentation.

**Object model:**
- `objects/tracks/` - Track facilities, blocks, occupants, DynamicTrack wrapper
- `objects/cells/` - Grid-based spatial representation (uses `Array2DMap`), Dynamic separator wrappers
- `objects/paths/` - Route management

**Utilities:**
- `util/Array2DMap` - Grid data structure with pathfinding extensions
- `util/Array2DMapExtensions.kt` - Kotlin-idiomatic navigation and spatial query operations (multiplatform-compatible)

**Simulation engine:**
- Built on jDisco library (discrete event simulation framework by Keld Helsgaun)
- External dependency from https://github.com/bedavs/jDisco (Java 6 compatible)
- `sim/` package contains simulation processes (e.g., `ShuntingLoop`)

**GUI:**
- Swing-based editor in `gui/` package
- `gui/gridcanvas/` - Grid-based canvas for track layout editing
- `gui/action/` - Editor actions

**XML Configuration:**
- Railway networks defined in XML format
- Schema: `src/main/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`
- Example: `src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
- Elements include: RailSwitch, RailSemaphore, InOut (entry/exit points), track connections

### Package Structure

```
src/
├── main/
│   ├── java/cz/vutbr/fit/interlockSim/
│   │   ├── Main.java              - Application entry point
│   │   ├── context/               - Context management and factories
│   │   ├── gui/                   - Graphical editor
│   │   ├── objects/               - Domain model (tracks, paths, cells)
│   │   ├── sim/                   - Simulation scenarios
│   │   ├── util/                  - Utilities and reporting
│   │   └── xml/                   - XML parsing and serialization
│   └── resources/cz/vutbr/fit/interlockSim/
│       └── resource/              - XML schemas and configuration files
└── test/
    ├── java/cz/vutbr/fit/interlockSim/
    │   ├── context/               - Context and serialization tests
    │   │   ├── ConcurrentSaveTest.java
    │   │   └── DefaultContextTest.java  (tests deprecated wrapper)
    │   ├── sim/                   - Simulation scenario tests
    │   │   ├── InOutWorkerTest.java
    │   │   ├── ShuntingLoopTest.java
    │   │   └── TrainTest.java
    │   ├── testutil/              - Test utilities and builders
    │   │   ├── MockSimulationContext.java  (delegates to DefaultSimulationContext)
    │   │   ├── TestContextBuilder.java
    │   │   ├── TestFixtures.java
    │   │   └── TestTrackBuilder.java
    │   ├── util/                  - Utility class tests
    │   │   ├── Array2DMapTest.kt
    │   │   ├── DoubletonTest.kt
    │   │   ├── EnumUnorientedGraphTest.kt
    │   │   ├── HashMapGraphTest.kt
    │   │   ├── MultimapExtensionsTest.kt
    │   │   └── PointTest.kt
    │   └── xml/                   - XML parsing and validation tests
    │       └── XMLContextFactoryTest.java
    └── resources/cz/vutbr/fit/interlockSim/xml/
        └── fixtures/              - Test XML files (10 fixtures)
```

**Note:** The jDisco library is now maintained as a separate project at https://github.com/bedavs/jDisco

## Kotlin Migration History

**Completed:** January 2026 (100% of 94 files migrated via manual conversion)
- Conservative structure-preserving approach with full test parity (242 tests passing)
- Physics calculations validated against Java baseline (tolerance: 1e-9s, 1e-6m)
- Full jDisco interoperability maintained

## Dependency Injection with Koin

**Status:** Migration complete (2026-01-12)
**Framework:** Koin 3.5.6 (Kotlin-native, lightweight ~1MB)
**Documentation:** See `KOTLIN-MIGRATION-STATUS.md` for migration overview and DI notes, `docs/KOTLIN_STYLE_GUIDE.md` for coding patterns

### Quick Start

```kotlin
// Inject dependencies (property delegation)
class MyClass {
    private val dependency: MyDependency by inject()
}

// Or constructor injection
class MyClass(private val dependency: MyDependency)
```

### Module Organization

Koin modules are defined in `src/main/kotlin/cz/vutbr/fit/interlockSim/di/InterlockSimModule.kt`:

- **utilModule** - Utility classes (ready for expansion)
- **xmlModule** - XML parsing, XMLContextFactory
- **editingModule** - Editing context factories
- **simulationModule** - Simulation context factories and SimulationProcessFactory
- **guiModule** - Swing components (ready for expansion)
- **objectsModule** - Domain model (minimal by design)
- **sim/** - ❌ **EXCLUDED** (wait for jDisco migration, except new factory classes)

**SimulationProcessFactory (2026-01-14):**
The simulation module now provides `SimulationProcessFactory` as a singleton. This factory abstracts creation of simulation processes (Generator, InOutWorker) following the Factory pattern. Contexts receive the factory via constructor injection, eliminating direct dependencies on concrete sim/ classes.

### Critical DI Rules

1. **sim/ package EXCLUDED** - No Koin injection in simulation classes (traffic-simulation-expert requirement)
2. **Contexts are NOT singletons** - Use `factory` or `scope`, never `single`
3. **Preserve factory patterns** - Inject factories, not products
4. **No AOP/proxies** - Koin uses direct instantiation (zero overhead)
5. **Test with golden output** - Validate simulation results unchanged

### Testing with Koin

```kotlin
@Test
fun myTest() {
    startKoin {
        modules(module {
            single<MyDependency> { mock() }
        })
    }
    stopKoin() // Cleanup in @AfterEach
}
```

**Benefits:** Eliminates MockSimulationContext (268 lines), enables MockK/Mockito in 235 test files

See `KOTLIN-MIGRATION-STATUS.md` for comprehensive guide, `docs/KOTLIN_STYLE_GUIDE.md` for coding patterns and DI examples.

## Code Style

Follows `.editorconfig` configuration:
- Java files: tabs (width 4), max line length 120
- XML files: 2 spaces
- UTF-8 encoding, LF line endings

## Code Modification Guidelines

**Now that the project has been migrated to Kotlin and LONG_TERM_GOALS.md is defined, the code modification approach is differentiated by component type:**

### Critical Restrictions (Until jDisco Migration)

**Simulation Core (`sim/` package):**
- **Minimal changes only** - Be extremely conservative with simulation logic
- **No refactoring** - Do not restructure working simulation code
- **Tests required** - Any changes MUST have comprehensive test coverage first
- **No unsolicited improvements** - Only make explicitly requested changes
- **Rationale:** These components use jDisco library. Major changes should wait until migration to DSOL/Kalasim (see LONG_TERM_GOALS.md)

**jDisco Library:**
- **Do not modify** - jDisco is maintained as a separate project at https://github.com/bedavs/jDisco
- Report issues at the jDisco repository

### Flexible Development (Other Components)

**GUI (`gui/` package), Editor, Utilities, Context System:**
- **Modernization allowed** - Can refactor, improve, and apply Kotlin idioms
- **Tests required** - Must have test coverage before and after changes
- **Alignment required** - Changes must align with LONG_TERM_GOALS.md goals and architecture
- **Code quality** - Apply detekt-strict.yml rules for new Kotlin code
- **Rationale:** These components can be improved independently without affecting simulation correctness

### General Rules for All Changes

1. **Tests are mandatory** - Any modified code MUST be covered by tests (before and after)
2. **Align with goals** - Check that changes support or enable LONG_TERM_GOALS.md objectives
3. **No breaking changes** - Maintain backward compatibility with existing XML configurations and APIs
4. **Document decisions** - Update relevant documentation for architectural changes
5. **Quality gates** - All changes must pass: `./gradlew build detekt ktlintCheck test`

### Examples of Appropriate Changes

**ALLOWED (with tests):**
- Refactoring GUI components for Goal 20 (Accessibility)
- Adding new editor features for Goal 16 (Signal Explanation)
- Improving context serialization for Goal 5 (Save/Restore State)
- Modernizing utility classes with Kotlin idioms
- Adding metrics collection infrastructure for Goal 6

**RESTRICTED (until jDisco migration):**
- Changing Train physics calculations
- Modifying jDisco process scheduling
- Restructuring simulation event handling
- Changing core simulation algorithms

**PROHIBITED:**
- Changes that break existing XML configurations
- Modifications that fail existing tests
- Changes that conflict with LONG_TERM_GOALS.md
- jDisco library modifications

## Testing

Comprehensive JUnit 5.11.4 test suite with AssertK assertions located in `src/test/kotlin/cz/vutbr/fit/interlockSim/`. All dependencies are managed via Gradle.

**Test framework:**
- JUnit 5 (Jupiter API and Engine)
- JUnit Platform for test execution
- AssertK 0.28.1 for fluent Kotlin assertions (migrated from AssertJ January 2026)

**Test organization:**
- **Unit tests** - Fast tests that run by default with `./gradlew test` (excludes integration tests)
- **Integration tests** - Tests tagged with `@Tag("integration-test")` that run separately with `./gradlew integrationTest`

**Tagging integration tests:**
To mark a test as an integration test, add the `@Tag("integration-test")` annotation:
```kotlin
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Test
@Tag("integration-test")
fun myIntegrationTest() {
    // Test code
}

// Or tag an entire test class:
@Tag("integration-test")
class MyIntegrationTest {
    @Test
    fun test1() { }

    @Test
    fun test2() { }
}
```

**Test coverage statistics (January 2026):**
- **662 tests total** (628 passing, 34 skipped, 0 failing)
- **51% code coverage** (8,824/17,070 instructions)
- **36 test classes** covering unit tests, integration tests, and edge cases

**Coverage highlights:**
- High coverage (70-85%): tracks, xml, util, cells, context
- Medium coverage (52%): paths
- Limited coverage (33%): sim (jDisco framework restrictions)
- Deferred: gui (0%)

**Test classes (36 total):** Utility (6), Context (5), Simulation (13), Path/Track (7), Cell (4), Entry point (3), XML (1)

**Test utilities:** MockSimulationContext, TestContextBuilder, TestFixtures, TestTrackBuilder, TrackTestMocks, AssertKExtensions

**Test resources:** 10 XML fixtures in `src/test/resources/.../xml/fixtures/`

**Run tests:**
```bash
# Run unit tests only (excludes integration tests)
./gradlew test

# Run integration tests only
./gradlew integrationTest

# Run all tests (unit + integration)
./gradlew test integrationTest

# As part of build (runs unit tests only)
./gradlew build
```

Tests are automatically executed during the build process. The build will fail if any test fails. Regular `./gradlew test` excludes integration tests for faster feedback. Integration tests should be run separately or in CI/CD pipelines.

## Code Quality Analysis

### SonarQube Integration

SonarQube integration provides: code smells, security vulnerabilities, coverage (JaCoCo), duplication, complexity metrics, technical debt.

**Configuration files:**
- `build.gradle.kts` - SonarQube plugin and JaCoCo configuration (primary)
- `sonar-project.properties` - Additional SonarQube settings (optional)
- `.github/workflows/sonarqube.yml` - CI/CD integration for automated analysis

### Running SonarQube Analysis

**SonarCloud (recommended):** Sign up at https://sonarcloud.io, generate token, run:
```bash
./gradlew clean test jacocoTestReport sonar \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.organization=<your-org> \
  -Dsonar.token=<your-token>
```

**Local server:** `docker run -d -p 9000:9000 sonarqube:lts-community`, then use `-Dsonar.host.url=http://localhost:9000`

### Code Coverage with JaCoCo

Generate with `./gradlew test jacocoTestReport`. View HTML at `build/reports/jacoco/test/html/index.html`. Configure thresholds in `build.gradle.kts`.

### Quality Gates and CI/CD

Quality gates permissive by default. Enable strict: `sonar.qualitygate.wait=true`. CI/CD via `.github/workflows/sonarqube.yml` (requires SONAR_TOKEN/SONAR_ORGANIZATION secrets).

### Kotlin Code Quality (Detekt and Ktlint)

The project uses a **dual-level approach** to Kotlin code quality enforcement, distinguishing between legacy Java→Kotlin converted code and new Kotlin code written from scratch.

**Two-level Detekt configuration:**
- `detekt.yml` - **Permissive rules** for legacy Java→Kotlin converted code
  - Focuses on critical bugs and potential crashes
  - Allows legacy patterns (var, complex methods, flexible exception handling)
  - Higher complexity thresholds (cyclomatic: 20, method length: 100)
  - Optional documentation for public APIs
- `detekt-strict.yml` - **Strict rules** for new Kotlin code written from scratch
  - Enforces modern Kotlin best practices
  - Requires immutability (val over var), null safety, documentation
  - Lower complexity thresholds (cyclomatic: 10, method length: 60)
  - Mandates Kotlin idioms (data classes, expression syntax, const)

**Ktlint configuration:**
- Version: 1.5.0
- Respects `.editorconfig` automatically for indentation and line length
- Configured in `build.gradle.kts` ktlint block

**Run checks:**
```bash
./gradlew detekt              # Check legacy/converted code (permissive)
./gradlew detektStrict        # Check new Kotlin code (strict)
./gradlew ktlintCheck         # Check formatting (respects .editorconfig tabs)
./gradlew ktlintFormat        # Auto-format (preserves tab indentation)
./gradlew build               # Includes detekt, ktlintCheck, tests
```

**CRITICAL: Tab Indentation**

Both configurations use **tabs** (not spaces) for indentation to match Java code style from develop branch:
- `.editorconfig`: `indent_style = tab`, `indent_size = 4`, `max_line_length = 120`
- `detekt.yml`: `NoTabs: active: false`, `Indentation: active: false`
- `detekt-strict.yml`: `NoTabs: active: false`, `Indentation: active: false`
- Ktlint reads `.editorconfig` directly for tab settings

**Why tabs?** The original Java code uses tab indentation with 4-space visual width. All Kotlin code (legacy and new) maintains this style for consistency.

**New Kotlin Code Directory Structure:**

Place new Kotlin code (not Java conversions) in:
```
src/main/kotlin/cz/vutbr/fit/interlockSim/new/
```

This triggers strict rule enforcement via the `detektStrict` task.

**Verification:**
```bash
# Verify tabs are preserved after formatting
cat -A src/main/kotlin/path/to/file.kt | head -20  # Tabs show as ^I
```

See `docs/KOTLIN_STYLE_GUIDE.md` for complete details on coding conventions and quality enforcement levels.

## Continuous Integration

GitHub Actions workflow (`.github/workflows/gradle-java21.yml`) runs on push/PR to main/develop branches. Compiles with Java 21, runs tests, packages JAR (90-day artifact retention), and caches dependencies.

View build status: [GitHub Actions](https://github.com/bedavs/interlockSim/actions)

## Documentation

**Thesis:** LaTeX sources in `text/`, build with `docker compose up text` (outputs to `artifacts/text/bakalarka.pdf`)
**JavaDoc:** Generate with `./gradlew javadoc` (outputs to `build/docs/javadoc/`)

## Logging

Uses **kotlin-logging** (SLF4J wrapper) with Logback backend. Configuration: `src/main/resources/logback.xml`

**In Kotlin code:**
```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.debug { "Message with $variable" }  // Lambda-based lazy evaluation
```

**Change log levels:** Edit `logback.xml` or use runtime override:
```bash
java -Dlogback.level=DEBUG -jar interlockSim.jar ...
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -jar interlockSim.jar ...
```

Output: Console + `logs/interlockSim.log` file

## Known Issues

**Critical:** None. All critical SonarQube bugs fixed.

**Notable issues:**
- **SIM-004:** ShuntingLoop hardcoded for `vyhybna.xml` configuration only
- **DEFERRED-001:** XMLContextFactoryTest missing exception type predicates (9 occurrences)
- Minor simulation issues (SIM-001 to SIM-006) documented in code comments

**Test coverage:** 662 tests (628 passing, 34 skipped), 51% code coverage. One disabled performance test (`Array2DMapTest.testSpeed()`).

Run SonarQube for detailed analysis: `./gradlew clean test jacocoTestReport sonar`

## Deprecated Java API Usage

**Key findings:**
- ✅ **RESOLVED:** java.util.Observable/Observer replaced with PropertyChangeSupport (2025-12-28)
- **HIGH:** Integer constructor (1 test occurrence) - use `Integer.valueOf()`
- **MEDIUM:** Internal project deprecations (`TreeMultiMap`, ~27 occurrences)

Run analysis: `./gradlew checkDeprecations`

**jDisco:** Maintained separately at https://github.com/bedavs/jDisco, no deprecated API usage.

## Future Development Considerations

The project currently uses **jDisco** (a discrete/continuous simulation library from 2004, no longer maintained). Research has been conducted on modern alternatives - see `jdisco-research.md` for comprehensive analysis by Claude Opus.

**Key findings:**
- jDisco is abandoned (last updated March 2004)
- Modern alternatives exist with active maintenance and better features

**Recommended migration paths:**
1. **DSOL** (Distributed Simulation Object Library) - Best for combined discrete-continuous simulation
   - Actively maintained (latest: November 2025)
   - Java 17+ support
   - Multi-formalism: discrete-event, continuous, DEVS, agent-based
   - TU Delft, Netherlands
   - Maven: `nl.tudelft.simulation:dsol-core:4.3.2`

2. **Kalasim** - Best for discrete-only simulation with Kotlin
   - Native Kotlin with coroutines
   - Actively maintained
   - Maven: `com.github.holgerbrandl:kalasim`

3. **SSJ** (Stochastic Simulation in Java) - For stochastic/Monte Carlo simulation
   - Université de Montréal
   - Maven: `ca.umontreal.iro.simul:ssj:3.3.2`

**Note:** Any migration from jDisco to modern frameworks is a future development goal and should follow the conservative approach outlined above - thorough testing required before any changes to existing simulation code.