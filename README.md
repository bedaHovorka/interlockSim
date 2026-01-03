# Railway Interlocking Simulator

A discrete-event/continuos combined simulation system for railway interlocking 
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

- **Java**: JDK 6 or compatible (javac 1.6)
- **Build Tool**: Apache Ant
- **Testing**: JUnit (included as `junit.jar`)

### Optional (for thesis documentation):
- LaTeX, gnuplot, make, wmf2eps, sed

---

## Building the Project

### Quick Start

```bash
# Clean and build
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
| `ant build` | Compile all sources |
| `ant clean` | Remove build artifacts |
| `ant start` | Run pre-configured shunting loop simulation |
| `ant run` | Launch graphical editor |
| `ant pack` | Create JAR file |
| `ant doc` | Generate JavaDoc documentation |

---

## Running the Simulator

### 1. Graphical Editor Mode

Open the track editor to design railway layouts:

```bash
ant run
```

Or manually:
```bash
java -ea cz.vutbr.fit.interlockSim.Main edit [xmlFile]
```

### 2. Simulation Mode

Run a simulation from an XML configuration file:

```bash
java -ea cz.vutbr.fit.interlockSim.Main sim [xmlFile]
```

### 3. Built-in Examples

Run pre-configured simulation scenarios:

```bash
# List all available examples
java -ea cz.vutbr.fit.interlockSim.Main example

# Run shunting loop example for 300 time units
java -ea cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

**Quick example:**
```bash
# Build and run shunting yard simulation (5 minutes model time)
ant clean build
java -ea -cp build cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
```

### Command-Line Synopsis

```
java -ea cz.vutbr.fit.interlockSim.Main (sim|edit|example) [arguments]
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
├── junit.jar              # JUnit testing library
├── src/                   # Java source files
│   ├── cz/vutbr/fit/interlockSim/
│   │   ├── Main.java      # Application entry point
│   │   ├── context/       # Simulation context management
│   │   ├── gui/           # Swing-based editor
│   │   ├── objects/       # Domain model (tracks, cells, paths)
│   │   ├── sim/           # Simulation scenarios
│   │   ├── test/          # JUnit tests
│   │   ├── xml/           # XML parsing/serialization
│   │   └── resource/      # XML schemas and examples
│   └── jDisco/            # Third-party simulation library
├── text/                  # LaTeX thesis source
├── doc/                   # Generated JavaDoc (ant doc)
└── build/                 # Compiled classes (ant build)
```

---

## XML Configuration

Railway networks are defined using XML with the following elements:

- `<RailSwitch>` - Track switches (points)
- `<RailSemaphore>` - Signals
- `<InOut>` - Entry and exit points
- Track connections with spatial coordinates

**Example configuration:** `src/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
**XML Schema:** `src/cz/vutbr/fit/interlockSim/resource/data.xsd`

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

## Testing

JUnit tests are located in `src/cz/vutbr/fit/interlockSim/test/`.

Run tests during build:
```bash
ant clean build
```

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
