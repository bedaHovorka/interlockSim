# #591 Scale Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a multiplatform commonTest integration test class that validates 5-train correctness and 20-train performance on `praha-hlavni-nadrazi.xml` using the existing `MultiTrainLoop`.

**Architecture:** A single new test file in `:core/src/commonTest` plus a one-line fixture constant in `:core-test`. The test loads the Praha XML, builds a `DefaultSimulationContext`, runs `MultiTrainLoop` with deterministic rosters, and asserts completion, resource cleanup, and real-time ratio.

**Tech Stack:** Kotlin Multiplatform, kotlin.test, AssertK, Koin, kDisco, Gradle.

## Global Constraints

- Branch: `feat/issue-591-scale-validation` from `goal-1`.
- PR target: `goal-1`.
- No production code changes; only `:core-test` test infrastructure and `:core` commonTest code.
- No real-time sync; measure raw wall-clock real-time ratio.
- All changes must pass `./gradlew clean build detekt ktlintCheck test integrationTest :core:linuxX64Test`.
- Do not auto-merge the PR; wait for external review.
- Follow project style: tabs, max line length 120, LF line endings.
- Use `CommonKoinTestBase` for Koin lifecycle in commonTest.
- Use `currentTimeMillisKMP()` (existing multiplatform time helper) for wall-clock measurement.

---

## File Map

| File | Responsibility |
|------|----------------|
| `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/NetworkResources.kt` | Add `PRAHA_HLAVNI_NADRAZI_XML` lazy constant so commonTest can load the fixture. |
| `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt` | New integration test class with 5-train completeness test and 20-train stress test. |

---

### Task 1: Expose Praha XML fixture to commonTest

**Files:**
- Modify: `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/NetworkResources.kt`

**Interfaces:**
- Consumes: existing `fixture(name: String)` private helper and `Resources.read`.
- Produces: `NetworkResources.PRAHA_HLAVNI_NADRAZI_XML: String`.

- [ ] **Step 1: Add the Praha XML constant**

Add the following line after the `TWO_TRACKS_PARALLEL_XML` constant in `NetworkResources`:

```kotlin
/** Praha Hlavní Nádraží — large station topology for scale validation. */
val PRAHA_HLAVNI_NADRAZI_XML: String by lazy { fixture("praha-hlavni-nadrazi.xml") }
```

- [ ] **Step 2: Verify the file compiles**

Run:

```bash
./gradlew :core-test:compileCommonMainKotlinMetadata
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/NetworkResources.kt
git commit -m "test(#591): expose praha-hlavni-nadrazi.xml fixture to commonTest"
```

---

### Task 2: Write the 5-train completeness test

**Files:**
- Create: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt`

**Interfaces:**
- Consumes: `NetworkResources.PRAHA_HLAVNI_NADRAZI_XML`, `CommonTestFixtures.parseSimulationContext`, `SimulationProcessFactory`, `MultiTrainLoop`, `currentTimeMillisKMP`.
- Produces: `MultiTrainScaleValidationTest.fiveTrainCompleteness()` passing.

- [ ] **Step 1: Create the test class skeleton**

Create the file with this content. It defines the class, constants, helper functions, and the 5-train test only. The 20-train test is added in Task 3.

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP7: 5-train correctness and 20-train performance scale validation (#591).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import kotlin.test.Test

/**
 * Scale validation for Goal 1 multi-train simulation.
 *
 * Runs the existing [MultiTrainLoop] on the existing Praha fixture to verify
 * 5-train correctness and 20-train performance on a topology large enough to
 * avoid trivial contention.
 */
class MultiTrainScaleValidationTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		private const val FIVE_TRAIN_END_TIME: Long = 400L
		private const val HEADWAY_SECONDS: Double = 5.0
		private const val TRAIN_LENGTH: Double = 40.0
		private const val MAX_CONCURRENT_TRAINS: Int = 20

		private val NORTH_ENTRIES = listOf("N-Lib-1", "N-Lib-2", "N-Vys-1", "N-Vys-2", "N-Bypass")
		private val SOUTH_EXITS = listOf("S-Vin-1", "S-Vin-2", "S-Vrs-1", "S-Vrs-2", "S-Vrs-3", "S-Bypass")

		private fun fiveTrainSpecs(): List<MultiTrainLoop.TrainSpec> =
			listOf(
				MultiTrainLoop.TrainSpec("N-Lib-1", "S-Vin-1", 0.0, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Lib-2", "S-Vin-2", HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-1", "S-Vrs-1", 2 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Vys-2", "S-Vrs-2", 3 * HEADWAY_SECONDS, TRAIN_LENGTH),
				MultiTrainLoop.TrainSpec("N-Bypass", "S-Bypass", 4 * HEADWAY_SECONDS, TRAIN_LENGTH)
			)
	}

	private val processFactory: SimulationProcessFactory by inject()

	private fun loadPrahaContext(): DefaultSimulationContext {
		val simCtx = CommonTestFixtures.parseSimulationContext(
			NetworkResources.PRAHA_HLAVNI_NADRAZI_XML,
			processFactory
		) as DefaultSimulationContext
		simCtx.getInOuts() // initialize dynamic wrappers
		return simCtx
	}

	private data class ScenarioResult(
		val wallSeconds: Double,
		val realTimeRatio: Double,
		val trainsEntered: Int,
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		val occupiedResources: Int
	)

	private fun runScenario(
		endTime: Long,
		specs: List<MultiTrainLoop.TrainSpec>
	): ScenarioResult {
		val context = loadPrahaContext()
		context.use { ctx ->
			val startMs = currentTimeMillisKMP()
			val process = MultiTrainLoop(
				ctx,
				endTime = endTime,
				trainSpecs = specs,
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS
			)
			ctx.setMainProcess(process)
			ctx.run()
			val wallSeconds = (currentTimeMillisKMP() - startMs) / 1000.0
			val realTimeRatio = endTime / wallSeconds
			return ScenarioResult(
				wallSeconds = wallSeconds,
				realTimeRatio = realTimeRatio,
				trainsEntered = process.getTrainsEntered(),
				trainsExited = process.getTrainsExited(),
				maxConcurrentTrains = process.getMaxConcurrentTrains(),
				occupiedResources = process.getOccupiedResourceCount()
			)
		}
	}

	@Test
	fun fiveTrainCompleteness() {
		val result = runScenario(FIVE_TRAIN_END_TIME, fiveTrainSpecs())
		logger.info {
			"5-train Praha correctness: " +
				"entered=${result.trainsEntered}, exited=${result.trainsExited}, " +
				"maxConcurrent=${result.maxConcurrentTrains}, occupied=${result.occupiedResources}, " +
				"wall=${result.wallSeconds}s, ratio=${result.realTimeRatio}"
		}
		assertThat(result.trainsEntered, name = "trains entered").isEqualTo(5)
		assertThat(result.trainsExited, name = "trains exited").isEqualTo(5)
		assertThat(result.occupiedResources, name = "occupied resources").isZero()
	}
}
```

