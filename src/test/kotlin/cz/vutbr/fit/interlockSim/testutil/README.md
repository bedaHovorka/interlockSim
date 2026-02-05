# Test Utilities Guide

Quick reference for writing tests in the interlockSim project.

## Overview

The `testutil` package provides:
- **TestFixtures** - XML configuration loading
- **TestTopologies** - Programmatic network creation
- **TestContextBuilder** - Custom topology builder
- **KoinTestBase** - Base class for Koin DI tests
- **AssertKExtensions** - Custom assertions

## Quick Start

### 1. Loading XML Configurations

```kotlin
import cz.vutbr.fit.interlockSim.testutil.TestFixtures

@Test
fun myTest() {
    TestFixtures.loadShuntingXml().use { stream ->
        val context = factory.createContext(stream)
        // Test code
    }
}
```

**Available XML Fixtures:**
- `loadShuntingXml()` - vyhybna.xml (main example)
- `loadLinearTrackXml()` - Simple A→B
- `loadSwitchBasicXml()` - Basic switch
- `loadSemaphoreBasicXml()` - Basic semaphore
- ...and 12 more (see TestFixtures.kt)

### 2. Using Pre-built Topologies

```kotlin
import cz.vutbr.fit.interlockSim.testutil.TestTopologies

// For EditingContext (editor/topology tests)
@Test
fun testTopology() {
    TestTopologies.simpleLinearPath().use { context ->
        // Test topology navigation
    }
}

// For SimulationContext (simulation/train tests)
@Test
fun testSimulation() {
    TestTopologies.simpleLinearPathSimulation().use { context ->
        val trainService = context.getTrainNavigationService()
        // Test train behavior
    }
}
```

**Available Topologies:**

| Topology | EditingContext | SimulationContext |
|----------|----------------|-------------------|
| A→B (100m) | `simpleLinearPath()` | `simpleLinearPathSimulation()` |
| A→[S]→B | `linearPathWithSemaphore(allowing)` | `linearPathWithSemaphoreSimulation(allowing)` |
| Dead-end | `deadEndSingleInOut()` | `deadEndSingleInOutSimulation()` |

### 3. Building Custom Topologies

```kotlin
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder

@Test
fun customTopology() {
    val context = TestContextBuilder()
        .withInOut("A", 2, 3, true)
        .withInOut("B", 8, 9, false)
        .withConnection(2, 3, 8, 9, 150.0, 100.0)
        .buildEditingContext()

    context.use {
        // Test with custom topology
    }
}
```

## Resource Management

**Always use `.use {}` with contexts:**

```kotlin
// ✅ CORRECT - auto-closes
TestTopologies.simpleLinearPath().use { context ->
    // Test code
}

// ❌ WRONG - resource leak
val context = TestTopologies.simpleLinearPath()
// ... test code ...
// context never closed!
```

**Nested contexts (XML → Editing → Simulation):**

```kotlin
TestFixtures.loadShuntingXml().use { stream ->
    editingFactory.createContext(stream).use { editing ->
        simulationFactory.createContext(editing).use { simulation ->
            // All resources auto-close in reverse order
        }
    }
}
```

## Choosing the Right Tool

```
┌─ Need test network?
│
├─ Real-world config (vyhybna.xml)?
│  └─ TestFixtures.loadShuntingXml()
│
├─ Simple topology (A→B, A→[S]→B)?
│  ├─ Editor/topology test?
│  │  └─ TestTopologies.simpleLinearPath()
│  └─ Simulation/train test?
│     └─ TestTopologies.simpleLinearPathSimulation()
│
└─ Custom topology?
   └─ TestContextBuilder()
```

## Common Patterns

### Pattern 1: Simple Test with Topology

```kotlin
@Test
fun testFeature() {
    TestTopologies.simpleLinearPath().use { context ->
        // Arrange
        val navigator = context.getTopologyNavigator()

        // Act
        val result = navigator.findPath(...)

        // Assert
        assertThat(result).isNotNull()
    }
}
```

### Pattern 2: Test with XML Config

```kotlin
@Test
fun testWithShuntingLoop() {
    TestFixtures.loadShuntingXml().use { stream ->
        editingFactory.createContext(stream).use { editing ->
            simulationFactory.createContext(editing).use { simulation ->
                // Test with real vyhybna.xml topology
            }
        }
    }
}
```

### Pattern 3: Shared Context Across Tests

```kotlin
class MyTestSuite {
    private lateinit var context: SimulationContext

    @BeforeEach
    fun setUp() {
        context = TestTopologies.simpleLinearPathSimulation()
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun test1() { /* use context */ }

    @Test
    fun test2() { /* use context */ }
}
```

## Anti-Patterns (Don't Do This!)

❌ **Hardcoded paths:**
```kotlin
File("src/main/resources/.../vyhybna.xml")  // BAD
```

❌ **Duplicate topologies:**
```kotlin
// Duplicated across 15 test methods
TestContextBuilder()
    .withInOut("A", 1, 1, true)
    .withInOut("B", 5, 5, false)
    .withConnection(1, 1, 5, 5, 100.0, 80.0)
    .buildEditingContext()
```

❌ **Forgotten closes:**
```kotlin
val context = factory.createContext(stream)
// ... test ...
// Never closed!
```

❌ **Wrong context type:**
```kotlin
val context = TestTopologies.simpleLinearPath()  // EditingContext
context.getTrainNavigationService()  // ERROR - not available!
```

## See Also

- **Full Guide**: `docs/KOTLIN_STYLE_GUIDE.md` (Test Fixtures section)
- **API Documentation**: `TestFixtures.kt` and `TestTopologies.kt` (inline KDoc)
- **Examples**: See any test in `context/navigation/` package

## Contributing

When adding new test fixtures:

1. **XML files** → Add to `src/test/resources/.../fixtures/`
2. **Add loader** → Add `loadXxxXml()` function to `TestFixtures`
3. **Common topologies** → Consider adding to `TestTopologies`
4. **Update docs** → Update this README and KOTLIN_STYLE_GUIDE.md

---

**Last Updated**: 2026-02-05 (Created as part of Issue #253)
