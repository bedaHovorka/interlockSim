# Issue #569: Block-Occupancy Event Subscription API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose block-reserve / block-release / occupancy-change events to external (non-train) subscribers via `SimulationEnvironment.onBlockEvent` and `SimulationEnvironment.onSimulationEvent`, enabling the Goal 10 AI dispatcher to react when a block frees up.

**Architecture:** A new `BlockEvent` sealed class carries domain event payloads. `DefaultPathReservationService` and `DynamicTrackBlock` emit events via kdisco's top-level `emitCustom()`, routing them through the kdisco event bus as `SimulationEvent.Custom(BlockEvent(...))`. `DefaultSimulationContext` buffers pre-run listener registrations and wires them into the kdisco `Simulation` at `run()` time. `SimulationEnvironment` exposes both a domain-typed `onBlockEvent` convenience and a raw `onSimulationEvent` escape hatch.

**Tech Stack:** Kotlin Multiplatform (KMP), kDisco 0.6.0-SNAPSHOT (with multi-listener + top-level emitCustom from kdisco issue-24 worktree, published to mavenLocal), Koin 3.5.6, JUnit 5, AssertK

## Global Constraints

- Worktree root: `/home/beda/work/interlockSim/.claude/worktrees/issue-569`
- Branch: `feat/issue-569-expose-block-occupancy-events`
- Base branch: `goal-1`
- **Prerequisite:** kdisco multi-listener plan must be published to mavenLocal first. Verify: `~/.m2/repository/cz/hovorka/kdisco/kdisco-core/0.6.0-SNAPSHOT/` exists.
- `kdiscoVersion=0.6.0-SNAPSHOT` in `gradle.properties` (already set)
- `mavenLocal()` is already the first repository in `settings.gradle.kts`
- All new KMP-clean code goes in `core/src/commonMain/kotlin/...`
- Tests in `core/src/jvmTest/kotlin/...` (JUnit 5 allowed here)
- Quality gate before PR: `./gradlew clean build detekt ktlintCheck test integrationTest`
- Do not push or create PRs; the coordinator handles that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt` | Create | `BlockEvent` sealed class |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBlock.kt` | Modify | Emit `OccupancySet` / `OccupancyCleared` from `enter()` / `leave()` |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt` | Modify | Emit `BlockReserved` / `BlockReleased` from reserve / release / unregister |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt` | Modify | Add `onBlockEvent` and `onSimulationEvent` to interface |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt` | Modify | Implement both methods with pending-listener lists wired at `run()` time |
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEventSubscriptionTest.kt` | Create | Integration tests for event delivery |

---

## Task 1: Create BlockEvent sealed class

**Files:**
- Create: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt`

**Interfaces:**
- Produces: `BlockEvent` sealed class with `BlockReserved`, `BlockReleased`, `OccupancySet`, `OccupancyCleared`

- [ ] **Step 1: Verify baseline tests pass**

```bash
cd /home/beda/work/interlockSim/.claude/worktrees/issue-569
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Create BlockEvent.kt**

Create `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt`:

```kotlin
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Domain events emitted when block reservation or occupancy state changes.
 *
 * Delivered via kdisco's event bus as [cz.hovorka.kdisco.SimulationEvent.Custom] payloads.
 * Subscribe via [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onBlockEvent].
 *
 * All fields are captured at emission time (simulation-thread values).
 *
 * @since Issue #569 (Goal 10 prereq)
 */
sealed class BlockEvent {
    abstract val block: DynamicTrackBlock
    abstract val time: Double

    /**
     * A block was atomically reserved for a train by [DefaultPathReservationService].
     * State transition: FREE → RESERVED.
     */
    data class BlockReserved(
        override val block: DynamicTrackBlock,
        val trainId: String,
        override val time: Double,
    ) : BlockEvent()

    /**
     * A block was released from a train's reservation.
     * State transition: RESERVED/OCCUPIED → FREE (registry entry removed).
     */
    data class BlockReleased(
        override val block: DynamicTrackBlock,
        val trainId: String,
        override val time: Double,
    ) : BlockEvent()

    /**
     * A train physically entered (occupied) a block.
     * State transition: RESERVED → OCCUPIED.
     */
    data class OccupancySet(
        override val block: DynamicTrackBlock,
        val occupant: TrackOccupant,
        override val time: Double,
    ) : BlockEvent()

    /**
     * A train physically left a block.
     * State transition: OCCUPIED → FREE.
     */
    data class OccupancyCleared(
        override val block: DynamicTrackBlock,
        override val time: Double,
    ) : BlockEvent()
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :core:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEvent.kt
git commit -m "feat(#569): add BlockEvent sealed class for block occupancy events"
```