- [ ] **Step 2: Verify the test compiles**

Run:

```bash
./gradlew :core:compileCommonTestKotlinMetadata
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the 5-train test on JVM**

Run:

```bash
./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.sim.MultiTrainScaleValidationTest.fiveTrainCompleteness"
```

Expected: the test passes and logs show 5 trains entered/exited.

If it fails because some north→south InOut pair has no path, note the failing pair and adjust the roster in Task 4.

- [ ] **Step 4: Commit**

```bash
git add core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt
git commit -m "test(#591): add 5-train Praha correctness validation"
```

---

### Task 3: Add the 20-train stress test

**Files:**
- Modify: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt`

**Interfaces:**
- Consumes: existing `runScenario`, `ScenarioResult`, constants, and helpers.
- Produces: `MultiTrainScaleValidationTest.twentyTrainStress()` passing.

- [ ] **Step 1: Add stress constants and roster helper**

Inside the `private companion object`, add:

```kotlin
private const val TWENTY_TRAIN_END_TIME: Long = 1200L
private const val STRESS_RUNS: Int = 10
private const val MIN_REAL_TIME_RATIO: Double = 1.0

private fun twentyTrainSpecs(): List<MultiTrainLoop.TrainSpec> {
	val specs = mutableListOf<MultiTrainLoop.TrainSpec>()
	repeat(20) { i ->
		val entry = NORTH_ENTRIES[i % NORTH_ENTRIES.size]
		val exit = SOUTH_EXITS[i % SOUTH_EXITS.size]
		specs.add(
			MultiTrainLoop.TrainSpec(
				entry,
				exit,
				i * HEADWAY_SECONDS,
				TRAIN_LENGTH
			)
		)
	}
	return specs
}
```

- [ ] **Step 2: Add the 20-train stress test method**

Add this method to the class:

```kotlin
@Test
fun twentyTrainStress() {
	val results = mutableListOf<ScenarioResult>()
	repeat(STRESS_RUNS) { runIndex ->
		val result = runScenario(TWENTY_TRAIN_END_TIME, twentyTrainSpecs())
		results.add(result)
		logger.info {
			"20-train Praha stress run ${runIndex + 1}/$STRESS_RUNS: " +
				"entered=${result.trainsEntered}, exited=${result.trainsExited}, " +
				"maxConcurrent=${result.maxConcurrentTrains}, occupied=${result.occupiedResources}, " +
				"wall=${result.wallSeconds}s, ratio=${result.realTimeRatio}"
		}
		assertThat(result.trainsEntered, name = "trains entered").isEqualTo(20)
		assertThat(result.trainsExited, name = "trains exited").isEqualTo(20)
		assertThat(result.occupiedResources, name = "occupied resources").isZero()
		assertThat(result.realTimeRatio, name = "real-time ratio")
			.isGreaterThanOrEqualTo(MIN_REAL_TIME_RATIO)
	}

	val ratios = results.map { it.realTimeRatio }
	logger.info {
		"20-train Praha stress aggregate: " +
			"runs=$STRESS_RUNS, minRatio=${ratios.minOrNull()}, " +
			"meanRatio=${ratios.average()}, maxRatio=${ratios.maxOrNull()}"
	}
}
```

