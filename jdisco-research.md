# JDisco Library Research Report

## Executive Summary

**JDisco** is a Java framework for **combined discrete and continuous simulation**, created by Keld Helsgaun at Roskilde University, Denmark. The library is **no longer actively maintained** — the last version (1.1) was released in **March 2004**. For modern Kotlin development, several actively maintained alternatives exist, with **Kalasim** being the most Kotlin-native option.

---

## JDisco Overview

### What is JDisco?

JDisco is a Java framework designed to facilitate description and simulation of systems involving both discrete and continuous phenomena. Key features include:

- **Discrete processes** (`Process` class): Have instantaneous active phases called events, causing discrete state changes
- **Continuous processes** (`Continuous` class): Undergo active phases during time intervals, causing continuous state changes
- **Piecewise continuous state variables** (`Variable` class)
- Support for both time-determined events (`hold`, `activate`) and state-determined events (`waitUntil`)
- Processes can communicate, reference, and modify variables in other processes

### Current Status

| Attribute | Value |
|-----------|-------|
| **Latest Version** | 1.1 |
| **Release Date** | March 2004 |
| **Maintenance Status** | ❌ Abandoned (20+ years) |
| **License** | Research use only |
| **Java Version** | Legacy (pre-Java 5) |
| **Documentation** | PDF report available |
| **Download** | http://webhotel4.ruc.dk/~keld/research/JDISCO/ |

### Technical Architecture

JDisco is inspired by the Simula language DISCO and provides:
- Process-oriented simulation paradigm
- Support for concurrent process execution
- Event scheduling and management
- Combined discrete-event and continuous-time simulation

---

## Why JDisco is Outdated

1. **No updates in 20+ years** — Last release was 2004
2. **Legacy Java** — Pre-dates modern Java features (generics, streams, etc.)
3. **No Maven/Gradle support** — Manual JAR distribution only
4. **No community** — No GitHub, no issue tracking, no discussions
5. **Limited documentation** — Only a PDF research paper
6. **Research license** — Not open source

---

## Modern Alternatives

### 1. Kalasim (Recommended for Kotlin)

**The premier choice for Kotlin developers.**

| Attribute | Value |
|-----------|-------|
| **Language** | Kotlin (native) |
| **Type** | Discrete Event Simulation |
| **License** | MIT |
| **Status** | ✅ Actively Maintained |
| **Latest Version** | 1.x (2023+) |
| **Repository** | https://github.com/holgerbrandl/kalasim |
| **Documentation** | https://www.kalasim.org/ |

**Key Features:**
- 100% Kotlin with coroutine-based process definitions
- Type-safe API with dependency injection (Koin)
- Modern persistence and structured logging
- Built-in visualization (kravis, lets-plot)
- Real-time simulation support
- Comprehensive statistics and monitoring

**Installation (Gradle Kotlin DSL):**
```kotlin
dependencies {
    implementation("com.github.holgerbrandl:kalasim:1.x.x")
}
```

**Example:**
```kotlin
import org.kalasim.*
import kotlin.time.Duration.Companion.minutes

createSimulation {
    class Car : Component() {
        override fun process() = sequence {
            hold(5.minutes)
            log("Car finished!")
        }
    }
    
    Car()
    run(10.minutes)
}
```

---

### 2. DESMO-J

**Established academic framework for Java.**

| Attribute | Value |
|-----------|-------|
| **Language** | Java |
| **Type** | Discrete Event Simulation |
| **License** | Apache 2.0 |
| **Status** | ⚠️ Maintenance mode |
| **Latest Version** | 2.5.1e |
| **Website** | http://desmoj.sourceforge.net/ |

**Key Features:**
- Event-oriented and process-oriented modeling
- 2D/3D visualization support
- Queue and resource management
- Statistical analysis tools
- Academic and educational focus

**Kotlin Compatibility:** Works via Java interop, but verbose.

**Installation (Maven):**
```xml
<dependency>
    <groupId>de.desmo-j</groupId>
    <artifactId>desmoj</artifactId>
    <version>2.5.1e</version>
</dependency>
```

---

### 3. DSOL (Distributed Simulation Object Library) ⭐ RECOMMENDED FOR COMBINED SIMULATION

**The best actively maintained Java library for combined discrete-continuous simulation.**

