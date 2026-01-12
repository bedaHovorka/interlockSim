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
- **AssertJ 3.27.6** - Fluent assertion library for better test readability
- **Mockito 5.21.0** - Mocking framework
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

### Artifacts

Build outputs copied to `./artifacts/`: `app/interlockSim.jar`, `text/bakalarka.pdf`

### Koin Integration

**Status:** ✅ Migration complete, fully compatible with Docker (verified 2026-01-12)

The Docker configuration supports Koin dependency injection framework without any modifications:
- Koin 3.5.6 automatically included in uber JAR via Gradle dependency resolution
- JAR size: 6.6 MB (Koin adds ~1.12 MB, within expected overhead)
- All 698 tests pass in Docker build
- All application modes work: `sim`, `edit`, `example`

**Quick verification:**
```bash
# Build and test
docker compose build app

# Verify Koin is included
docker run --rm interlocksim:latest sh -c \
  "unzip -l /app/interlockSim.jar | grep org/koin/ | wc -l"
# Output: 225+ Koin class files

# Run simulation with Koin DI
docker compose run --rm app java -jar interlockSim.jar example shuntingLoop 10
```

For detailed Docker + Koin integration guide, see:
- `DOCKER_KOIN_QUICKSTART.md` - Quick reference and commands
- `KOIN_DOCKER_INTEGRATION_REPORT.md` - Full verification report
- `KOTLIN_STYLE_GUIDE.md` - Complete Koin DI guide (section: Dependency Injection with Koin)

## Architecture

### Core Components

**Main entry point:** `cz.vutbr.fit.interlockSim.Main` - handles three modes:
- `sim` - Run simulation from XML file
- `edit` - Launch graphical editor
- `example` - Run built-in examples (uses reflection to find methods annotated with `@Example`)

**Context system:**
- `Context` - Base abstraction for railway network configuration
- `SimulationContext` - Simulation execution context
- `EditingContextFactory` / `SimulationContextFactory` - Factory pattern for context creation
- `XMLContextFactory` - Creates contexts from XML files (defined by `data.xsd` schema)

