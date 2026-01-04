# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of Technology that simulates railway interlocking systems with a graphical editor and discrete event simulation engine.

[![Gradle Build with Java 11](https://github.com/bedavs/interlockSim/actions/workflows/ant-java11.yml/badge.svg)](https://github.com/bedavs/interlockSim/actions/workflows/ant-java11.yml)

## Build System

This project uses Gradle with Kotlin DSL for building. Java 11 bytecode compatibility is maintained (can be built with Java 11+). The jDisco library module remains at Java 6 for compatibility and uses Maven.

**Migration Note**: Migrated from Apache Ant + Ivy to Gradle in January 2026. Gradle can be launched locally for development and builds. The previous Ant build system has been completely removed.

### Dependency Management

Dependencies are managed via Gradle:
- **jDisco 1.2.0** - Discrete event simulation library (from Maven local repository, Java 6)
- **JUnit 5.10.1** - Testing framework (JUnit Jupiter API and Engine)
- **AssertJ 3.24.2** - Fluent assertion library for better test readability
- **Mockito 5.7.0** - Mocking framework

Gradle automatically downloads dependencies during the build. Configuration files:
- `build.gradle.kts` - Build configuration and dependency declarations
- `settings.gradle.kts` - Project settings
- `gradle.properties` - Version management and build properties

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

The project includes Docker support for both the Java application and LaTeX thesis compilation. This eliminates the need to install Java 11, Ant, or LaTeX tools on the host machine.

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

**Build both services:**
```bash
docker compose build
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
1. **Builder stage** - Uses Debian Buster with OpenJDK 11, Maven, and Ant
   - Builds jDisco dependency (Maven install, Java 6 compatibility)
   - Resolves dependencies via Apache Ivy (automatic download)
   - Compiles Java sources (Java 11 target)
   - Runs all tests with JUnit 5 (build fails if tests fail)
   - Creates uber JAR with all dependencies
2. **Runner stage** - Debian Buster with OpenJDK 11 JRE and X11 libraries
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

Both services copy build outputs to `/artifacts` inside the container, which is mounted to `./artifacts/` on the host:
- `artifacts/app/interlockSim.jar` - Compiled application
- `artifacts/text/bakalarka.pdf` - Compiled thesis

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
- Standalone Maven module in `jdisco/` (Java 6 compatible)
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

jdisco/                            - Third-party discrete event simulation library (Java 6, separate Maven module)
```

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
4. **No unsolicited modernization** - While the project now uses Java 11, do not update Java idioms to modern features, do not add new language features, do not restructure working code
5. **jDisco preservation** - The jDisco module must remain at Java 6 compatibility and should never be modified

This is a working historical codebase from 2007. Stability and preservation are more important than modernization.

## Testing

Comprehensive JUnit 5.10.1 test suite with AssertJ assertions located in `src/test/java/cz/vutbr/fit/interlockSim/`. All dependencies are managed via Apache Ivy.

**Test framework:**
- JUnit 5 (Jupiter API and Engine)
- JUnit Platform for Ant integration
- AssertJ 3.24.2 for fluent assertions

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
ant test
# Or as part of build:
ant build
```

Tests are automatically executed during the build process using Ant's `junitlauncher` task. The build will fail if any test fails (`haltonfailure="yes"`).

**Note:** Ant 1.10.6+ is required for JUnit 5 support via the `junitlauncher` task.

## Continuous Integration

The project uses GitHub Actions for automated build, test, and deployment workflows.

**Workflow:** `.github/workflows/ant-java11.yml`

**Features:**
- Builds jDisco library with Java 6 compatibility
- Compiles main project with Java 11
- Runs all tests with JUnit 5
- Packages application JAR
- Uploads JAR as artifact (90-day retention)
- Smoke test execution
- Dependency caching (Maven and Ivy) for faster builds

**Triggers:**
- Push to `main`, `develop`, `feature/**`, `fix/**` branches
- Pull requests to `main` and `develop`
- Manual workflow dispatch

**Build environment:**
- Ubuntu latest
- Java 11 (Temurin distribution)
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