| Attribute | Value |
|-----------|-------|
| **Language** | Java |
| **Type** | **Combined Discrete + Continuous + DEVS + Agent-Based** |
| **License** | BSD-3-Clause |
| **Status** | ✅ **Actively Maintained** |
| **Latest Version** | 4.3.2 (November 2025) |
| **Java Version** | Java 17+ |
| **Repository** | https://github.com/averbraeck/dsol |
| **Documentation** | https://dsol.readthedocs.io/ |
| **Institution** | TU Delft, Netherlands |

**Key Features:**
- **Multi-formalism**: Discrete-event, differential equations, agent-based, DEVS
- **Combined simulation**: Different formalisms can co-exist in a single model
- Distributed simulation support (publish-subscribe)
- Animation and visualization (GIS support, Swing, Web)
- Flow-based modeling
- Modular architecture with multiple sub-projects

**Sub-modules:**
- `dsol-core` - Core simulation engine
- `dsol-animation` - 2D visualization
- `dsol-animation-gis` - GIS integration
- `dsol-devs` - DEVS formalism support
- `dsol-flow` - Flow-based simulation
- `dsol-swing` - Swing GUI components
- `dsol-web` - Web-based visualization

**Installation (Maven):**
```xml
<dependency>
    <groupId>nl.tudelft.simulation</groupId>
    <artifactId>dsol-core</artifactId>
    <version>4.3.2</version>
</dependency>
```

**Installation (Gradle Kotlin DSL):**
```kotlin
dependencies {
    implementation("nl.tudelft.simulation:dsol-core:4.3.2")
}
```

**Real-world Applications:**
- OpenTrafficSim (traffic modeling)
- MedLabs (disease spread simulation)
- Supply chain simulations

---

### 4. SSJ (Stochastic Simulation in Java)

**Comprehensive stochastic simulation library.**

| Attribute | Value |
|-----------|-------|
| **Language** | Java |
| **Type** | Stochastic/Monte Carlo + Continuous Simulation |
| **License** | Apache 2.0 |
| **Status** | ✅ Actively Maintained |
| **Latest Version** | 3.3.2 |
| **Repository** | https://github.com/umontreal-simul/ssj |
| **Institution** | Université de Montréal |

**Key Features:**
- Event view, process view, continuous simulation
- Random number generation (multiple streams/substreams)
- Probability distributions and variate generators
- Quasi-Monte Carlo methods
- Statistical probes and goodness-of-fit tests

**Installation (Gradle):**
```kotlin
dependencies {
    implementation("ca.umontreal.iro.simul:ssj:3.3.2")
}
```

---

### 5. JSL (Java Simulation Library)

**Open-source discrete-event simulation.**

| Attribute | Value |
|-----------|-------|
| **Language** | Java |
| **Type** | Discrete Event Simulation |
| **License** | Open Source |
| **Repository** | https://github.com/rossetti/JSL |
| **Institution** | University of Arkansas |

**Key Features:**
- Event-driven architecture
- Statistical collection and analysis
- Queue and resource modeling
- Time-weighted statistics

---

### 5. SimPy (Python Alternative)

If you're open to Python, SimPy is the industry standard.

| Attribute | Value |
|-----------|-------|
| **Language** | Python |
| **Type** | Discrete Event Simulation |
| **License** | MIT |
| **Status** | ✅ Actively Maintained |

---

## Comparison Matrix

| Feature | JDisco | DSOL | SSJ | Kalasim | DESMO-J |
|---------|--------|------|-----|---------|---------|
| **Language** | Java | Java | Java | Kotlin | Java |
| **Maintained** | ❌ No | ✅ Yes | ✅ Yes | ✅ Yes | ⚠️ Limited |
| **Last Update** | 2004 | Nov 2025 | 2023+ | 2023+ | 2016 |
| **Discrete Events** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Continuous Sim** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Combined Sim** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **DEVS Support** | ❌ | ✅ | ❌ | ❌ | ❌ |
| **Agent-Based** | ❌ | ✅ | ❌ | ❌ | ❌ |
| **Kotlin Native** | ❌ | ❌ | ❌ | ✅ | ❌ |
| **Coroutines** | ❌ | ❌ | ❌ | ✅ | ❌ |
| **Maven/Gradle** | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Visualization** | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Java Version** | Legacy | Java 17+ | Java 8+ | JVM | Java 11+ |
| **License** | Research | BSD-3 | Apache 2.0 | MIT | Apache 2.0 |

---

## Recommendation for Kotlin Users

### For Combined Discrete-Continuous Simulation: DSOL

**DSOL** is the clear winner for combined simulation:

