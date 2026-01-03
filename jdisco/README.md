# jDisco - Discrete-Event and Continuous Simulation Library

A Java library for combined discrete-event and continuous simulation, originally written by Keld Helsgaun (Roskilde University, Denmark) and released into the public domain.

## Overview

jDisco provides a framework for building simulation models that combine:

- **Discrete-event simulation** - Process-oriented modeling with coroutines
- **Continuous simulation** - Numerical integration of differential equations
- **State-event detection** - Automatic detection of condition changes
- **Statistical collection** - Built-in reporting and data collection

## Features

- Process-oriented discrete-event simulation with coroutines
- Multiple numerical integration methods (Euler, RKF45, Adams, etc.)
- State-event detection via `waitUntil` mechanism
- Built-in random number generation with multiple distributions
- Queue/list management for process scheduling
- Statistical collection (histograms, tallies, accumulators)
- Zero external dependencies (pure Java SE)

## Requirements

- Java 6 or higher
- Maven 3.x (for building)

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>dk.ruc.keld</groupId>
    <artifactId>jdisco</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Building from Source

**Native build:**
```bash
mvn clean install
```

**Docker build:**
```bash
docker compose build
docker compose run jdisco mvn install
```

## Example: Simple Discrete-Event Simulation

```java
import jDisco.*;

public class SimpleSimulation {
    public static void main(String[] args) {
        // Create a process
        new Process() {
            protected void actions() {
                System.out.println("Process started at time " + time());
                hold(10.0);  // Hold for 10 time units
                System.out.println("Process resumed at time " + time());
            }
        }.activate();  // Activate the main process
    }
}
```

## Example: Continuous Simulation

```java
import jDisco.*;

public class ContinuousExample {
    static Variable x = new Variable(1.0);  // Initial value

    public static void main(String[] args) {
        // Define continuous dynamics
        new Continuous() {
            protected void derivatives() {
                x.rate = -x.state * 0.1;  // dx/dt = -0.1*x
            }
        }.start();

        // Create process to observe
        new Process() {
            protected void actions() {
                for (int i = 0; i < 10; i++) {
                    System.out.println("Time: " + time() + ", x: " + x.state);
                    hold(1.0);
                }
            }
        }.activate();
    }
}
```

## Core Classes

- **Process** - Abstract class for discrete processes
  - `activate()` - Start process execution
  - `hold(t)` - Suspend for time t
  - `passivate()` - Suspend indefinitely
  - `waitUntil(condition)` - Wait for state condition

- **Continuous** - Abstract class for continuous processes
  - `derivatives()` - Define differential equations
  - `start()` - Activate continuous process
  - `stop()` - Deactivate continuous process

- **Variable** - State variable
  - `state` - Current value
  - `rate` - Derivative (dx/dt)

- **Random** - Random number generation
  - `uniform(a, b)` - Uniform distribution
  - `exponential(mean)` - Exponential distribution
  - `normal(mean, std)` - Normal distribution

- **Head, Link** - Queue/list management

## Documentation

- **API Documentation**: Build with `mvn javadoc:javadoc`, output in `target/site/apidocs/`
- **Original Website**: http://webhotel4.ruc.dk/~keld/research/JDISCO/ (archived)
- **Project Instructions**: See `CLAUDE.md` for development guidelines

## History

- **2001**: Initial release by Keld Helsgaun
- **2004**: Last update (v1.2)
- **2025**: Extracted as standalone Maven library from interlockSim project

## License

Public domain. Originally written by Keld Helsgaun and released into the public domain. This may be used for any purposes whatsoever without acknowledgment.

## Author

**Keld Helsgaun**
Roskilde University, Denmark
Email: keld@ruc.dk

## Modern Alternatives

For new projects, consider these actively maintained alternatives:

- **DSOL** (Distributed Simulation Object Library) - Java 17+, multi-formalism
- **Kalasim** - Native Kotlin with coroutines
- **SSJ** (Stochastic Simulation in Java) - For stochastic simulation

jDisco remains valuable for:
- Java 6 compatibility requirements
- Legacy code maintenance
- Educational purposes
- Systems that cannot be easily migrated
