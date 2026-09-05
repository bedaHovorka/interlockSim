# Test Utilities Guide

Quick reference for writing tests in the interlockSim project.

## Overview

The `testutil` package provides:
- **TestFixtures** - XML configuration loading
- **TestTopologies** - Programmatic network creation
- **TestContextBuilder** - Custom topology builder
- **KoinTestBase** - Base class for Koin DI tests; `tracked()` registers a context for
  automatic close in `tearDownKoin()` (Issue #1038)
- **AssertKExtensions** - Custom assertions
- **HeadingFlipSampler** - Per-train raw/resolved heading sampling for the heading-flip
  regression tests (`gui.animation`), owner of the #789 per-train skip contract
- **HeadingSamplerTestBase** - Shared scaffolding for those regression tests: injects the
  process factory and exposes `startSamplerContext()`, which creates the shunting context,
  registers it with `tracked()` for `tearDownKoin()` cleanup (Issues #1026, #1038), and points the
  `calculator`/`sampler` fields at it
- **ContextRoundTrip** - `saveAndReloadThroughFile` / `saveAndReloadThroughStream`
  extensions on `ContextFactory`: save a context, load it back, run a verify lambda, and
  close the loaded context (Issue #1035). The source context stays open — the caller owns it
- **ContextPropertyEvents** - `TestPropertyChangeListener` plus
  `assertMaxSpeedPropertyEventFires` / `assertNetworkProperties` / `assertFrozen`, shared by
  the context lifecycle workflow integration tests
- **NetworkBuilders** - `buildLinearNetwork`: two InOuts on a square grid joined by one
  track, the smallest network that can be transformed and simulated. The caller owns the
  returned context and must close it (`.use`)
- **RailwayNetGridAssertions** - `gridContainsCellType<T>` and `countCellTypes`: one shared
  grid scan replacing the four private copies that classified cells by type
- **PathExistence** - `existPath(from, to, context)`: BFS connectivity check between two
  InOuts over the context's track graph; one shared copy replacing the three private
  helpers of the XML factory tests (PR #1043 review round)
- **EditingContextCleanupContractTest** - Contract tests pinning the `.use {}` cleanup
  pattern: scope closed on success, on failure inside the block, on double close, for
  the loaded context of a round trip, and a round trip failing at the save step (Issue #1035)
- **RudyUjezdStructure** - `assertRudyUjezdStationInOuts`: asserts the four station
  InOuts of the `rudyUjezd.xml` fixture exist and returns them (f1, f2, s1, s2); shared by
  the parse test and the stream round trip of the XML factory tests

Shared fixture-library helpers from `:core-test` (same package, KMP `commonMain`) are also
visible here: `ContextTracker` (the registry behind `tracked()`), `runSampled` (listener-wiring
harness), `ArrivalTally` (completed-journey witness), `sameStatic`/`separatorLabel`
(dynamic-wrapper-safe separator identity).

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
| **Y-Junction** (1 entry, 1 switch, 2 exits - diverging) | `yJunctionWithSwitch()` | `yJunctionWithSwitchSimulation()` |
| **Multi-Semaphore** (A→[S1]→...→[Sn]→B) | `linearPathWithSemaphoreSequence(count, allowing)` | `linearPathWithSemaphoreSequenceSimulation(count, allowing)` |
| Dead-end | `deadEndSingleInOut()` | `deadEndSingleInOutSimulation()` |

**Note:** For complex switch patterns with multiple switches AND semaphore sequences on each route, use XML fixtures:
- Simple switch: `TestFixtures.loadSwitchBasicXml()`
- Complex routing: `TestFixtures.loadShuntingXml()` (vyhybna.xml)

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

**Contexts held across a whole test method (KoinTestBase subclasses):** register them with
`.tracked()` — `tearDownKoin()` closes every tracked context after each test, in reverse
registration order (Issues #1026, #1038):

```kotlin
val context = TestFixtures.newShuntingSimulationContext(...).tracked()  // tearDownKoin() closes it
```

The heading-flip regression tests get this from `HeadingSamplerTestBase.startSamplerContext()`.

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

### Pattern 3: Save-and-Reload Round Trip

```kotlin
@Test
fun roundTrip(@TempDir tempDir: File) {
    DefaultEditingContext(40, 40).use { editingContext ->
        // ... build the network ...

        xmlFactory.saveAndReloadThroughFile(editingContext, File(tempDir, "test.xml")) { loaded ->
            // Verify the loaded context; it is closed by the helper afterwards
        }
    }
}
```

The stream variant `saveAndReloadThroughStream(editingContext) { loaded -> ... }` does the
same through `ByteArrayOutputStream`/`ByteArrayInputStream`. Both close the loaded context
and leave the source context to the caller (Issue #1035).

### Pattern 4: Shared Contexts Across Tests

```kotlin
class MyTestSuite : KoinTestBase() {
    private lateinit var editing: EditingContext
    private lateinit var context: SimulationContext

    @BeforeEach
    fun setUp() {
        editing = editingContextFactory.createEmptyContext().tracked()
        context = simulationContextFactory.createContext(editing).tracked()
        // tearDownKoin() closes both (simulation first, then editing) — no manual @AfterEach needed
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

## New Topologies (Issue #253 Post-Implementation)

The following topologies were added based on common duplication patterns found in tests:

### Y-Junction with Switch

```kotlin
TestTopologies.yJunctionWithSwitch().use { context ->
    // Diverging Y-junction: single entry splits at switch to two exits
    // Entry → Switch → ExitMain (straight, segment F - main route)
    //              → ExitBranch (diagonal, segment G - branch route)
    // 3 track segments: 180-250m, speeds 80-100 m/s
    // Grid: 60x50, switch at (30,30)
    // IMPORTANT: SIMPLE switch supports exactly 3 connections (segments A, F, G)
}
```

**Use cases:**
- Multi-route path finding tests
- Switch configuration testing
- Complex network topology validation

### Linear Path with Multiple Semaphores

```kotlin
TestTopologies.linearPathWithSemaphoreSequence(
    semaphoreCount = 5,
    semaphoresAllowing = false
).use { context ->
    // Creates A→[S1]→[S2]→[S3]→[S4]→[S5]→B
    // Semaphores evenly spaced (5 grid units apart)
    // 100m track segments, 80 m/s speed
    // Grid size auto-calculated
}
```

**Use cases:**
- Multi-signal coordination testing
- Complex path reservation scenarios
- Train progression through multiple control points
- Signal sequencing validation

---

**Last Updated**: 2026-09-05 (PR #1043 review round: shared PathExistence BFS helper; Issue #1035 round-trip helpers, shared network builder and grid scan, rudyUjezd structure check, and cleanup contract)
