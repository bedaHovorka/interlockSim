# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**jDisco** - A Java library for discrete-event and continuous simulation.

Originally written by Keld Helsgaun (Roskilde University, Denmark) and released into the public domain (2001-2004). Extracted from the interlockSim project in 2025 as a standalone Maven library.

## Build System

This project uses Apache Maven for building. Java 6 source/target compatibility is maintained.

### Common Build Commands

**Clean build:**
```bash
mvn clean compile
```

**Run tests:**
```bash
mvn test
```

**Package JAR:**
```bash
mvn package
```

**Install to local Maven repository:**
```bash
mvn install
```

**Generate JavaDoc:**
```bash
mvn javadoc:javadoc
# Output: target/site/apidocs/
```

**Full build with tests:**
```bash
mvn clean install
```

## Docker Setup (Recommended)

**Dockerization: 2025** - Complete containerized build environment with no host dependencies.

The project includes Docker support for building with Java 6 compatibility. This eliminates the need to install JDK 6 or Maven on the host machine.

### Prerequisites

- Docker and Docker Compose installed on the host

### Common Docker Commands

**Build library:**
```bash
docker-compose build
```

**Run tests:**
```bash
docker-compose run jdisco mvn test
```

**Install to artifacts directory:**
```bash
docker-compose run jdisco mvn install
# JAR files will be available in artifacts/
```

**Clean build:**
```bash
docker-compose run jdisco mvn clean install
```

### Artifacts

The build process copies artifacts to `/artifacts` inside the container, which is mounted to `./artifacts/` on the host:
- `artifacts/jdisco-1.2.0.jar` - Compiled library
- `artifacts/jdisco-1.2.0-sources.jar` - Source JAR
- `artifacts/jdisco-1.2.0-javadoc.jar` - JavaDoc JAR

## Architecture

### Core Components

jDisco provides a combined discrete-event/continuous simulation framework with these key abstractions:

**Process** (`Process.java`) - Discrete event simulation
- Abstract class for modeling discrete processes
- Coroutine-based implementation for process switching
- Methods: `activate()`, `hold()`, `passivate()`, `wait()`, `waitUntil()`
- Static method `time()` returns current simulation time

**Continuous** (`Continuous.java`) - Continuous process modeling
- Abstract class for continuous state changes
- Integration with numerical methods
- Method: `derivatives()` - defines differential/difference equations
- Methods: `start()`, `stop()` to control continuous processes

**Variable** (`Variable.java`) - State variables
- Represents variables that change continuously
- Properties: `state` (current value), `rate` (derivative)
- Used in continuous processes

**Monitor** (`Monitor.java`) - Simulation controller
- Controls simulation execution behind the scenes
- Manages continuous state updates via numerical integration
- Ensures events occur at correct time and order
- Handles state-events (via `waitUntil`) and time-events

**Random** (`Random.java`) - Random number generation
- Extends `java.util.Random`
- Provides distribution methods: uniform, exponential, normal, etc.
- Used for stochastic simulation

**Queue Management**:
- `Head` - Circular two-way list head
- `Link` - List items
- `Linkage` - Base class for linked structures

**Integration Methods** (13 classes):
- Explicit methods: Euler, RKF45, RKE, RKDP45, RKN34, RKV56
- Implicit methods: Implicit, Adams, AdamsBashforth, FowlerWarten
- Special: Trapez, Simpson
- PDEVariable - Partial differential equations

**Reporting & Statistics**:
- `Reporter` - Observation pattern for gathering simulation data
- `Condition` - Interface for state-event detection
- `Accumulate`, `Tally`, `Histogram` - Statistical collection
- `Format` - Output formatting utilities

### Package Structure

```
jDisco/
├── Process.java          - Discrete event simulation core (32KB)
├── Continuous.java       - Continuous process modeling
├── Monitor.java          - Simulation controller
├── Variable.java         - State variables
├── Condition.java        - State-event interface
├── Random.java           - Random number generation
├── Reporter.java         - Observation/reporting
├── Head.java, Link.java  - Queue/list management
├── Coroutine.java        - Coroutine implementation
├── DiscoException.java   - Exception handling
├── Euler.java, RKF45.java, ... - Integration methods (13 files)
└── Format.java, Table.java, ... - Utilities
```

## Code Style

Follows `.editorconfig` configuration:
- Java files: tabs (width 4), max line length 120
- Source encoding: ISO-8859-1 (some files contain Latin-1 characters)
- UTF-8 for documentation files
- LF line endings

## Important: Conservative Approach to Modifications

**CRITICAL:** This is a legacy library from 2004. Be extremely conservative when modifying code.

**Rules:**
1. **Do not touch Java code unless explicitly requested** - This is working, stable code that has been in use for 20+ years
2. **Preserve Java 6 compatibility** - Do not introduce Java 7+ features (no try-with-resources, no diamond operator, no lambdas, etc.)
3. **Maintain encoding** - Source files are ISO-8859-1 encoded
4. **No modernization** - Do not refactor working code to "improve" it
5. **Tests required** - Any modifications must be covered by tests

**Allowed modifications (user specified):**
- Fixing actual bugs (only if absolutely necessary)
- Fixing very critical security issues or sonar/findbugs violations
- Any change must be covered by tests to prove the fix
- Adding tests to verify existing behavior
- Documentation improvements
- Build system updates (Maven plugins, Docker)
- Add dependency to slf4j for logging and replace System.out.println etc. calls to use a logger instead
- Add dependency to javax.inject for Singleton annotations (only if absolutely necessary)
- Add other dependencies than mentioned above is not allowed (only test scoped dependencies are allowed)
- Adding new features only if absolutely necessary (with tests)


## Testing

JUnit 4.13.2 is used for testing.

**Run tests:**
```bash
mvn test
# Or with Docker:
docker-compose run jdisco mvn test
```

Tests are located in `src/test/java/jDisco/`. Current test coverage includes:
- Process lifecycle and event scheduling
- Continuous integration
- Random number distributions
- Queue operations
- Variable state management

## Maven Coordinates

When depending on this library:

```xml
<dependency>
    <groupId>dk.ruc.keld</groupId>
    <artifactId>jdisco</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Future Development Considerations

This library is in **maintenance mode**. The original author (Keld Helsgaun) released it into the public domain in 2001-2004, and the website (http://webhotel4.ruc.dk/~keld/research/JDISCO/) is no longer accessible.

**Modern alternatives** exist for new projects:
- **DSOL** (Distributed Simulation Object Library) - Java 17+, actively maintained
- **Kalasim** - Native Kotlin with coroutines
- **SSJ** (Stochastic Simulation in Java) - For stochastic simulation

However, jDisco remains valuable for:
- Projects requiring Java 6 compatibility
- Legacy code depending on jDisco API
- Educational purposes (clean, understandable implementation)
- Systems that cannot be easily migrated

## License

Public domain. Originally written by Keld Helsgaun and released for any purpose whatsoever without acknowledgment.