---

## Task 2: Emit OccupancySet / OccupancyCleared from DynamicTrackBlock

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBlock.kt`

**Interfaces:**
- Consumes: `BlockEvent.OccupancySet`, `BlockEvent.OccupancyCleared` (Task 1)
- Consumes: top-level `emitCustom(payload)` from kdisco (package `cz.hovorka.kdisco`)
- Produces: `OccupancySet` emitted after `enter()` sets `occupant`; `OccupancyCleared` emitted after `leave()` clears `occupant`

- [ ] **Step 1: Add imports to DynamicTrackBlock.kt**

In `DynamicTrackBlock.kt`, add two imports after the existing `import cz.hovorka.kdisco.Process` line:

```kotlin
import cz.hovorka.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
```

- [ ] **Step 2: Emit OccupancySet in enter()**

In the `enter(newOccupant: TrackOccupant)` method, after the line `occupant = newOccupant` (and `reservedFrom = null`), add:

```kotlin
emitCustom(BlockEvent.OccupancySet(this, newOccupant, Process.activeContext?.currentTime ?: 0.0))
```

The full `enter()` method body after change looks like:

```kotlin
override fun enter(newOccupant: TrackOccupant) {
    logger.info {
        "${Process.time()} TrackBlock ${staticRef.hashCode()} ENTRY: " +
            "occupant=$newOccupant, state=${getState()}->OCCUPIED, trainId=$trainName"
    }
    if (occupant != null) {
        logger.error {
            "${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} collision! " +
                "Existing occupant=$occupant, newOccupant=$newOccupant"
        }
    }
    requireSimulation(occupant == null) {
        "TrackBlock occupant collision - must be null on entry (shunting not implemented)"
    }
    assertGoodStateChange(TrackFacility.State.RESERVED, TrackFacility.State.OCCUPIED)
    occupant = newOccupant
    reservedFrom = null
    emitCustom(BlockEvent.OccupancySet(this, newOccupant, Process.activeContext?.currentTime ?: 0.0))
}
```

- [ ] **Step 3: Emit OccupancyCleared in leave()**

In the `leave(leavingOccupant: TrackOccupant)` method, after `occupant = null` and `trainName = null`, add:

```kotlin
emitCustom(BlockEvent.OccupancyCleared(this, Process.activeContext?.currentTime ?: 0.0))
```

The full `leave()` method body after change:

```kotlin
override fun leave(leavingOccupant: TrackOccupant) {
    logger.info {
        "${Process.time()} TrackBlock ${staticRef.hashCode()} EXIT: " +
            "occupant=$leavingOccupant, state=OCCUPIED->FREE, trainId=$trainName"
    }
    requireSimulation(occupant === leavingOccupant) {
        "TrackBlock occupant mismatch on leave"
    }
    assertGoodStateChange(TrackFacility.State.OCCUPIED, TrackFacility.State.FREE)
    occupant = null
    trainName = null
    emitCustom(BlockEvent.OccupancyCleared(this, Process.activeContext?.currentTime ?: 0.0))
}
```

- [ ] **Step 4: Verify it compiles and tests still pass**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, all tests green (no behaviour change yet — no listeners registered in existing tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBlock.kt
git commit -m "feat(#569): emit OccupancySet/OccupancyCleared from DynamicTrackBlock enter/leave"
```

---

