# #618 Train-Completion Cleanup Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the production train-completion reservation cleanup path so that switches are unlocked and both the new `BlockEvent` and legacy `BlockOccupancyEvent` release channels fire consistently.

**Architecture:** Add a private dual-channel `emitBlockReleased` helper to `DefaultPathReservationService`, use it from `releasePath`, `unregister`, and `unregisterBlock`, and extend `unregister` to unlock switches the same way `releasePath` already does. All changes stay inside `DefaultPathReservationService` and its existing JVM test suite.

**Tech Stack:** Kotlin (commonMain), JUnit 5, AssertK, kDisco, Gradle.

## Global Constraints

- Branch from `develop`: `feat/issue-618-train-completion-cleanup`
- Open PR to `develop`; do not auto-merge
- Pass `./gradlew clean build detekt ktlintCheck test integrationTest`
- Follow existing Kotlin style (tabs, max line length 120)
- Keep all changes conservative; no public API changes
- `sim/` package logic changes require traffic-simulation-expert sign-off already captured in issue #618
- Tests for `PathReservationServiceTest` and `PathReservationRegistryTest` are tagged `@Tag("integration-test")`

---

## File Structure

| File | Responsibility |
|------|--------------|
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt` | Contains the release-path logic to fix. Add helper, update `unregister`, `unregisterBlock`, and inline `releasePath` to use the helper. |
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt` | Existing integration test class. Extend with I1/I2 tests for `unregister`, `releaseTrainReservations`, switch unlock, and legacy listener events. |
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/SwitchConfigurationTest.kt` | Existing unit test class with `zA`, `doA1`, `doA2`, `vA` already initialized. Add the multi-train switch-reconfiguration-after-completion test here. |

---

### Task 1: Add the dual-channel `emitBlockReleased` helper and update `releasePath` to use it

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt`

**Interfaces:**
- Consumes: `emitCustom(BlockEvent)`, `registry.emit(BlockOccupancyEvent)`, `TrackFacility.State`, `BlockOccupancyEventType`, `currentSimulationTime()`
- Produces: private helper `emitBlockReleased(block: DynamicTrackBlock, trainId: String, simTime: Double)`

- [ ] **Step 1: Add the private helper near the bottom of `DefaultPathReservationService`, before `MinimalTrackOccupant`**

```kotlin
	/**
	 * Emit both the new kdisco-bus [BlockEvent.BlockReleased] and the legacy
	 * [BlockOccupancyEvent] (BLOCK_RELEASED) for a single block.
	 *
	 * This keeps the two event channels consistent on every release path
	 * ([releasePath], [unregister], [unregisterBlock]).
	 */
	private fun emitBlockReleased(
		block: DynamicTrackBlock,
		trainId: String,
		simTime: Double
	) {
		emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
		registry.emit(
			BlockOccupancyEvent(
				block = block,
				type = BlockOccupancyEventType.BLOCK_RELEASED,
				trainId = trainId,
				occupant = null,
				previousState = TrackFacility.State.RESERVED,
				newState = TrackFacility.State.FREE,
				simulationTime = simTime
			)
		)
	}
```

- [ ] **Step 2: Replace the inline dual-channel emission in `releasePath` with the helper**

Find this block inside `releasePath` (around lines 392–408):

```kotlin
			val simTime = currentSimulationTime()
			blocks.forEach { block ->
				emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
				// Also notify addBlockOccupancyListener subscribers (legacy API, works without run())
				registry.emit(
					BlockOccupancyEvent(
						block = block,
						type = BlockOccupancyEventType.BLOCK_RELEASED,
						trainId = trainId,
						occupant = null,
						previousState = TrackFacility.State.RESERVED,
						newState = TrackFacility.State.FREE,
						simulationTime = simTime
					)
				)
			}
```

Replace it with:

```kotlin
			val simTime = currentSimulationTime()
			blocks.forEach { block ->
				emitBlockReleased(block, trainId, simTime)
			}
```

- [ ] **Step 3: Run the integration tests that exercise `releasePath` to ensure no regression**

Run:

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest" --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistryTest"
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt
git commit -m "refactor(#618): extract dual-channel block-release helper" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Unlock switches in `unregister`

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt`
- Test: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt`

