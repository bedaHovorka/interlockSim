# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of Technology that simulates railway interlocking systems with a graphical editor and discrete event simulation engine.

[![Gradle Build with Java 21](https://github.com/bedavs/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedavs/interlockSim/actions/workflows/gradle-java21.yml)
[![SonarQube Analysis](https://github.com/bedavs/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedavs/interlockSim/actions/workflows/sonarqube.yml)

## Build System

This project uses Gradle with Kotlin DSL for building. Java 21 LTS is the minimum required version.

**Migration Notes**:
- Migrated from Apache Ant + Ivy to Gradle in January 2026
- Migrated from Java 11 to Java 21 LTS in January 2026
- Refactored deprecated Observable/Observer to PropertyChangeSupport
- Extracted jDisco library to separate repository in January 2026

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
- **SLF4J 2.0.17** + **Logback 1.5.23** - Logging framework

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
java -ea -jar build/libs/interlockSim.jar sim [xmlFile]
```

**Editor mode:**
```bash
java -ea -jar build/libs/interlockSim.jar edit [xmlFile]
```

**Built-in examples:**
```bash
java -ea -jar build/libs/interlockSim.jar example [exampleName] [endTime]
```

To list available examples, run:
```bash
java -ea -jar build/libs/interlockSim.jar example
```

**Note:** Enable assertions with `-ea` flag. For memory-constrained environments, add `-Xmx300m`.

## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build and runtime environment with no host dependencies.

The project includes Docker support for both the Java application and LaTeX thesis compilation. This eliminates the need to install Java 21, Gradle, or LaTeX tools on the host machine.

### Prerequisites

- Docker and Docker Compose installed on the host
- X11 server running on the host (for GUI display)
  - Linux: X11 is usually already running
  - macOS: Install XQuartz
  - Windows: Install VcXsrv or Xming

### Docker Services

**app** - Java application with GUI support (X11 forwarding)
**text** - LaTeX thesis compilation

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
docker compose run app java -ea -jar interlockSim.jar example shuntingLoop 60
```

**Run simulation with custom XML:**
```bash
docker compose run -v $(pwd)/myfile.xml:/app/myfile.xml app java -ea -jar interlockSim.jar sim myfile.xml
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

**Root Dockerfile (multi-stage build):**
1. **Builder stage** - Uses Eclipse Temurin 21 JDK
   - Accepts GITHUB_ACTOR and GITHUB_TOKEN as build args
   - Resolves dependencies via Gradle with fallback strategy:
     - First tries mavenLocal() cache (if jDisco was built locally)
     - Falls back to GitHub Packages (requires authentication)
   - Compiles Java sources (Java 21 target)
   - Runs all tests with JUnit 5 (build fails if tests fail)
   - Creates uber JAR with all dependencies
   - Uses BuildKit cache mounts for ~/.m2, ~/.gradle for faster rebuilds
2. **Runner stage** - Eclipse Temurin 21 JRE and X11 libraries
   - Minimal runtime environment
   - X11 forwarding for GUI support
   - No build tools in final image

**text/Dockerfile:**
- Based on Debian Bookworm
- Full TeX Live installation with Czech language support
- Image conversion tools (wmf2eps, autotrace, gnuplot)
- Compiles thesis PDF from LaTeX sources

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

Services copy build outputs to `/artifacts` inside the container, which is mounted to `./artifacts/` on the host:
- `artifacts/app/interlockSim.jar` - Compiled application
- `artifacts/text/bakalarka.pdf` - Compiled thesis

**Note:** jDisco is now consumed as a Maven dependency from GitHub Packages, not built locally in Docker.

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
    │   │   ├── DoubletonTest.java
    │   │   ├── EnumUnorientedGraphTest.java
    │   │   ├── HashMapGraphTest.java
    │   │   └── TreeMultiMapTest.java
    │   └── xml/                   - XML parsing and validation tests
    │       └── XMLContextFactoryTest.java
    └── resources/cz/vutbr/fit/interlockSim/xml/
        └── fixtures/              - Test XML files (10 fixtures)
```

**Note:** The jDisco library is now maintained as a separate project at https://github.com/bedavs/jDisco

## Code Style

Follows `.editorconfig` configuration:
- Java files: tabs (width 4), max line length 120
- XML files: 2 spaces
- UTF-8 encoding, LF line endings

## Important: Conservative Approach to Java Source Modifications

**CRITICAL:** Be extremely conservative when editing Java source files in this legacy codebase.

**Rules:**
1. **Do not touch Java code unless explicitly requested** - Do not refactor, optimize, or "improve" code that is working
2. **Tests must exist before modifications** - Any Java source file being modified MUST be covered by tests first. If tests don't exist, they must be written before making any changes
3. **Minimal changes only** - Make only the specific changes requested, nothing more
4. **No unsolicited modernization** - While the project now uses Java 21, do not update Java idioms to modern features, do not add new language features, do not restructure working code
5. **jDisco library** - jDisco is now maintained as a separate project. Do not modify jDisco code; report issues at https://github.com/bedavs/jDisco

This is a working historical codebase from 2007. Stability and preservation are more important than modernization.

## Testing

Comprehensive JUnit 5.11.4 test suite with AssertJ assertions located in `src/test/java/cz/vutbr/fit/interlockSim/`. All dependencies are managed via Gradle.

**Test framework:**
- JUnit 5 (Jupiter API and Engine)
- JUnit Platform for test execution
- AssertJ 3.27.6 for fluent assertions

**Test organization:**
- **Unit tests** - Fast tests that run by default with `./gradlew test` (excludes integration tests)
- **Integration tests** - Tests tagged with `@Tag("integration-test")` that run separately with `./gradlew integrationTest`

**Tagging integration tests:**
To mark a test as an integration test, add the `@Tag("integration-test")` annotation:
```java
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Test
@Tag("integration-test")
void myIntegrationTest() {
    // Test code
}

// Or tag an entire test class:
@Tag("integration-test")
class MyIntegrationTest {
    @Test
    void test1() { }

    @Test
    void test2() { }
}
```

**Test coverage (237 tests across 13 test classes):**

**Utility tests:**
- `Array2DMapTest` - 10 tests for 2D array-based map implementation
- `DoubletonTest` - 66 tests for immutable ordered pair data structure
- `EnumUnorientedGraphTest` - 55 tests for enum-based unoriented graph
- `HashMapGraphTest` - 48 tests for HashMap-based graph implementation
- `TreeMultiMapTest` - 25 tests for tree-based multimap implementation

**Context tests:**
- `DefaultContextTest` - 8 tests for railway network context operations
- `ConcurrentSaveTest` - 2 tests for thread-safe XML serialization

**Simulation tests:**
- `TrainTest` - 6 tests for train behavior and state management
- `InOutWorkerTest` - 8 tests for entry/exit point worker operations
- `ShuntingLoopTest` - 2 tests for shunting loop simulation scenario

**XML tests:**
- `XMLContextFactoryTest` - 7 tests for XML parsing and validation with 10 fixture files

**Test utilities:**
- `MockSimulationContext` - Mock implementation for testing
- `TestContextBuilder` - Fluent builder for test contexts
- `TestFixtures` - Shared test data and configurations
- `TestTrackBuilder` - Fluent builder for test track layouts

**Test resources:**
- `src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/` - 10 XML test fixtures for parser validation

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

The project includes SonarQube integration for static code analysis and quality metrics. SonarQube provides comprehensive analysis including:
- Code smells and maintainability issues
- Security vulnerabilities
- Code coverage (via JaCoCo)
- Code duplication detection
- Complexity metrics
- Technical debt assessment

**Configuration files:**
- `build.gradle.kts` - SonarQube plugin and JaCoCo configuration (primary)
- `sonar-project.properties` - Additional SonarQube settings (optional)
- `.github/workflows/sonarqube.yml` - CI/CD integration for automated analysis

### Running SonarQube Analysis

**Prerequisites:**
- SonarQube server running (local or cloud)
- SonarQube authentication token

#### Option 1: SonarCloud (Recommended for Open Source)

SonarCloud is free for public repositories and requires no infrastructure setup.

**Setup:**
1. Sign up at https://sonarcloud.io with your GitHub account
2. Create a new project and organization
3. Generate a token: User Menu > My Account > Security > Generate Tokens
4. Run analysis:
   ```bash
   ./gradlew clean test jacocoTestReport sonar \
     -Dsonar.host.url=https://sonarcloud.io \
     -Dsonar.organization=<your-org> \
     -Dsonar.token=<your-token>
   ```

**Environment variables (alternative to command-line):**
```bash
export SONAR_HOST_URL=https://sonarcloud.io
export SONAR_ORGANIZATION=<your-org>
export SONAR_TOKEN=<your-token>
./gradlew clean test jacocoTestReport sonar
```

#### Option 2: Local SonarQube Server

Run SonarQube locally with Docker for private analysis.

**Start SonarQube server:**
```bash
docker run -d --name sonarqube \
  -p 9000:9000 \
  sonarqube:lts-community
```

**Access and setup:**
1. Open http://localhost:9000 in browser
2. Login with default credentials: admin/admin (change password on first login)
3. Create new project manually or use automatic setup
4. Generate token: User Menu > My Account > Security > Generate Tokens

**Run analysis:**
```bash
./gradlew clean test jacocoTestReport sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=<your-token>
```

### Code Coverage with JaCoCo

The project uses JaCoCo for code coverage measurement, which is integrated with SonarQube.

**Generate coverage report:**
```bash
./gradlew test jacocoTestReport
```

**View coverage report:**
Open `build/reports/jacoco/test/html/index.html` in a browser

**Coverage report locations:**
- HTML report: `build/reports/jacoco/test/html/index.html`
- XML report (for SonarQube): `build/reports/jacoco/test/jacocoTestReport.xml`

**Current coverage baseline:**
- The project has 237 tests across 13 test classes
- Coverage thresholds start at 0% and can be increased gradually
- Configure thresholds in `build.gradle.kts` under `jacocoTestCoverageVerification`

### Quality Gates

SonarQube quality gates define the criteria for code quality acceptance. By default, the build does not fail if quality gates fail (suitable for legacy code). To enable strict quality gates:

**In build.gradle.kts:**
```kotlin
property("sonar.qualitygate.wait", "true")
```

**In sonar-project.properties:**
```properties
sonar.qualitygate.wait=true
```

### CI/CD Integration

See `.github/workflows/sonarqube.yml` for automated SonarQube analysis on every push and pull request. The workflow requires:
- `SONAR_TOKEN` secret configured in GitHub repository settings
- `SONAR_ORGANIZATION` secret (for SonarCloud)

## Continuous Integration

The project uses GitHub Actions for automated build, test, and deployment workflows.

**Workflow:** `.github/workflows/gradle-java21.yml`

**Features:**
- Compiles main project with Java 21
- Runs all tests with JUnit 5
- Packages application JAR
- Uploads JAR as artifact (90-day retention)
- Smoke test execution
- Dependency caching (Gradle) for faster builds
- Requires jDisco 1.2.0 from Maven local repository

**Triggers:**
- Push to `main`, `develop`, `feature/**`, `fix/**` branches
- Pull requests to `main` and `develop`
- Manual workflow dispatch

**Build environment:**
- Ubuntu latest
- Java 21 (Temurin distribution)
- 15-minute timeout
- Concurrency control (cancels outdated builds)

**Performance:**
- First build (cold cache): ~3-5 minutes
- Subsequent builds (warm cache): ~30-60 seconds

View build status and artifacts at: [GitHub Actions](https://github.com/bedavs/interlockSim/actions)

## Documentation

LaTeX-based thesis documentation in `text/` directory.

**Build thesis PDF:**
```bash
cd text
make
```

Requires: `make`, `gnuplot`, `latex`, `wmf2eps`, `sed`

**Generate JavaDoc:**
```bash
ant doc
```

Output goes to `doc/` directory.

## Logging

The application uses SLF4J with Logback for logging. This provides flexible log configuration and runtime control.

### Configuration Files

- **Main application:** `src/main/resources/logback.xml` - Logback configuration
- **jDisco tests:** `jdisco/src/test/resources/simplelogger.properties` - SLF4J simple logger for tests

### Log Levels

Standard SLF4J log levels (most to least verbose):
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
java -Dlogback.level=DEBUG -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

**Docker environment variable:**
```bash
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -ea -jar interlockSim.jar example shuntingLoop 60
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

When modifying Java source code (following the conservative approach), use SLF4J logging:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger logger = LoggerFactory.getLogger(ClassName.class);

// In methods:
logger.trace("Very detailed trace message");
logger.debug("Debug message with context: {}", variable);
logger.info("Informational message");
logger.warn("Warning message");
logger.error("Error message", exception);
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

#### DEFERRED-003: Doubleton Missing equals() Override

**Severity:** Minor (SonarQube rule java:S1206)
**File:** `src/main/java/cz/vutbr/fit/interlockSim/util/Doubleton.java` (line 85)

**Description:** Class overrides `hashCode()` but not `equals()`, violating the hashCode/equals contract.

**Impact:** Potential incorrect behavior when Doubleton objects are used in hash-based collections.

**Workaround:** Doubleton class is marked `@Deprecated`. Avoid using in new code.

**Recommendation:** Replace Doubleton with modern alternatives (e.g., `Map.Entry`, records) in future modernization.

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

**Description:** Critical validation logic uses Java `assert` statements, which are disabled without the `-ea` flag.

**Impact:** Invalid states may not be detected when running without assertions enabled.

**Workaround:** Always run the application with `-ea` flag: `java -ea -jar interlockSim.jar ...`

**Recommendation:** Convert critical assertions to explicit validation with exceptions.

### Test Suite Notes

**Skipped Tests:** 5 tests (2.1% of suite) are currently disabled:
- `ShuntingLoopTest`: 4 tests marked with `@Disabled` annotation
- `XMLContextFactoryTest`: 1 test with conditional skip logic

These skipped tests document known initialization edge cases and do not indicate failures. See test source files for specific skip reasons.

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
- Internal project classes marked deprecated (`Doubleton`, `TreeMultiMap`) - 55 occurrences
  - These are project-specific deprecations, not Java SE
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