- [ ] **Step 3: Run the stress test once locally**

Run:

```bash
./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.sim.MultiTrainScaleValidationTest.twentyTrainStress"
```

This may take several minutes. Expected: the test passes and logs 10 runs.

- [ ] **Step 4: Commit**

```bash
git add core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt
git commit -m "test(#591): add 20-train Praha stress and real-time ratio assertions"
```

---

### Task 4: Tune end times and roster if needed

**Files:**
- Modify: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt`

**Interfaces:**
- Consumes: results from Task 2 and Task 3 local runs.
- Produces: final constants that make both tests reliably pass on the development machine.

- [ ] **Step 1: Inspect logs from Task 2 and Task 3**

Check whether all expected trains entered and exited. If some trains did not finish, raise the corresponding `FIVE_TRAIN_END_TIME` or `TWENTY_TRAIN_END_TIME`.

Recommended tuning increments:
- 5-train case: try `600L` then `900L`.
- 20-train case: try `1800L` then `2400L`.

- [ ] **Step 2: Adjust roster if paths are missing**

If logs show a specific north→south pair has no path, replace that pair with a known-good pair from the 30 possible combinations. Do not redesign the topology.

- [ ] **Step 3: Re-run both tests after tuning**

Run:

```bash
./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.sim.MultiTrainScaleValidationTest"
```

Expected: both tests pass.

- [ ] **Step 4: Commit any tuning changes**

```bash
git add core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt
git commit -m "test(#591): tune end times and roster after first local runs"
```

---

### Task 5: Run native linuxX64 tests

**Files:**
- None (verification only).

- [ ] **Step 1: Run the commonTest subset on linuxX64**

Run:

```bash
./gradlew :core:linuxX64Test
```

Expected: the build succeeds. The new test is included automatically because it is in `commonTest`.

If the native test fails for a reason unrelated to this change (e.g., missing toolchain), document it in the PR and ensure the JVM path is green.

- [ ] **Step 2: Commit if only verification**

If no code changes are needed, no additional commit is required.

---

### Task 6: Full quality gate

**Files:**
- None (verification only).

- [ ] **Step 1: Run detekt, ktlint, and the full test suite**

Run:

```bash
./gradlew clean build detekt ktlintCheck test integrationTest :core:linuxX64Test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Fix any style or test failures**

If ktlint or detekt reports issues, run:

```bash
./gradlew ktlintFormat
```

Then re-run the quality gate.

- [ ] **Step 3: Commit any fixes**

```bash
git add .
git commit -m "style(#591): fix ktlint/detekt findings"
```

---

### Task 7: Open PR to goal-1

**Files:**
- None (process step).

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/issue-591-scale-validation
```

- [ ] **Step 2: Open a PR using the project template**

Use `.github/PULL_REQUEST_TEMPLATE.md`. Fill in:

- Summary: test-only scale validation for #591.
- Related Issues: Closes #591.
- Changes Made:
  - Expose `praha-hlavni-nadrazi.xml` to commonTest via `NetworkResources`.
  - Add `MultiTrainScaleValidationTest` with 5-train and 20-train scenarios.
- Test Coverage: two new integration tests; no production code, so coverage impact is neutral.
- CI/CD Status: link to local quality gate output.
- Code Quality: detekt and ktlint passing.
- Breaking Changes: none.
- Documentation: design spec already in `docs/superpowers/specs/2026-06-30-591-scale-validation-design.md`.
- Checklist: mark relevant items.

- [ ] **Step 3: Do not auto-merge**

Wait for external review per project memory.

---

## Self-Review

### Spec coverage

| Spec requirement | Task |
|---|---|
| 5-train scenario completes all routes | Task 2 |
| 20-train stress scenario runs at ratio >= 1x | Task 3 |
| 10 consecutive 20-train runs | Task 3 (`STRESS_RUNS = 10`) |
| Per-run and aggregate metrics logged | Task 2, Task 3 |
| Test runs on JVM and linuxX64 | commonTest location + Task 5 |
| Quality gates pass | Task 6 |
| PR to `goal-1`, no auto-merge | Task 7 |

### Placeholder scan

No TBD, TODO, or vague steps remain. Each step contains exact code, commands, and expected outcomes.

### Type consistency

- Uses `currentTimeMillisKMP()` (existing multiplatform helper) rather than a non-existent `PlatformTime.nanoTime()`.
- `MultiTrainLoop.TrainSpec` constructor signature matches existing code: `(inName, outName, inTime, length)`.
- `runScenario` returns `ScenarioResult` consumed by both test methods.