**Interfaces:**
- Consumes: `registry.getSwitches(trainId)`, `registry.unregister(trainId)`, `registry.unregisterSwitches(trainId)`, `DynamicRailSwitch.unlock()`
- Produces: updated `override fun unregister(trainId: String): List<DynamicTrackBlock>`

- [ ] **Step 1: Add `PathReservationRegistry` field to the test class**

Open `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt`.

Add the import:

```kotlin
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
```

Add the field next to the other `lateinit` fields:

```kotlin
	private lateinit var registry: PathReservationRegistry
```

Initialize it in `setUp()` after `navigator`:

```kotlin
		registry = simulationContext.scope.get()
```

Note: `PathReservationServiceTest` uses backtick-named test methods and no `@DisplayName` annotations. Follow that style for the new tests.

- [ ] **Step 2: Write the failing test — switch unlock after `service.unregister(trainId)`**

Inside a new `@Nested inner class SwitchCleanupTests` at the bottom of `PathReservationServiceTest`, add:

```kotlin
	@Nested
	inner class SwitchCleanupTests {
		@Test
		fun `unregister unlocks all switches and clears switch registry`() {
			// Arrange: reserve a path through a switch (vyhybna.xml)
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val switches = registry.getSwitches("train1")
			assertThat(switches).isNotEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isTrue()
			}

			// Act: production cleanup path
			val releasedBlocks = service.unregister("train1")

			// Assert: blocks and switches released
			assertThat(releasedBlocks).isNotEmpty()
			assertThat(registry.getBlocks("train1")).isEmpty()
			assertThat(registry.getSwitches("train1")).isEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isFalse()
			}
		}
	}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*unregisterUnlocksSwitchesAndClearsRegistry"
```

Expected: FAIL — `switch.locked` is still `true` after `unregister`.

- [ ] **Step 4: Implement switch unlock in `unregister`**

Find `override fun unregister(trainId: String): List<DynamicTrackBlock>` and replace its body with:

```kotlin
	override fun unregister(trainId: String): List<DynamicTrackBlock> {
		// Unlock switches before registry cleanup, matching releasePath behavior.
		// unregister is the production train-completion path; releasePath is test-only.
		val switches = registry.getSwitches(trainId)
		switches.forEach { switch ->
			try {
				switch.unlock()
				logger.debug { "unregister: Unlocked switch ${switch.hashCode()} for $trainId" }
			} catch (e: Exception) {
				logger.warn(e) { "unregister: Failed to unlock switch $switch" }
			}
		}

		val releasedBlocks = registry.unregister(trainId)
		registry.unregisterSwitches(trainId)

		logger.info {
			"unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
				releasedBlocks.joinToString(", ") { it.toString() }
		}
		val simTime = currentSimulationTime()
		releasedBlocks.forEach { block ->
			emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
		}
		return releasedBlocks
	}
```

Note: do **not** use the new `emitBlockReleased` helper yet — that comes in Task 3. Keep this task focused on I1.

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*unregisterUnlocksSwitchesAndClearsRegistry"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt
git commit -m "fix(#618-I1): unlock switches in production unregister path" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Route `unregister` and `unregisterBlock` through the dual-channel helper

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt`
- Test: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt`

**Interfaces:**
- Consumes: `emitBlockReleased` helper from Task 1
- Produces: updated `unregister` and `unregisterBlock` that emit both new and legacy release events

- [ ] **Step 1: Write the failing tests for legacy `BlockOccupancyEvent(BLOCK_RELEASED)`**

In `PathReservationServiceTest`, add a nested test class or extend the existing `ExternalObserverApi` class with two tests.

Add to `ExternalObserverApi`:

```kotlin
		@Test
		fun `legacy listener receives BLOCK_RELEASED on unregister path`() {
			val listener = RecordingListener()
			environment.addBlockOccupancyListener(listener)

			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			val reservedCount = success.reservedBlocks.size

			// Clear reserve events so we can count releases in isolation
			listener.events.clear()

			service.unregister("train1")

			val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			assertThat(releasedEvents).hasSize(reservedCount)
			releasedEvents.forEach { event ->
				assertThat(event.trainId).isEqualTo("train1")
				assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
				assertThat(event.occupant).isNull()
			}
		}

		@Test
		fun `legacy listener receives BLOCK_RELEASED on unregisterBlock path`() {
			val listener = RecordingListener()
			environment.addBlockOccupancyListener(listener)

			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			val firstBlock = success.reservedBlocks.first()

			listener.events.clear()

			val released = service.unregisterBlock("train1", firstBlock)
			assertThat(released).isTrue()

			val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			assertThat(releasedEvents).hasSize(1)
			val event = releasedEvents.first()
			assertThat(event.block).isEqualTo(firstBlock)
			assertThat(event.trainId).isEqualTo("train1")
			assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
		}
```

The `RecordingListener` inner class already exists at the bottom of `PathReservationServiceTest`.

- [ ] **Step 2: Run the new tests and verify they fail**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*legacyListenerReceivesBlockReleasedOnUnregister" --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*legacyListenerReceivesBlockReleasedOnUnregisterBlock"
```

Expected: FAIL — `releasedEvents` is empty or too small because legacy events are not emitted.

- [ ] **Step 3: Update `unregister` to use the helper**

In `unregister`, replace:

```kotlin
		val simTime = currentSimulationTime()
		releasedBlocks.forEach { block ->
			emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
		}
```

with:

```kotlin
		val simTime = currentSimulationTime()
		releasedBlocks.forEach { block ->
			emitBlockReleased(block, trainId, simTime)
		}
```

- [ ] **Step 4: Update `unregisterBlock` to use the helper**

Find `unregisterBlock` and replace:

```kotlin
		if (released) {
			emitCustom(BlockEvent.BlockReleased(block, trainId, currentSimulationTime()))
		}
```

with:

```kotlin
		if (released) {
			emitBlockReleased(block, trainId, currentSimulationTime())
		}
```

- [ ] **Step 5: Run the new tests and verify they pass**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*legacyListenerReceivesBlockReleasedOnUnregister" --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*legacyListenerReceivesBlockReleasedOnUnregisterBlock"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt
git commit -m "fix(#618-I2): emit legacy BlockOccupancyEvent from production release paths" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Add end-to-end production-path test for switch unlock

**Files:**
- Test: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt`

**Interfaces:**
- Consumes: `DefaultSimulationContext.releaseTrainReservations(trainId)`
- Produces: test proving `releaseTrainReservations` unlocks switches

- [ ] **Step 1: Add the end-to-end test**

In `SwitchCleanupTests` (created in Task 2), add:

```kotlin
		@Test
		fun `releaseTrainReservations unlocks switches through production entry point`() {
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val switches = registry.getSwitches("train1")
			assertThat(switches).isNotEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isTrue()
			}

			simulationContext.releaseTrainReservations("train1")

			assertThat(registry.getBlocks("train1")).isEmpty()
			assertThat(registry.getSwitches("train1")).isEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isFalse()
			}
		}
```

- [ ] **Step 2: Run the test and verify it passes**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.context.navigation.PathReservationServiceTest*releaseTrainReservationsUnlocksSwitches"
```

Expected: PASS (because Task 2 already fixed the underlying behavior).

- [ ] **Step 3: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationServiceTest.kt
git commit -m "test(#618-I1): verify releaseTrainReservations unlocks switches" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: Add multi-train integration test for switch reconfiguration after completion

**Files:**
- Test: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/SwitchConfigurationTest.kt`

**Interfaces:**
- Consumes: `vyhybna.xml` topology, `reservePath`, `pathService.unregister`
- Produces: test proving a second train can reserve a switch in the opposite configuration after the first train's production cleanup

- [ ] **Step 1: Add the multi-train switch reconfiguration test**

Open `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/SwitchConfigurationTest.kt`.

At the bottom of `SwitchConfigurationTest`, add:

```kotlin
	@Test
	fun `second train can reconfigure switch after first train completes`() {
		// First train: zA -> vA(BRANCH) -> doA2
		val pathService = context.getPathReservationService()
		val result1 = pathService.reservePath("train1", zA, doA2)
		assertThat(result1).isNotNull()
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.BRANCH)
		assertThat(vA.locked).isTrue()

		// Production completion cleanup for train1
		pathService.unregister("train1")
		assertThat(vA.locked).isFalse()

		// Second train: zA -> vA(MAIN) -> doA1 (opposite configuration)
		val result2 = pathService.reservePath("train2", zA, doA1)
		assertThat(result2).isNotNull()
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.MAIN)
		assertThat(vA.locked).isTrue()
	}
```

- [ ] **Step 2: Run the test and verify it passes**

```bash
./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.context.navigation.SwitchConfigurationTest*secondTrainReconfiguresSwitchAfterFirstCompletion"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/SwitchConfigurationTest.kt
git commit -m "test(#618-I1): verify second train reconfigures switch after first completion" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Full quality gate and final verification

**Files:**
- All files modified in previous tasks.

- [ ] **Step 1: Run the full test gate**

```bash
./gradlew clean build detekt ktlintCheck test integrationTest
```

- [ ] **Step 2: Run regression suites explicitly**

Switch/configuration tests:

```bash
./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.context.navigation.SwitchConfigurationTest"
```

Simulation/multi-train regression tests:

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.sim.DeadlockDetectionTest" --tests "cz.vutbr.fit.interlockSim.sim.ThreeTrainLoopTest" --tests "cz.vutbr.fit.interlockSim.sim.TwoTrainLoopTest" --tests "cz.vutbr.fit.interlockSim.sim.MultiTrainLoopTest"
```

```bash
./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.sim.ShuntingLoop*"
```

Expected: all green.

- [ ] **Step 3: Verify no production caller of `releasePath` was introduced**

Run:

```bash
grep -r "releasePath(" core/src/commonMain --include="*.kt" | grep -v "fun releasePath"
```

Expected: no matches from production code (only test files should call `releasePath`).

- [ ] **Step 4: Commit if any last fixes were needed**

If the gate required any changes, commit them. If the gate passed without changes, no additional commit is needed for this task.

---

### Task 7: Open PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/issue-618-train-completion-cleanup
```

- [ ] **Step 2: Open a PR to `develop`**

Use the PR template at `.github/PULL_REQUEST_TEMPLATE.md`. Title suggestion:

```
fix(#618): unlock switches in unregister (I1) + emit legacy BlockOccupancyEvent on release paths (I2)
```

Body should reference:
- Summary of I1 and I2 fixes
- Tests added
- Full gate command run
- Link to issue #618
- Note: do not auto-merge; wait for external review

---

## Self-Review Checklist

### Spec coverage

| Spec requirement | Implementing task |
|------------------|-------------------|
| I1: `unregister` unlocks switches + calls `registry.unregisterSwitches(trainId)` | Task 2 |
| I1 test #1 — switch unlock after `unregister` | Task 2 |
| I1 test #2 — switch unlock after `releaseTrainReservations` | Task 4 |
| I1 test #3 — second train reconfigures switch | Task 5 |
| I2-B: `unregister` emits legacy `BlockOccupancyEvent(BLOCK_RELEASED)` | Task 3 |
| I2-B: `unregisterBlock` emits legacy `BlockOccupancyEvent(BLOCK_RELEASED)` | Task 3 |
| I2 tests #4–#5 | Task 3 |
| Dual-channel helper used by all release paths | Task 1 + Task 3 |
| `./gradlew clean build detekt ktlintCheck test integrationTest` green | Task 6 |
| Regression suites green | Task 6 |
| No production caller of `releasePath` | Task 6 |

### Placeholder scan

- No "TBD", "TODO", "implement later".
- No vague "handle edge cases".
- Test code is concrete and uses existing test variables where possible.
- All referenced types and methods exist in the codebase.

### Type consistency

- `emitBlockReleased(block: DynamicTrackBlock, trainId: String, simTime: Double)` is used consistently.
- `BlockOccupancyEvent` field values (`previousState = RESERVED`, `newState = FREE`, `occupant = null`) match the spec and `releasePath`.
- `unregister` return type remains `List<DynamicTrackBlock>`.
- `unregisterBlock` return type remains `Boolean`.