1. **Multi-formalism** — Discrete-event, continuous (differential equations), DEVS, agent-based
2. **Actively maintained** — Version 4.3.2 released November 2025
3. **Modern Java** — Java 17+ support
4. **Rich ecosystem** — Animation, GIS, web visualization
5. **Proven in production** — OpenTrafficSim, MedLabs
6. **Open source** — BSD-3-Clause license

**Gradle Kotlin DSL Setup:**
```kotlin
dependencies {
    implementation("nl.tudelft.simulation:dsol-core:4.3.2")
    // Optional: for differential equations
    // implementation("nl.tudelft.simulation:dsol-core:4.3.2")
}
```

### For Discrete-Only Simulation: Kalasim

If you only need discrete event simulation and want native Kotlin:

1. **Native Kotlin** — Designed from the ground up for Kotlin
2. **Coroutine-based** — Uses Kotlin coroutines for process definitions
3. **Type-safe** — Full advantage of Kotlin's type system

### Secondary Options

- **SSJ** — For Monte Carlo/stochastic simulation with strong mathematical foundations
- **DESMO-J** — For 2D/3D visualization or existing Java code
- **JSL** — For simple discrete-event models with educational focus

### Migration Path from JDisco

If you have existing JDisco code:

1. **Identify core processes** — Map JDisco `Process` and `Continuous` classes
2. **Convert to DSOL** — Use DSOL's `DEVSSimulator` or `DifferentialEquationSimulator`
3. **Replace event handling** — Use DSOL's event scheduling mechanisms
4. **Update state variables** — Use DSOL's state management
5. **Add visualization** — Leverage DSOL's animation capabilities

**DSOL supports the same simulation paradigms as JDisco:**
- Discrete processes → DSOL event scheduling
- Continuous processes → DSOL differential equations solver
- Combined models → DSOL multi-formalism support

---

## Getting Started with DSOL

### Gradle Setup (Kotlin DSL)

```kotlin
plugins {
    kotlin("jvm") version "1.9.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("nl.tudelft.simulation:dsol-core:4.3.2")
    // For animation support
    implementation("nl.tudelft.simulation:dsol-animation:4.3.2")
}
```

### Basic Discrete Event Example (Java/Kotlin interop)

```kotlin
import nl.tudelft.simulation.dsol.experiment.Replication
import nl.tudelft.simulation.dsol.experiment.SingleReplication
import nl.tudelft.simulation.dsol.model.DsolModel
import nl.tudelft.simulation.dsol.simulators.DevsSimulator

fun main() {
    // Create simulator
    val simulator = DevsSimulator<Double>("sim")
    
    // Create and build model
    val model = MyModel(simulator)
    
    // Create replication
    val replication = SingleReplication<Double>("rep", 0.0, 0.0, 100.0)
    
    // Initialize and start
    simulator.initialize(model, replication)
    simulator.start()
}

class MyModel(simulator: DevsSimulator<Double>) : DsolModel<Double>(simulator) {
    override fun constructModel() {
        // Schedule events here
        simulator.scheduleEventAbs(10.0, this, "processArrival", null)
    }
    
    fun processArrival() {
        println("Customer arrived at ${simulator.simulatorTime}")
        // Schedule next arrival
        simulator.scheduleEventRel(5.0, this, "processArrival", null)
    }
}
```

---

## Conclusion

JDisco served its purpose as an academic research tool in the early 2000s, but it is no longer viable for modern development. 

**For combined discrete-continuous simulation** (like JDisco provided), **DSOL** is the clear recommendation:
- Actively maintained (November 2025)
- Supports discrete-event, continuous, DEVS, and agent-based modeling
- Modern Java 17+ support
- BSD-3-Clause open source license
- Proven in production systems

**For discrete-only simulation in Kotlin**, **Kalasim** provides a native Kotlin experience with coroutines and type safety.

**For stochastic/Monte Carlo simulation**, **SSJ** remains excellent with ongoing updates from Université de Montréal.

---

## References

1. K. Helsgaun, "jDisco - a Java framework for combined discrete and continuous simulation", DATALOGISKE SKRIFTER, Roskilde University, 2001
2. DSOL Documentation: https://dsol.readthedocs.io/
3. DSOL GitHub: https://github.com/averbraeck/dsol
4. Kalasim Documentation: https://www.kalasim.org/
5. SSJ GitHub: https://github.com/umontreal-simul/ssj
6. DESMO-J: http://desmoj.sourceforge.net/
7. JSL GitHub: https://github.com/rossetti/JSL
8. P. Jacobs, "The DSOL simulation suite - Enabling multi-formalism simulation in a distributed context", PhD Thesis, 2005
