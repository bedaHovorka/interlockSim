# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of Technology that simulates railway interlocking systems with a graphical editor and discrete event simulation engine.

## Build System

This project uses Apache Ant for building. Java 6 is required (`javac 1.6`), with JUnit for testing.

### Common Build Commands

**Clean build:**
```bash
ant clean build
```

**Build only:**
```bash
ant build
```

**Run simulation (pre-configured shunting loop example):**
```bash
ant start
```

**Run editor GUI:**
```bash
ant run
```

**Generate JavaDoc documentation:**
```bash
ant doc
```

### Running Manually

After building with `ant build`, run from the `build/` directory:

**Simulation mode:**
```bash
java -ea cz.vutbr.fit.interlockSim.Main sim [xmlFile]
```

**Editor mode:**
```bash
java -ea cz.vutbr.fit.interlockSim.Main edit [xmlFile]
```

**Built-in examples:**
```bash
java -ea cz.vutbr.fit.interlockSim.Main example [exampleName] [endTime]
```

To list available examples, run:
```bash
java -ea cz.vutbr.fit.interlockSim.Main example
```

**Note:** Enable assertions with `-ea` flag. For memory-constrained environments, add `-Xmx300`.

## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build and runtime environment with no host dependencies.

The project includes Docker support for both the Java application and LaTeX thesis compilation. This eliminates the need to install Java 6, Ant, or LaTeX tools on the host machine.

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
docker-compose build
```

**Run editor GUI:**
```bash
# Method 1 (Recommended): Use .Xauthority file (more secure)
docker-compose up app

# Method 2: If you get authorization errors, allow X11 connections from Docker
xhost +local:docker
docker-compose up app

# When done with Method 2, revoke access for security:
xhost -local:docker
```

**Run simulation example:**
```bash
docker-compose run app java -ea -jar interlockSim.jar example shuntingLoop 60
```

**Run simulation with custom XML:**
```bash
docker-compose run -v $(pwd)/myfile.xml:/app/myfile.xml app java -ea -jar interlockSim.jar sim myfile.xml
```

**Build thesis PDF:**
```bash
docker-compose up text
# PDF will be available in artifacts/text/bakalarka.pdf
```

**Extract compiled JAR:**
```bash
docker-compose build app
# JAR will be available in artifacts/app/interlockSim.jar
```

### Docker Architecture

**Root Dockerfile (multi-stage build):**
1. **Builder stage** - Uses `caninjas/jdk6` with Ant
   - Compiles Java sources
   - Runs tests
   - Creates JAR with all dependencies
2. **Runner stage** - JRE 6 with X11 libraries
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
   docker-compose up app
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
- Located in `src/jDisco/` - bundled third-party library
- `sim/` package contains simulation processes (e.g., `ShuntingLoop`)

**GUI:**
- Swing-based editor in `gui/` package
- `gui/gridcanvas/` - Grid-based canvas for track layout editing
- `gui/action/` - Editor actions

**XML Configuration:**
- Railway networks defined in XML format
- Schema: `src/cz/vutbr/fit/interlockSim/resource/data.xsd`
- Example: `src/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
- Elements include: RailSwitch, RailSemaphore, InOut (entry/exit points), track connections

### Package Structure

```
cz.vutbr.fit.interlockSim/
├── Main.java              - Application entry point
├── context/               - Context management and factories
├── gui/                   - Graphical editor
├── objects/               - Domain model (tracks, paths, cells)
├── sim/                   - Simulation scenarios
├── test/                  - Unit tests (JUnit)
├── util/                  - Utilities and reporting
├── xml/                   - XML parsing and serialization
└── resource/              - XML schemas and configuration files

jDisco/                    - Third-party discrete event simulation library
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
4. **No unsolicited modernization** - Do not update Java 6 idioms to modern Java, do not add new language features, do not restructure working code

This is a working historical codebase from 2007. Stability and preservation are more important than modernization.

## Testing

JUnit tests located in `cz.vutbr.fit.interlockSim.test/`. The `junit.jar` library is included in the repository root.

**Run tests:**
```bash
ant build
# Tests run during build
```

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