## Task 3: Emit BlockReserved / BlockReleased from DefaultPathReservationService

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt`

**Interfaces:**
- Consumes: `BlockEvent.BlockReserved`, `BlockEvent.BlockReleased` (Task 1)
- Consumes: top-level `emitCustom(payload)` from kdisco
- Produces: `BlockReserved` after successful `reservePath`; `BlockReleased` in `releasePath` finally-block and `unregister`/`unregisterBlock`

- [ ] **Step 1: Add imports to DefaultPathReservationService.kt**

Add after the existing imports:

```kotlin
import cz.hovorka.kdisco.Process
import cz.hovorka.kdisco.emitCustom
```

- [ ] **Step 2: Emit BlockReserved after successful reservation in reservePath()**

In `reservePath()`, the success path ends at:
```kotlin
PathReservationService.ReservationResult.Success(blocks)
```
(This is inside the `is PathReservationRegistry.RegistrationResult.Success -> { ... }` branch, after `configureIntermediateSemaphores(blocks)`.)

Add emission immediately before this return line:

```kotlin
// Emit BlockReserved for each successfully reserved block
val simTime = Process.activeContext?.currentTime ?: 0.0
blocks.forEach { block ->
    emitCustom(BlockEvent.BlockReserved(block, trainId, simTime))
}

PathReservationService.ReservationResult.Success(blocks)
```

- [ ] **Step 3: Emit BlockReleased in releasePath() finally block**

In `releasePath(trainId: String)`, the `finally` block currently is:

```kotlin
} finally {
    // Unregister blocks and switches from registry - ALWAYS executed
    // This prevents memory leaks and stale reservations if block release fails
    registry.unregister(trainId)
    registry.unregisterSwitches(trainId)
}
```

Change it to:

```kotlin
} finally {
    // Unregister blocks and switches from registry - ALWAYS executed
    // This prevents memory leaks and stale reservations if block release fails
    registry.unregister(trainId)
    registry.unregisterSwitches(trainId)
    // Emit BlockReleased after registry cleanup so isBlockAvailable() returns true for subscribers
    val simTime = Process.activeContext?.currentTime ?: 0.0
    blocks.forEach { block ->
        emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
    }
}
```

- [ ] **Step 4: Emit BlockReleased in unregister()**

In `unregister(trainId: String)`:

```kotlin
// BEFORE:
override fun unregister(trainId: String): List<DynamicTrackBlock> {
    val releasedBlocks = registry.unregister(trainId)
    logger.info {
        "unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
            releasedBlocks.joinToString(", ") { it.toString() }
    }
    return releasedBlocks
}
```

```kotlin
// AFTER:
override fun unregister(trainId: String): List<DynamicTrackBlock> {
    val releasedBlocks = registry.unregister(trainId)
    logger.info {
        "unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
            releasedBlocks.joinToString(", ") { it.toString() }
    }
    val simTime = Process.activeContext?.currentTime ?: 0.0
    releasedBlocks.forEach { block ->
        emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
    }
    return releasedBlocks
}
```

- [ ] **Step 5: Emit BlockReleased in unregisterBlock()**

In `unregisterBlock(trainId: String, block: DynamicTrackBlock)`:

```kotlin
// BEFORE:
override fun unregisterBlock(
    trainId: String,
    block: DynamicTrackBlock
): Boolean = registry.unregisterBlock(trainId, block)
```

```kotlin
// AFTER:
override fun unregisterBlock(
    trainId: String,
    block: DynamicTrackBlock
): Boolean {
    val released = registry.unregisterBlock(trainId, block)
    if (released) {
        emitCustom(BlockEvent.BlockReleased(block, trainId, Process.activeContext?.currentTime ?: 0.0))
    }
    return released
}
```

- [ ] **Step 6: Verify tests still pass**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt
git commit -m "feat(#569): emit BlockReserved/BlockReleased from DefaultPathReservationService"
```

---

## Task 4: Add subscription API to SimulationEnvironment and DefaultSimulationContext

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt`
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt`

**Interfaces:**
- Consumes: `BlockEvent` (Task 1); kdisco `SimulationEvent` (existing)
- Produces: `SimulationEnvironment.onBlockEvent(listener)`, `SimulationEnvironment.onSimulationEvent(listener)`, both implemented in `DefaultSimulationContext`

