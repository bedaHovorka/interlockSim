# Railway Interlocking Simulator

[![Gradle Build](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml)
[![SonarQube Analysis](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml)

A discrete-event/continuous combined simulation system for railway interlocking 
with a graphical track editor and XML-based configuration.

**BSc Thesis Project (2006/2007)**
Brno University of Technology
Faculty of Information Technology
Author: Bedrich Hovorka

---

## Overview

**InterlockSim** is a Kotlin-based railway interlocking simulator that combines:

- **Graphical Editor** - Design railway track layouts with switches, semaphores, and entry/exit points
- **Discrete Event Simulation** - Simulate train movements and interlocking logic using the kDisco framework
- **Continuous Simulation** - Model continuous train positions and speeds
- **XML Configuration** - Define and save railway networks in a structured XML format
- **Process-Oriented Modeling** - Built-in examples demonstrate shunting yard operations

The simulator uses a combined discrete-continuous simulation approach powered by the kDisco library (Keld Helsgaun, Roskilde University).

---

## Features

- **Interactive track editor** with grid-based layout
- **Animated GUI simulation** - Real-time train movement visualization with event logging (30 FPS)
- **XML schema-validated** railway network definitions
- **Discrete event simulation engine** (kDisco-based)
- **Built-in examples** including shunting loop scenarios
- **Swing GUI** for visualization and editing
- **Assertion-based validation** for simulation integrity
- **Dynamic state visualization** - Track coloring (FREE/RESERVED/OCCUPIED), signal states, switch positions

---

## System Requirements

### Option 1: Docker (Recommended)

- **Docker** and **Docker Compose**
- **X11 Server** (for GUI display)
  - Linux: Usually already running
  - macOS: Install XQuartz
  - Windows: Install VcXsrv or Xming

### Option 2: Native Build

- **Java**: JDK 21 or later (Java 21 LTS minimum)
- **Build Tool**: Gradle (wrapper included)
- **Dependencies**: Automatically managed via Gradle (versions in `gradle.properties`)
  - `cz.ksimulantenbande.kdisco:kdisco-core` 0.6.1-SNAPSHOT (from GitHub Packages, or `mavenLocal()`)
  - Koin 3.5.6 - dependency injection
  - Koog 1.1.1 - LLM agent framework, used by `:dispatcher-agent`
  - JUnit 5.11.4 (from Maven Central)
  - assertk 0.28.1 - assertions
  - MockK 1.13.14 - Kotlin-native mocking for sealed classes
  - Burst 2.12.2 and Mokkery 3.3.0 - multiplatform test parameterization and mocking

### Optional (for thesis documentation):
- LaTeX, gnuplot, make, wmf2eps, sed

---

## Quick Start with Docker

**Dockerization: 2025** - Complete containerized build and runtime environment with no host dependencies.

### Prerequisites: GitHub Packages credentials

The build downloads the kDisco dependency from GitHub Packages, which requires
authentication. Put your credentials in `.env` (copy `.env.example`) and **export
them** before building — `docker compose` auto-loads `.env` only for `${VAR}`
interpolation, while the BuildKit secret that carries the token reads the
*process* environment. A non-exported `.env` produces an empty token and a 401:

```bash
set -a; source .env; set +a    # export GITHUB_ACTOR / GITHUB_TOKEN
```

Use a Personal Access Token with the `read:packages` scope (see `.env.example`).

### Build and Run

```bash
# Build Docker images (after exporting credentials, see above)
docker compose build

# Run graphical editor (X11 forwarding)
docker compose up app

# Run simulation example
docker compose run app java -jar interlockSim.jar example shuntingLoop 60

# Build thesis PDF
docker compose up text
# PDF available in artifacts/text/bakalarka.pdf
```

### X11 Troubleshooting

If you encounter `Can't connect to X11 window server`:

```bash
# Allow Docker X11 access
xhost +local:docker
docker compose up app

# When done, revoke access
xhost -local:docker
```

For more details, see the Docker section below or `CLAUDE.md`.

---

## Prerequisites for Windows: Running the GUI in Docker

The default `docker compose run app` command opens the Swing editor GUI and requires an X11
server on the Windows host. Docker containers cannot use the Windows display directly, so a
separate X server is needed.

### Steps

1. **Install VcXsrv**
   Download and install from https://sourceforge.net/projects/vcxsrv/

2. **Launch XLaunch** (included with VcXsrv) with these settings:
   - Display settings: **Multiple windows**, Display number `0`
   - Client startup: **Start no client**
   - Extra settings: check **Disable access control**
   - Click **Finish**

3. **Allow VcXsrv through Windows Firewall**
   Windows will prompt on first launch — click **Allow access** for both private and public
   networks. Without this, the Docker container connection is silently dropped.

4. **Run the app**
   ```bash
   docker compose run app
   ```
   The editor GUI will appear in the VcXsrv window.

### Why this is needed

The container sets `DISPLAY=host.docker.internal:0` when `$DISPLAY` is not set in the
environment. This routes the X11 connection from the Linux container over TCP to port 6000 on
the Windows host, where VcXsrv is listening. The Unix socket path (`/tmp/.X11-unix`) used on
Linux does not exist on Windows.

---

## Building the Project

### Quick Start

```bash
# Clean and build (compiles main + tests, runs tests, creates JAR)
./gradlew clean build

# Run simulation (shunting loop example)
./gradlew runSim

# Run graphical editor
./gradlew runEditor

# Generate JavaDoc
./gradlew javadoc
```

### Build Tasks

| Task | Description |
|------|-------------|
| `./gradlew build` | Compile all sources, run tests, create JAR (build fails if tests fail) |
| `./gradlew test` | Run unit tests only |
| `./gradlew integrationTest` | Run integration tests |
| `./gradlew heavyTest` | Run high-repetition stress tests. Manual only, never in CI |
| `./gradlew jmh` | Run JMH performance benchmarks (see `desktop-ui/src/jmh/kotlin/README.md`) |
| `./gradlew detekt` | Static analysis |
| `./gradlew ktlintCheck` / `ktlintFormat` | Check or apply Kotlin formatting |
| `./gradlew jacocoTestReport` | Coverage report |
| `./gradlew clean` | Remove build artifacts |
| `./gradlew shadowJar` | Create uber JAR file with all dependencies |
| `./gradlew runSim` | Run pre-configured shunting loop simulation |
| `./gradlew runEditor` | Launch graphical editor |
| `./gradlew runExample` | Run custom example with parameters |
| `./gradlew runExampleGui` | Run an example in the animated GUI |
| `./gradlew runExampleAIGui` | Run the LLM-dispatcher example in the animated GUI (needs Ollama) |
| `./gradlew runSimFromXml` | Run a simulation from an XML file |
| `./gradlew javadoc` | Generate JavaDoc documentation |

**Note:** Dependencies are automatically downloaded during build via Gradle. On Windows, use `gradlew.bat` instead of `./gradlew`.

---

## Running the Simulator

### 1. Graphical Editor Mode

Open the track editor to design railway layouts:

```bash
./gradlew runEditor
```

Or manually (after building):
```bash
java -jar desktop-ui/build/libs/interlockSim.jar edit [xmlFile]
```

![InterlockSim Editor](text/img/Screenshot%20at%202026-01-03%2009-09-58.png)

*The graphical track editor showing a simple shunting loop layout with entry/exit points and rail switches.*

### 2. Simulation Mode

Run a simulation from an XML configuration file:

```bash
java -jar desktop-ui/build/libs/interlockSim.jar sim [xmlFile]
```

### 3. Built-in Examples

Run pre-configured simulation scenarios:

```bash
# List all available examples
java -jar desktop-ui/build/libs/interlockSim.jar example

# Run shunting loop example for 1024 time units
java -jar desktop-ui/build/libs/interlockSim.jar example shuntingLoop 1024
```

**Quick example:**
```bash
# Build and run shunting yard simulation (1024 seconds model time)
./gradlew clean build
java -jar desktop-ui/build/libs/interlockSim.jar example shuntingLoop 1024
```

### 4. Animated GUI Simulation

**AnimatedSim Milestone** - Real-time visual simulation with animated trains and event logging.

Run simulation examples with animated GUI:

```bash
# Recommended: Use Gradle task
./gradlew runExampleGui

# Or manually after building
java -jar desktop-ui/build/libs/interlockSim.jar exampleGui shuntingLoop 1024
```

![AnimatedSim GUI](text/img/Screenshot%20at%202026-02-04%2013-47-30.png)

*The animated GUI showing real-time train movement with dynamic track coloring (Gray/Yellow/Red), signal states (RED/GREEN), and event timeline with simulation time display.*

**Features:**
- **Real-time train animation** - Watch trains move smoothly across the track network (30 FPS)
- **Dynamic track coloring** - Tracks change color based on state:
  - 🟤 **Gray** - FREE (available for reservation)
  - 🟡 **Yellow** - RESERVED (path set for train)
  - 🔴 **Red** - OCCUPIED (train currently on track)
- **Signal visualization** - Semaphores show RED (stop) or GREEN (proceed)
- **Switch position display** - Rail switches indicate MAIN or BRANCH position
- **Event timeline** - Scrollable log of simulation events with filtering:
  - Path commands (reservation, release)
  - Node events (switch/signal state changes)
  - Train events (stop, exit, arrival)
  - Continuous updates (position/velocity at 1 Hz)
- **Time display** - Current simulation time in HH:MM:SS.mmm format

**Example Usage:**

```bash
# Run with default settings (shunting loop, 1024 time units)
./gradlew runExampleGui

# Run specific example with custom duration
java -jar desktop-ui/build/libs/interlockSim.jar exampleGui shuntingLoop 600

# Docker users
docker compose run app java -jar interlockSim.jar exampleGui shuntingLoop 1024
```

**Performance:**
- Smooth 30 FPS animation with multiple trains
- Event-driven updates (no polling overhead)
- Optimized state capture with caching (20-80× faster than polling)

**Technical Details:**
- Built with Swing GUI components
- Thread-safe animation controller (EDT marshaling)
- Immutable state snapshots for rendering
- PropertyChangeListener-based event propagation

See `docs/IMPLEMENTATION_SUMMARY_ISSUE_205.md` for detailed architecture documentation.

### Command-Line Synopsis

```
java -jar desktop-ui/build/libs/interlockSim.jar (sim|simgui|edit|example|exampleGui|aiSweep) [arguments]
```

**Modes:**
- `sim [file.xml]` - Run simulation from XML file (console output)
- `simgui [file.xml]` - Run simulation from XML file with the animated GUI
- `edit [file.xml]` - Open editor (optionally load file)
- `example [name] [endTime]` - Run built-in example (console output)
- `exampleGui [name] [endTime]` - Run built-in example with animated GUI
- `aiSweep --grid <grid.json> [--out <dir>] [--dry-run]` - Run the dispatcher reliability sweep.
  Manual only, never in CI; the LLM arm needs a live Ollama. No grid files are committed —
  write the grid for the campaign you run. See CLAUDE.md for an example grid.

**Note:** For memory-constrained environments, add `-Xmx300m`.

---

## Project Structure

The project is a multi-module Gradle build. All production code is Kotlin.

```
interlockSim/
├── build.gradle.kts       # Root build script (thin aggregator)
├── settings.gradle.kts    # Subprojects: :core, :core-test, :dispatcher-agent,
│                          #              :desktop-ui, and :fast-sim on Linux hosts
├── gradle.properties      # Dependency versions
├── gradle/                # Gradle wrapper files
│
├── core/                  # KMP :core - domain model, simulation engine, XML
│   └── src/
│       ├── commonMain/kotlin/cz/vutbr/fit/interlockSim/
│       │   ├── context/   # Simulation and editing contexts
│       │   ├── objects/   # Domain model (tracks, cells, paths)
│       │   ├── sim/       # Simulation scenarios and engine
│       │   ├── pathfinding/, ports/, domain/, di/, util/, xml/
│       ├── commonMain/resources/cz/vutbr/fit/interlockSim/resource/
│       │   ├── data.xsd       # XML schema
│       │   └── vyhybna.xml    # Example configuration
│       ├── commonTest/, jvmTest/   # Tests
│       ├── jvmMain/       # JVM actuals (XML parsing, factories)
│       │   └── resources/logback.xml   # Logging configuration
│       └── nativeMain/, nativeInterop/ # linuxX64 actuals, libxml2 interop
│
├── core-test/             # KMP :core-test - shared test fixtures (TestFixtures)
├── dispatcher-agent/      # JVM :dispatcher-agent - Goal 10 dispatcher
│   └── src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/
│       ├── agents/, executor/, planner/, observation/, sweep/, di/
│
├── desktop-ui/            # JVM :desktop-ui - GUI and application entry point
│   └── src/
│       ├── main/kotlin/cz/vutbr/fit/interlockSim/
│       │   ├── Main.kt            # Application entry point
│       │   ├── AppMetadata.kt     # Name, version, window titles
│       │   ├── ExampleRegistry.kt # Built-in examples
│       │   └── gui/, di/
│       ├── test/kotlin/   # Desktop tests
│       ├── jmh/kotlin/    # JMH benchmarks
│       └── build/libs/interlockSim.jar   # Packaged application
│
├── fast-sim/              # Native :fast-sim - linuxX64 CLI binary
├── docs/                  # Architecture docs
├── text/                  # LaTeX thesis source
└── .github/workflows/     # CI/CD workflows
```

---

## XML Configuration

Railway networks are defined using XML with the following elements:

- `<RailSwitch>` - Track switches (points)
- `<RailSemaphore>` - Signals
- `<InOut>` - Entry and exit points
- Track connections with spatial coordinates

**Example configuration:** `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
**XML Schema:** `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`

---

## Architecture

### Simulation Engine

Built on **kDisco** (Java framework for combined discrete and continuous simulation):
- Process-oriented simulation paradigm
- Discrete event scheduling
- Continuous variable support
- Time-determined and state-determined events

### Core Components

**Context System** - Composition-based architecture (Issue #153, 2026-01-20):
- **BaseContext** - Abstract base class with shared infrastructure (grid, graph, property change notification)
- **DefaultEditingContext : BaseContext** - Editing operations (putCell, removeCell, moveCell, joinCells)
- **DefaultSimulationContext : BaseContext** - Simulation operations (run, stop, pathToNextSemaphore)
- **ContextTransformer** - Factory for transforming EditingContext to SimulationContext
- Immutability enforcement via freeze() mechanism (simulation contexts are immutable after initialization)
- Factory pattern for context creation (EditingContextFactory, SimulationContextFactory, XMLContextFactory)
- Grid parameterization for type-safe cell access (RailwayNetGrid<T : Cell>)

**Context Separation:**
- EditingContext and SimulationContext are separate interfaces (no inheritance between them)
- Both implementations extend BaseContext independently (composition over inheritance)
- Follows Interface Segregation Principle - simulation contexts do NOT support editing operations
- Network structure is frozen at simulation initialization time for correctness

**Object Model** - Track facilities, blocks, cells, and paths:
- Static objects (NodeCell: RailSwitch, RailSemaphore, InOut)
- Dynamic wrappers (DynamicPathSeparator, DynamicTrack) for simulation state
- Grid-based spatial representation with pathfinding

**GUI** - Swing-based editor with grid canvas

**XML Factory** - Schema-validated configuration loading

---

## Docker Setup (Detailed)

The project includes Docker support for both the Java application and LaTeX thesis compilation, eliminating the need to install Java 21, Gradle, or LaTeX tools on the host machine.

### Docker Services

- **app** - Java application with GUI support (X11 forwarding)
- **text** - LaTeX thesis compilation

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

**Root Dockerfile (multi-stage build):**
1. **Builder stage** - Uses Eclipse Temurin 21 JDK
   - Builds kDisco dependency (Maven install, Java 6 compatibility)
   - Resolves dependencies via Gradle (automatic download)
   - Compiles Java sources (Java 21 target)
   - Runs all tests (build fails if tests fail)
   - Creates uber JAR with all dependencies
2. **Runner stage** - Eclipse Temurin 21 JRE with X11 libraries
   - Minimal runtime environment
   - X11 forwarding for GUI support
   - No build tools in final image

**text/Dockerfile:**
- Based on Debian Bookworm
- Full TeX Live installation with Czech language support
- Image conversion tools (wmf2eps, autotrace, gnuplot)
- Compiles thesis PDF from LaTeX sources

### Artifacts

Both services copy build outputs to `/artifacts` inside the container, which is mounted to `./artifacts/` on the host:
- `artifacts/app/interlockSim.jar` - Compiled application
- `artifacts/text/bakalarka.pdf` - Compiled thesis

---

## Logging

The application uses SLF4J with Logback for comprehensive logging of simulation events and operations.

### Log Configuration

**Main application:** `core/src/jvmMain/resources/logback.xml`
**Sweep runs:** `desktop-ui/src/main/resources/logback-sweep.xml`

### Log Levels

Available log levels (from most to least verbose):
- `TRACE` - Very detailed diagnostic information
- `DEBUG` - Detailed information for debugging
- `INFO` - General informational messages (default)
- `WARN` - Warning messages for potential issues
- `ERROR` - Error messages for failures

### Changing Log Levels

**Method 1: Edit logback.xml (recommended for development)**

Edit `core/src/jvmMain/resources/logback.xml`:

```xml
<!-- Change root logger level (affects all loggers) -->
<root level="DEBUG">
    <appender-ref ref="CONSOLE"/>
</root>

<!-- Or change specific package/class level -->
<logger name="cz.vutbr.fit.interlockSim.sim.Train" level="TRACE"/>
<logger name="cz.vutbr.fit.interlockSim.sim.ShuntingLoop" level="DEBUG"/>
```

**Method 2: System property (runtime override)**

```bash
java -Dlogback.level=DEBUG -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 1024
```

**Method 3: Environment variable (Docker)**

```bash
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -jar interlockSim.jar example shuntingLoop 60
```

### Pre-configured Loggers

The following loggers are pre-configured in `logback.xml`:

- `cz.vutbr.fit.interlockSim.simulation` - Simulation events (INFO)
- `kDisco.statistics` - kDisco statistical reports (INFO)
- `cz.vutbr.fit.interlockSim.sim.Train` - Train behavior (DEBUG)
- `cz.vutbr.fit.interlockSim.sim.ShuntingLoop` - Shunting operations (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.paths.AbstractPath` - Path management (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack` - Track operations (DEBUG)

### Log Output

**Console:** Real-time output with format: `HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`

**File:** `logs/interlockSim.log` with timestamp format: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`

---

## Testing

Comprehensive JUnit 5.11.4 test suite with assertk 0.28.1 assertions. Tests live in each
subproject: `core/src/{commonTest,jvmTest}/kotlin/`, `desktop-ui/src/test/kotlin/`,
`dispatcher-agent/src/test/kotlin/` and `fast-sim/src/linuxX64Test/kotlin/`.

**Test coverage statistics, last counted February 2026:**
- **1840 tests total** (1836 passing, 4 skipped, 0 failing)
- **51% code coverage** (8,824/17,070 instructions covered)
- **145 test classes** across 6 expansion phases

That count predates the `:core-test`, `:dispatcher-agent` and `:fast-sim` subprojects, so it is
a floor, not the current figure. For the current numbers run `./gradlew test jacocoTestReport`,
or read the CI coverage artifact (see "Test Coverage" below).

**Coverage by package:**
- objects.tracks/ - 85% (excellent), xml/ - 85% (excellent)
- util/ - 75% (good), objects.cells/ - 72% (good), context/ - 70% (good)
- objects.paths/ - 52% (medium), sim/ - 33% (limited by kDisco framework)

**Test expansion phases completed (2026-01-10):**
1. Safety-critical components (Train physics, Track state, RailSwitch, RailSemaphore)
2. Simulation engine core (Train state transitions, path interaction, InOutWorker)
3. Path and track integration (AbstractPath, path/track coordination)
4. Main entry points and cell edge cases (CLI parsing, example loading, NodeCell)
5. Generator and advanced simulations (Generator, shunting operations, timetables)
6. Exception handling and edge cases (SimulationException, validation, deadlock detection)

Run tests:
```bash
# Run unit tests only
./gradlew test

# Run integration tests
./gradlew integrationTest

# Run all tests
./gradlew test integrationTest

# Run JMH performance benchmarks
./gradlew jmh

# Or as part of build
./gradlew clean build
```

Tests are automatically executed during the build process. The build will fail if any test fails.

**Performance Benchmarks**: JMH benchmarks are available in `desktop-ui/src/jmh/` for accurate performance measurements. See `desktop-ui/src/jmh/kotlin/README.md` for details.

---

## Documentation

### Project Documentation

- **`CLAUDE.md`** - Comprehensive development guide (build, test, architecture)
- **`docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md`** - Complete static/dynamic separation architecture with diagrams
- **`docs/CONTEXT_REFACTORING_DESIGN.md`** - Context refactoring and factory pattern design
- **`docs/FACTORY_PATTERN_IMPLEMENTATION.md`** - Factory pattern implementation summary
- **`docs/KOTLIN_STYLE_GUIDE.md`** - Kotlin coding standards and detekt configuration
- **`TEAM.md`** - Multi-agent development workflows and roles

### Thesis Documentation (LaTeX)

Build the thesis PDF:

```bash
cd text
make
```

**Requirements:** LaTeX, gnuplot, make, wmf2eps, sed

### API Documentation (JavaDoc)

Generate JavaDoc:

```bash
./gradlew javadoc
```

Output: `desktop-ui/build/docs/javadoc/` directory

---

## Future Development

The simulation engine is **kDisco**, a Kotlin discrete-event library maintained in its own
repository at https://github.com/bedaHovorka/kdisco. It replaced the 2004 jDisco library on
2026-03-20.

**There is no planned migration to another simulation framework.** An earlier road map had a
second phase that would swap kDisco for Kalasim; that plan is dropped (owner decision,
2026-08-24). `docs/jdisco-research.md` and `docs/SIMULATION_LIBRARY_DECISION_ROUND2.md` record
the framework research and the decision as it stood then. Read them as history.

---

## License & Attribution

**InterlockSim**
© 2006-2007 Bedrich Hovorka
BSc Thesis, Brno University of Technology

**kDisco Library**
© 2001-2004 Keld Helsgaun, Roskilde University, Denmark
Research use only

---

## Repository Contents

This repository includes:

- Complete Kotlin source code (multi-module; kDisco is an external dependency)
- Gradle build system with Kotlin DSL
- Comprehensive JUnit 5 test suite
- XML schemas and example configurations
- Docker support for containerized builds
- GitHub Actions CI/CD workflows
- LaTeX thesis source and images
- Documentation (JavaDoc, README, CLAUDE.md)

---

## CI/CD and Quality Assurance

### Build Status

The project uses GitHub Actions for continuous integration and quality assurance:

- **Gradle Build (Java 21)** - Compiles, tests, and creates JAR artifacts
- **SonarQube Analysis** - Code quality analysis and coverage reporting

View workflow status: [GitHub Actions](https://github.com/bedaHovorka/interlockSim/actions)

### Test Coverage

The authoritative figure is the CI coverage artifact, not this file. See "Testing" above for
the last recorded count and how to measure the current one.

**Coverage Reports:**
- JaCoCo reports generated with every build
- Available in CI artifacts: `jacoco-coverage-report-{sha}`
- View locally: `./gradlew jacocoTestReport` → `build/reports/jacoco/test/html/index.html`

### Contributing

For information on:
- Running CI checks locally
- Verifying test coverage improvements
- Creating PRs with proper documentation
- Understanding CI/CD workflows

See:
- **[Contributing Guide](.github/CONTRIBUTING.md)** - Complete contribution guidelines
- **[PR Template](.github/PULL_REQUEST_TEMPLATE.md)** - Pull request checklist
- **[CI/CD Quick Reference](.github/CI_CD_QUICK_REFERENCE.md)** - CI status badges and commands

---

## Contact & References

**Project:** Railway Interlocking Simulator (InterlockSim 0.2)

*Version note:* `0.2` is the application version, defined by `PROGRAM_VERSION` in
`desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/AppMetadata.kt`, and it is what the
window title shows. The `version=1.0` in `gradle.properties` is the artifact version of the
Gradle modules; the two are separate on purpose.

**Institution:** Brno University of Technology, Faculty of Information Technology
**Year:** 2006/2007

For development guidance, see `CLAUDE.md`.