**Object model:**
- `objects/tracks/` - Track facilities, blocks, occupants
- `objects/cells/` - Grid-based spatial representation
- `objects/paths/` - Route management

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
    │   │   └── DefaultContextTest.java
    │   ├── sim/                   - Simulation scenario tests
    │   │   ├── InOutWorkerTest.java
    │   │   ├── ShuntingLoopTest.java
    │   │   └── TrainTest.java
    │   ├── testutil/              - Test utilities and builders
    │   │   ├── MockSimulationContext.java
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
**Documentation:** See `KOIN_MIGRATION_SUMMARY.md` for complete guide, `KOTLIN_STYLE_GUIDE.md` for coding patterns

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
- **contextModule** - Context lifecycle management
- **guiModule** - Swing components (ready for expansion)
- **objectsModule** - Domain model (minimal by design)
- **sim/** - ❌ **EXCLUDED** (wait for jDisco migration)

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

**Benefits:** Eliminates MockSimulationContext (268 lines), enables Mockito in 235 test files

See `KOIN_MIGRATION_SUMMARY.md` for comprehensive guide, `KOTLIN_STYLE_GUIDE.md` for coding patterns and DI examples.

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

See `KOTLIN_STYLE_GUIDE.md` for complete details on coding conventions and quality enforcement levels.

## Continuous Integration

GitHub Actions workflow (`.github/workflows/gradle-java21.yml`) runs on push/PR to main/develop branches. Compiles with Java 21, runs tests, packages JAR (90-day artifact retention), and caches dependencies.

View build status: [GitHub Actions](https://github.com/bedavs/interlockSim/actions)

## Documentation

**Thesis:** LaTeX sources in `text/`, build with `make` (requires gnuplot, latex, wmf2eps)
**JavaDoc:** `ant doc` outputs to `doc/` directory

## Logging

The application uses **kotlin-logging** (a Kotlin wrapper for SLF4J) with Logback as the backend. This provides flexible log configuration, runtime control, and lambda-based lazy evaluation to eliminate verbose guard checks.

**Migration Status (January 2026):** Migrated from SLF4J to kotlin-logging. All logger declarations now use `KotlinLogging.logger {}` for automatic lazy evaluation and cleaner syntax.

### Configuration Files

- **Main application:** `src/main/resources/logback.xml` - Logback configuration
- **jDisco tests:** `jdisco/src/test/resources/simplelogger.properties` - SLF4J simple logger for tests

### Log Levels

Standard log levels (most to least verbose):
- `TRACE` - Very detailed diagnostic information
- `DEBUG` - Detailed debugging information
- `INFO` - General informational messages (default for most loggers)
- `WARN` - Warning messages
- `ERROR` - Error messages

### Changing Log Levels During Development

**Edit logback.xml:**
```xml
<!-- Change root logger level (affects all classes) -->
<root level="DEBUG">
    <appender-ref ref="CONSOLE"/>
</root>

<!-- Or target specific packages/classes -->
<logger name="cz.vutbr.fit.interlockSim.sim.Train" level="TRACE"/>
<logger name="cz.vutbr.fit.interlockSim.sim.ShuntingLoop" level="DEBUG"/>
```

**Runtime system property override:**
```bash
java -Dlogback.level=DEBUG -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

**Docker environment variable:**
```bash
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -jar interlockSim.jar example shuntingLoop 60
```

### Pre-configured Loggers

The following loggers are configured in `logback.xml`:

- `cz.vutbr.fit.interlockSim.simulation` - Simulation events (INFO, separate file output)
- `jDisco.statistics` - jDisco statistical reports (INFO, console only)
- `cz.vutbr.fit.interlockSim.sim.Train` - Train behavior (DEBUG)
- `cz.vutbr.fit.interlockSim.sim.ShuntingLoop` - Shunting loop operations (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.paths.AbstractPath` - Path management (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack` - Track operations (DEBUG)

### Log Output Destinations

**Console appender:**
- Format: `HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`
- All loggers by default

**File appender:**
- Location: `logs/interlockSim.log`
- Format: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`
- Append mode (accumulates across runs)
- Captures simulation events logger output

### Adding Logging to Code

When adding logging to Kotlin code, use kotlin-logging for automatic lazy evaluation:

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

// In methods - lambda-based lazy evaluation (no manual guard checks needed):
logger.trace { "Very detailed trace message" }
logger.debug { "Debug message with context: $variable" }
logger.info { "Informational message" }
logger.warn { "Warning message" }
logger.error(exception) { "Error message" }
```

**Benefits of kotlin-logging:**
- **No manual guard checks** - Eliminates verbose `if (logger.isDebugEnabled)` patterns
- **Lazy evaluation** - Lambda content only evaluated if log level is enabled
- **String interpolation** - Use Kotlin string templates (`$variable`) instead of SLF4J placeholders
- **Cleaner syntax** - Idiomatic Kotlin logger initialization

**Legacy SLF4J syntax (deprecated, use only for compatibility):**

```kotlin
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger(ClassName::class.java)

// Old pattern with manual guard checks (avoid in new code):
if (logger.isDebugEnabled) {
    logger.debug("Debug message with context: {}", variable)
}
```

## Known Bugs and Issues

**Last Updated:** 2026-01-05

This section documents known bugs that remain in the codebase. These have been identified through SonarQube analysis and comprehensive simulation verification. For detailed analysis, see the report files in the project root.

### Critical Issues

None. All critical bugs identified by SonarQube have been fixed.

### Major Issues

#### DEFERRED-001: Missing Assertion Predicates in XMLContextFactoryTest (9 occurrences)

**Severity:** Major (SonarQube rule java:S5833)
**Files:** `src/test/java/cz/vutbr/fit/interlockSim/xml/XMLContextFactoryTest.java`
**Lines:** 219, 228, 237, 246, 255, 262, 271, 281, 433

**Description:** Tests use `assertThatThrownBy()` without specifying expected exception types. The assertions verify that methods execute without unexpected exceptions but lack explicit exception class predicates.

**Impact:** Tests function correctly but are less precise than they could be. No false positives or missed failures observed.

**Workaround:** Tests work as intended; this is a test quality enhancement opportunity.

**Recommendation:** Add `.isInstanceOf(ExpectedException.class)` predicates in future test improvements.

### Minor Issues

#### SIM-001: Potential Division by Zero in Motor Calculation

**Severity:** Minor (mitigated)
**File:** `src/main/java/cz/vutbr/fit/interlockSim/sim/Train.java` (Motor inner class, line ~468)

**Description:** The acceleration calculation `a = ((targetSpeed - velocity)*(targetSpeed + velocity)) / (2*s)` could divide by zero if distance `s` approaches zero.

**Impact:** Numerical instability in edge cases.

**Workaround:** Already mitigated by `if (s <= 0) { accelerate = false; return; }` guard.

**Recommendation:** No action required; current mitigation is sufficient.

#### SIM-002: Static Train Counter Not Reset Between Runs

**Severity:** Minor
**File:** `src/main/java/cz/vutbr/fit/interlockSim/sim/Train.java` (line 487)

**Description:** Static `count` variable increments across simulation runs without reset.

**Impact:** Cosmetic only - train IDs continue incrementing in same JVM instance. Affects logging/toString output.

**Workaround:** Restart JVM between simulation runs if sequential train numbering is required.

#### SIM-003: Unused Variable Increment in Generator

**Severity:** Minor (dead code)
**File:** `src/main/java/cz/vutbr/fit/interlockSim/sim/Generator.java` (line 87)

**Description:** Variable `i` is incremented but never used.

**Impact:** None - dead code with no functional effect.

**Workaround:** None needed.

#### DEFERRED-002: Integer Division Precision Loss (3 occurrences)

**Severity:** Minor (SonarQube rule java:S2184)
**Files:**
- `src/main/java/cz/vutbr/fit/interlockSim/gui/gridcanvas/CellRenderer.java` (line 83, 2 occurrences)
- `src/main/java/cz/vutbr/fit/interlockSim/objects/cells/Cell.java` (line 146)

**Description:** Integer division where result is assigned to double, potentially losing fractional part.

**Impact:** May affect GUI rendering precision. No visual issues observed in testing.

**Workaround:** None needed for current functionality.

**Recommendation:** Review with domain expert if high-precision rendering is required.

#### RESOLVED: Doubleton equals() Override

**Status:** RESOLVED (equals() override added during Kotlin migration)
**File:** `src/main/kotlin/cz/vutbr/fit/interlockSim/util/Doubleton.kt` (line 110)

**Description:** Class now properly overrides both `hashCode()` and `equals()`, satisfying the contract.

**Resolution:** The equals() method was added during Kotlin migration. Additionally, the incorrect `@Deprecated` annotation was removed after analysis showed that Doubleton has no equivalent in Kotlin's standard library. Kotlin's `Pair` is ordered (A,B ≠ B,A), while Doubleton is unordered (A,B = B,A) and supports associated values, making it essential for representing bidirectional graph edges.

**Documentation:** Updated KDoc explains why Doubleton cannot be replaced with Kotlin's Pair.

### Design Limitations

#### SIM-004: Hardcoded Grid Coordinates in ShuntingLoop

**Severity:** Design Limitation (documented)
**File:** `src/main/java/cz/vutbr/fit/interlockSim/sim/ShuntingLoop.java` (lines 116-129)

**Description:** ShuntingLoop uses hardcoded grid positions (x/y coordinates) that only work with `vyhybna.xml` network configuration.

**Impact:** Cannot reuse ShuntingLoop with other railway network configurations without code changes.

**Workaround:** Use only with the provided `vyhybna.xml` configuration file.

**Recommendation:** Future enhancement - make ShuntingLoop configurable via XML or constructor parameters.

#### SIM-005: Negative Train Length Allowed

**Severity:** Low (validation gap)
**File:** `src/main/java/cz/vutbr/fit/interlockSim/sim/Train.java`

**Description:** Train constructor accepts negative length values without validation.

**Impact:** Could cause undefined simulation behavior with invalid inputs.

**Workaround:** Ensure positive train lengths when creating Train instances.

**Recommendation:** Add parameter validation in future releases.

#### SIM-006: Assertion-Only Validation

**Severity:** Medium (production concern)
**Files:** Multiple simulation classes


**Impact:** Invalid states may not be detected when running without assertions enabled.


**Recommendation:** Convert critical assertions to explicit validation with exceptions.

### Test Suite Notes

**Skipped Tests:** 1 test (0.4% of suite) is currently disabled:
- `Array2DMapTest.testSpeed()`: Performance benchmark marked with `@Disabled` annotation due to timing variability

**Note:** As of January 2026, 4 previously disabled ShuntingLoop tests were re-enabled after confirming they properly verify exception handling for invalid contexts. The tests validate that ShuntingLoop fails gracefully (throwing appropriate exceptions) when given incompatible network structures, which is correct behavior despite the design limitation documented as SIM-004.

### Reference Reports

For comprehensive details, see:
- `SIMULATION-VERIFICATION-REPORT.md` - Simulation engine analysis and SIM-* issues
- `GOAL3-PHASE2-REPORT.md` - SonarQube findings and bug triage
- `FINAL-VERIFICATION-REPORT.md` - Consolidated verification results
- `QA_ISSUES_DETAIL.md` - QA testing findings

## Deprecated Java API Usage

**Analysis Last Run:** 2026-01-05

The codebase has been analyzed for deprecated Java standard library APIs. This is critical for planning future Java version upgrades, especially to Java 17+.

### Key Findings

**CRITICAL Issue:**
- **java.util.Observable/Observer** (6 occurrences) - Deprecated in Java 9, blocks Java 17+ migration
  - Used in: `DefaultContext`, `Context`, `RailwayNetGridCanvas`, `StatusBar`
  - **Must be replaced** before upgrading to Java 17+
  - **Recommended replacement:** `java.beans.PropertyChangeSupport`

**HIGH Priority:**
- **Integer constructor** (1 occurrence in test code) - Deprecated in Java 9, marked for removal
  - Easy fix: Use `Integer.valueOf()` or autoboxing

**MEDIUM Priority:**
- Internal project classes marked deprecated (`TreeMultiMap`) - ~27 occurrences
  - These are project-specific deprecations, not Java SE
  - Note: `Doubleton` deprecation was removed after analysis showed no Kotlin stdlib equivalent
  - Require design review to determine replacement strategy

### Monitoring

Run deprecation analysis:
```bash
./gradlew checkDeprecations
```

Generate detailed report:
```bash
./gradlew clean compileJava compileTestJava 2>&1 | tee build/reports/deprecation-main.txt
```

Review comprehensive report:
```bash
cat docs/deprecated-api-report.md
```

### jDisco Library

The jDisco library (Java 6 compatible) is now maintained as a separate project at https://github.com/bedavs/jDisco and has **no deprecated Java API usage**. It remains at Java 6 compatibility as designed.

**Note:** This analysis is documentation-only for the interlockSim codebase. See `docs/deprecated-api-report.md` for detailed findings about interlockSim. For jDisco deprecation analysis, see the jDisco repository.

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