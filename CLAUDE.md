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