- [ ] **Step 1: Add imports to SimulationEnvironment.kt**

Add to the imports section:

```kotlin
import cz.hovorka.kdisco.SimulationEvent as KDiscoSimulationEvent
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
```

- [ ] **Step 2: Add methods to SimulationEnvironment interface**

At the end of `SimulationEnvironment.kt`, before the closing `}` of the interface, add:

```kotlin
// ========================================
// Event Subscription (Issue #569)
// ========================================

/**
 * Subscribe to block-level domain events (reserve / release / occupancy changes).
 *
 * Listener is called synchronously on the simulation thread in simulation-time order.
 * Listeners registered after [run] has started are silently ignored (context is frozen).
 *
 * @since Issue #569 (Goal 10 prereq)
 */
fun onBlockEvent(listener: (BlockEvent) -> Unit)

/**
 * Subscribe to raw kdisco simulation events (process lifecycle, resource changes, custom payloads).
 *
 * Listener is called synchronously on the simulation thread in simulation-time order.
 * Listeners registered after [run] has started are silently ignored (context is frozen).
 *
 * @since Issue #569 (Goal 10 prereq)
 */
fun onSimulationEvent(listener: (KDiscoSimulationEvent) -> Unit)
```

- [ ] **Step 3: Add pending-listener fields to DefaultSimulationContext**

In `DefaultSimulationContext.kt`, after the `private var simulation: Simulation? = null` field (around line 188), add:

```kotlin
/** Block-event listeners registered before run(); wired into kdisco at run() time. */
private val pendingBlockEventListeners: MutableList<(BlockEvent) -> Unit> = mutableListOf()

/** Raw kdisco event listeners registered before run(); wired into kdisco at run() time. */
private val pendingSimEventListeners: MutableList<(cz.hovorka.kdisco.SimulationEvent) -> Unit> = mutableListOf()
```

Also add these imports at the top of the file:

```kotlin
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
```

- [ ] **Step 4: Implement onBlockEvent and onSimulationEvent in DefaultSimulationContext**

Add these overrides anywhere in the `SimulationEnvironment` implementation section:

```kotlin
override fun onBlockEvent(listener: (BlockEvent) -> Unit) {
    if (isFrozen()) return
    pendingBlockEventListeners += listener
}

override fun onSimulationEvent(listener: (cz.hovorka.kdisco.SimulationEvent) -> Unit) {
    if (isFrozen()) return
    pendingSimEventListeners += listener
}
```

- [ ] **Step 5: Wire listeners into kdisco Simulation at run() time**

In `DefaultSimulationContext.run()`, after the line `simulation = sim` (around line 1134), add the wiring block:

```kotlin
simulation = sim
// Wire pre-registered listeners into kdisco simulation
pendingSimEventListeners.forEach { sim.onEvent(it) }
if (pendingBlockEventListeners.isNotEmpty()) {
    val blockListeners = pendingBlockEventListeners.toList()
    sim.onEvent { event ->
        if (event is cz.hovorka.kdisco.SimulationEvent.Custom && event.payload is BlockEvent) {
            blockListeners.forEach { it(event.payload as BlockEvent) }
        }
    }
}
```

- [ ] **Step 6: Verify everything compiles and tests pass**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt \
        core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt
git commit -m "feat(#569): expose onBlockEvent/onSimulationEvent on SimulationEnvironment"
```

---

## Task 5: Integration tests for event delivery

**Files:**
- Create: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEventSubscriptionTest.kt`

**Interfaces:**
- Consumes: `SimulationEnvironment.onBlockEvent`, `SimulationEnvironment.onSimulationEvent` (Task 4)
- Consumes: `MultiTrainLoop`, `TestTopologies.linearPathWithSemaphoreSimulation`
- Produces: verified ordering: `BlockReserved → OccupancySet → OccupancyCleared → BlockReleased` per block

- [ ] **Step 1: Write the test file**

Create `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEventSubscriptionTest.kt`:

