# Railway Interlocking Simulator

A discrete-event/continuous combined simulation system for railway interlocking 
with a graphical track editor and XML-based configuration.

**BSc Thesis Project (2006/2007)**
Brno University of Technology
Faculty of Information Technology
Author: Bedrich Hovorka

---

## Overview

**InterlockSim** is a Java-based railway interlocking simulator that combines:

- **Graphical Editor** - Design railway track layouts with switches, semaphores, and entry/exit points
- **Discrete Event Simulation** - Simulate train movements and interlocking logic using the jDisco framework
- **Continuous Simulation** - Model continuous of train positions and speeds
- **XML Configuration** - Define and save railway networks in a structured XML format
- **Process-Oriented Modeling** - Built-in examples demonstrate shunting yard operations

The simulator uses a combined discrete-continuous simulation approach powered by the jDisco library (Keld Helsgaun, Roskilde University).

---

## Features

- **Interactive track editor** with grid-based layout
- **XML schema-validated** railway network definitions
- **Discrete event simulation engine** (jDisco-based)
- **Built-in examples** including shunting loop scenarios
- **Swing GUI** for visualization and editing
- **Assertion-based validation** for simulation integrity

---

## System Requirements

### Option 1: Docker (Recommended)

- **Docker** and **Docker Compose**
- **X11 Server** (for GUI display)
  - Linux: Usually already running
  - macOS: Install XQuartz
  - Windows: Install VcXsrv or Xming

### Option 2: Native Build

- **Java**: JDK 6 or compatible (javac 1.6)
- **Build Tool**: Apache Ant with Apache Ivy
- **Dependencies**: Automatically managed via Ivy
  - jDisco 1.2.0 (from Maven local repository)
  - JUnit 3.8.2 (from Maven Central)

### Optional (for thesis documentation):
- LaTeX, gnuplot, make, wmf2eps, sed

---

## Quick Start with Docker

**Dockerization: 2025** - Complete containerized build and runtime environment with no host dependencies.

### Build and Run

```bash
# Build Docker images
docker compose build

# Run graphical editor (X11 forwarding)
docker compose up app

# Run simulation example
docker compose run app java -ea -jar interlockSim.jar example shuntingLoop 60

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

## Building the Project

### Quick Start

```bash
# Resolve dependencies (automatic via Ivy)
ant resolve

# Clean and build (compiles main + tests, runs tests)
ant clean build

# Run simulation (shunting loop example)
ant start

# Run graphical editor
ant run

# Generate JavaDoc
ant doc
```

### Build Targets

| Target | Description |
|--------|-------------|
| `ant resolve` | Download dependencies via Apache Ivy |
| `ant build` | Compile all sources, run tests (build fails if tests fail) |
| `ant test` | Run JUnit tests only |
| `ant clean` | Remove build artifacts |
| `ant clean-all` | Clean everything including Ivy cache |
| `ant start` | Run pre-configured shunting loop simulation |
| `ant run` | Launch graphical editor |
| `ant pack` | Create uber JAR file with all dependencies |
| `ant doc` | Generate JavaDoc documentation |

**Note:** Dependencies are automatically resolved during build. The `ant resolve` target is only needed if you want to download dependencies separately.

---

## Running the Simulator

### 1. Graphical Editor Mode

Open the track editor to design railway layouts:

```bash
ant run
```

Or manually (after building):
```bash
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main edit [xmlFile]
```

![InterlockSim Editor](text/img/Screenshot%20at%202026-01-03%2009-09-58.png)

*The graphical track editor showing a simple shunting loop layout with entry/exit points and rail switches.*

### 2. Simulation Mode

Run a simulation from an XML configuration file:

```bash
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main sim [xmlFile]
```

### 3. Built-in Examples

Run pre-configured simulation scenarios:

```bash
# List all available examples
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example

# Run shunting loop example for 300 time units
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

**Quick example:**
```bash
# Build and run shunting yard simulation (5 minutes model time)
ant clean build
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

### Command-Line Synopsis

```
java -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main (sim|edit|example) [arguments]
```

**Modes:**
- `sim [file.xml]` - Run simulation from XML file
- `edit [file.xml]` - Open editor (optionally load file)
- `example [name] [endTime]` - Run built-in example

**Note:** Always use `-ea` to enable assertions. For memory-constrained environments, add `-Xmx300`.

---

## Project Structure

```
interlockSim/
├── build.xml              # Ant build configuration
├── ivy.xml                # Ivy dependency declarations
├── ivysettings.xml        # Ivy resolver configuration
├── src/
│   ├── main/
│   │   ├── java/cz/vutbr/fit/interlockSim/
│   │   │   ├── Main.java      # Application entry point
│   │   │   ├── context/       # Simulation context management
│   │   │   ├── gui/           # Swing-based editor
│   │   │   ├── objects/       # Domain model (tracks, cells, paths)
│   │   │   ├── sim/           # Simulation scenarios
│   │   │   ├── xml/           # XML parsing/serialization
│   │   │   └── util/          # Utilities
│   │   └── resources/cz/vutbr/fit/interlockSim/resource/
│   │       ├── data.xsd       # XML schema
│   │       └── vyhybna.xml    # Example configuration
│   └── test/
│       └── java/cz/vutbr/fit/interlockSim/test/
│           ├── TestArray2DMap.java
│           ├── TestCell.java
│           └── TestContext.java
├── jdisco/                # jDisco library (separate Maven module)
├── lib/                   # Downloaded dependencies (Ivy)
│   ├── compile/           # Compile dependencies (jDisco)
│   └── test/              # Test dependencies (JUnit)
├── build/                 # Compiled classes
│   ├── main/              # Main code
│   └── test/              # Test code
├── text/                  # LaTeX thesis source
├── doc/                   # Generated JavaDoc (ant doc)
└── jar/                   # Packaged JAR (ant pack)
```

---

## XML Configuration

Railway networks are defined using XML with the following elements:

- `<RailSwitch>` - Track switches (points)
- `<RailSemaphore>` - Signals
- `<InOut>` - Entry and exit points
- Track connections with spatial coordinates

**Example configuration:** `src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
**XML Schema:** `src/main/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`