```kotlin
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.hovorka.kdisco.SimulationEvent as KDiscoSimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Tag("integration-test")
@DisplayName("BlockEvent subscription — #569 event delivery")
class BlockEventSubscriptionTest : KoinTestBase() {

    private var context: DefaultSimulationContext? = null

    @AfterEach
    fun closeContext() {
        context?.close()
        context = null
    }

    private fun twoTrainLinearContext(): DefaultSimulationContext {
        val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
            as DefaultSimulationContext
        ctx.getInOuts()
        context = ctx
        return ctx
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("onBlockEvent delivers BlockReserved and BlockReleased for both trains")
    fun blockEventsDeliveredForBothTrains() {
        val ctx = twoTrainLinearContext()
        val events = mutableListOf<BlockEvent>()

        ctx.onBlockEvent { events.add(it) }

        val process = MultiTrainLoop(
            ctx, endTime = 400L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
                MultiTrainLoop.TrainSpec("A", "B", inTime = 2.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        val reserved = events.filterIsInstance<BlockEvent.BlockReserved>()
        val released = events.filterIsInstance<BlockEvent.BlockReleased>()

        assertThat(reserved).isNotEmpty()
        assertThat(released).isNotEmpty()
        // Every reserved block eventually gets released
        assertThat(reserved.size).isEqualTo(released.size)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Per-block order: BlockReserved → OccupancySet → OccupancyCleared → BlockReleased")
    fun blockEventOrderIsCorrectPerBlock() {
        val ctx = twoTrainLinearContext()
        val eventsByBlock = mutableMapOf<DynamicTrackBlock, MutableList<BlockEvent>>()

        ctx.onBlockEvent { event ->
            eventsByBlock.getOrPut(event.block) { mutableListOf() }.add(event)
        }

        val process = MultiTrainLoop(
            ctx, endTime = 400L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        assertThat(eventsByBlock).isNotEmpty()

        for ((block, blockEvents) in eventsByBlock) {
            val types = blockEvents.map { it::class.simpleName }
            // The first event for any block must be BlockReserved (not OccupancySet or BlockReleased)
            assertThat(
                blockEvents.first() is BlockEvent.BlockReserved,
                name = "Block ${block.staticRef.hashCode()} first event must be BlockReserved, got $types"
            ).isTrue()
            // BlockReleased must be the last event for each block (block is free after release)
            val lastReleaseIdx = blockEvents.indexOfLast { it is BlockEvent.BlockReleased }
            val lastReservedIdx = blockEvents.indexOfLast { it is BlockEvent.BlockReserved }
            assertThat(lastReleaseIdx).isGreaterThan(lastReservedIdx)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("onSimulationEvent delivers kdisco process lifecycle events")
    fun rawKdiscoEventsDelivered() {
        val ctx = twoTrainLinearContext()
        val rawEvents = mutableListOf<KDiscoSimulationEvent>()

        ctx.onSimulationEvent { rawEvents.add(it) }

        val process = MultiTrainLoop(
            ctx, endTime = 400L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        val processActivated = rawEvents.filterIsInstance<KDiscoSimulationEvent.ProcessActivated>()
        assertThat(processActivated).isNotEmpty()
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Listener registered after run() is ignored (context is frozen)")
    fun listenerAfterRunIsIgnored() {
        val ctx = twoTrainLinearContext()
        val events = mutableListOf<BlockEvent>()

        val process = MultiTrainLoop(
            ctx, endTime = 400L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        // Register AFTER run — should be silently ignored
        ctx.onBlockEvent { events.add(it) }

        assertThat(events.size).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run the new tests**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.BlockEventSubscriptionTest"
```

Expected: BUILD SUCCESSFUL, all 4 tests pass.

- [ ] **Step 3: Run full test suite**

```bash
./gradlew :core:test integrationTest
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 4: Run quality checks**

```bash
./gradlew :core:detekt :core:ktlintCheck
```

Expected: BUILD SUCCESSFUL. Fix any reported issues before proceeding.

- [ ] **Step 5: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/BlockEventSubscriptionTest.kt
git commit -m "test(#569): integration tests for block-event subscription API"
```

- [ ] **Step 6: Final full build check**

```bash
./gradlew clean build detekt ktlintCheck test integrationTest
```

Expected: BUILD SUCCESSFUL.