---

## Architecture

### Simulation Engine

Built on **jDisco** (Java framework for combined discrete and continuous simulation):
- Process-oriented simulation paradigm
- Discrete event scheduling
- Continuous variable support
- Time-determined and state-determined events

### Core Components

- **Context System** - Factory pattern for creating simulation/editing contexts
- **Object Model** - Track facilities, blocks, cells, and paths
- **GUI** - Swing-based editor with grid canvas
- **XML Factory** - Schema-validated configuration loading

---

## Docker Setup (Detailed)

The project includes Docker support for both the Java application and LaTeX thesis compilation, eliminating the need to install Java 6, Ant, or LaTeX tools on the host machine.

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
   - Builds jDisco dependency (Maven install)
   - Resolves dependencies via Apache Ivy (automatic download)
   - Compiles Java sources (Java 6 compatibility mode)
   - Runs all tests (build fails if tests fail)
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

### Artifacts

Both services copy build outputs to `/artifacts` inside the container, which is mounted to `./artifacts/` on the host:
- `artifacts/app/interlockSim.jar` - Compiled application
- `artifacts/text/bakalarka.pdf` - Compiled thesis

---

## Logging

The application uses SLF4J with Logback for comprehensive logging of simulation events and operations.

### Log Configuration

**Main application:** `src/main/resources/logback.xml`
**jDisco tests:** `jdisco/src/test/resources/simplelogger.properties`

### Log Levels

Available log levels (from most to least verbose):
- `TRACE` - Very detailed diagnostic information
- `DEBUG` - Detailed information for debugging
- `INFO` - General informational messages (default)
- `WARN` - Warning messages for potential issues
- `ERROR` - Error messages for failures

### Changing Log Levels

**Method 1: Edit logback.xml (recommended for development)**

Edit `src/main/resources/logback.xml`:

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
java -Dlogback.level=DEBUG -ea -cp "build/main:lib/compile/*" cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

**Method 3: Environment variable (Docker)**

```bash
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -ea -jar interlockSim.jar example shuntingLoop 60
```

### Pre-configured Loggers

The following loggers are pre-configured in `logback.xml`:

- `cz.vutbr.fit.interlockSim.simulation` - Simulation events (INFO)
- `jDisco.statistics` - jDisco statistical reports (INFO)
- `cz.vutbr.fit.interlockSim.sim.Train` - Train behavior (DEBUG)
- `cz.vutbr.fit.interlockSim.sim.ShuntingLoop` - Shunting operations (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.paths.AbstractPath` - Path management (DEBUG)
- `cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack` - Track operations (DEBUG)

### Log Output

**Console:** Real-time output with format: `HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`

**File:** `logs/interlockSim.log` with timestamp format: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL Logger.method(File:Line) - message`

---

## Testing

JUnit 5.10.1 tests with AssertJ assertions are located in `src/test/java/cz/vutbr/fit/interlockSim/`.

**Test coverage:**
- `Array2DMapTest` - 10 tests for 2D array-based map implementation
- `CellTest` - 2 tests for cell segment and direction logic
- `ContextTest` - 4 tests for railway network context operations

Run tests:
```bash
# Run tests only
ant test

# Or as part of build
ant clean build
```

Tests are automatically executed during the build process. The build will fail if any test fails.

---

## Documentation

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
ant doc
```

Output: `doc/` directory

---

## Future Development

The project currently uses **jDisco** (2004, no longer maintained). Research has identified modern alternatives for potential migration:

- **DSOL** - Combined discrete-continuous simulation (Java 17+, actively maintained)
- **Kalasim** - Discrete event simulation (Kotlin-native with coroutines)
- **SSJ** - Stochastic simulation (Université de Montréal)

See `jdisco-research.md` for comprehensive analysis.

---

## License & Attribution

**InterlockSim**
© 2006-2007 Bedrich Hovorka
BSc Thesis, Brno University of Technology

**jDisco Library**
© 2001-2004 Keld Helsgaun, Roskilde University, Denmark
Research use only

---

## Repository Contents

This repository includes:

- Complete Java source code (interlockSim + jDisco library)
- Ant build system
- JUnit tests
- XML schemas and example configurations
- LaTeX thesis source and images
- Generated documentation (JavaDoc)

---

## Contact & References

**Project:** Railway Interlocking Simulator (InterlockSim v0.1-bachelor)
**Institution:** Brno University of Technology, Faculty of Information Technology
**Year:** 2006/2007

For development guidance, see `CLAUDE.md`.
