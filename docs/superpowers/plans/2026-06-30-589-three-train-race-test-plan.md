# #589 — Goal 1 SP6: 1000-iteration Three-Train Race Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `:core` JVM integration test (`ThreeTrainLoopRaceTest`) that runs the existing `ThreeTrainLoop` scenario on `vyhybna.xml` 1000 times and verifies stability, cleanup, and runtime consistency.

**Architecture:** The test mirrors existing `TwoTrainConcurrencyTest` / `TwoTrainLoopTest` patterns: a per-run `@RepeatedTest` collects results in a thread-safe companion holder, and a final aggregate `@Test` validates the full 1000-run statistics. No production code changes.

**Tech Stack:** Kotlin, JUnit 5, AssertK, Koin, kDisco, Gradle.

## Global Constraints

- Branch from `goal-1` into `feat/issue-589-three-train-race-test`.
- PR target: `goal-1`. Do **not** auto-merge; wait for external review.
- All changes are test-only in `:core/src/jvmTest/.../sim/`.
- Must pass `./gradlew clean build detekt ktlintCheck test integrationTest` before PR.
- `@Tag("integration-test")` required on the test class.
- Per-run timeout 30 s; aggregate timeout 120 s.
- Use existing `ThreeTrainLoop`, `TestFixtures.loadShuntingXml()`, and `SimulationContextFactory` from Koin.

---

## File Map

| File | Action | Responsibility |
|------|--------|--------------|
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/ThreeTrainLoopRaceTest.kt` | Create | The 1000-iteration race test. |
| `docs/superpowers/plans/2026-06-30-589-three-train-race-test-plan.md` | Already created (this file) | Plan reference. |

---

## Task 1: Create Branch

**Files:** none (git operation)

**Interfaces:** none

- [ ] **Step 1: Check out a fresh branch from `goal-1`**

```bash
git checkout goal-1
git pull origin goal-1
git checkout -b feat/issue-589-three-train-race-test
```

- [ ] **Step 2: Verify clean state**

```bash
git status
```

Expected: `On branch feat/issue-589-three-train-race-test`, no uncommitted changes.

- [ ] **Step 3: Commit marker (optional)**

No commit needed yet; the next task will create the first file.

---

## Task 2: Add `ThreeTrainLoopRaceTest.kt`

**Files:**
- Create: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/ThreeTrainLoopRaceTest.kt`

**Interfaces:**
- Consumes: `TestFixtures.loadShuntingXml(): InputStream`, `SimulationContextFactory.createContext(stream): Context<*, *>`, `DefaultSimulationContext.run()`, `ThreeTrainLoop`, `MultiTrainLoop` observability counters.
- Produces: `ThreeTrainLoopRaceTest` test class with `@RepeatedTest(1000)` and aggregate `@Test`.

- [ ] **Step 1: Write the test file**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP6: 1000-iteration deterministic race test for three-train scenario (Issue #589).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@Tag("integration-test")
@DisplayName("ThreeTrainLoop — 1000-iteration deterministic race test (Goal 1 SP6 #589)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreeTrainLoopRaceTest : KoinTestBase() {

    private data class RunResult(
        val wallMs: Long,
        val trainsEntered: Int,
        val trainsExited: Int,
        val maxConcurrentTrains: Int,
        val occupiedResources: Int
    )

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** Number of consecutive runs required to validate stability. */
        const val EXPECTED_RUNS: Int = 1000

        /** Simulation end time for each run. */
        const val END_TIME: Long = 600L

        /** Maximum acceptable wall-clock spread between fastest and slowest run (ms). */
        const val WALL_SPREAD_MS: Long = 2000L

        /** Maximum acceptable coefficient of variation for runtime stability. */
        const val MAX_CV: Double = 0.5

        /** Minimum observed peak concurrency to confirm trains actually contended. */
        const val MIN_CONCURRENT_TRAINS: Int = 2

        /** Thread-safe collector for per-run results across @RepeatedTest invocations. */
        private val results: ConcurrentLinkedQueue<RunResult> = ConcurrentLinkedQueue()
    }

    private val factory: SimulationContextFactory by inject()

    @BeforeAll
    fun clearResults() {
        results.clear()
    }

    /**
     * Single race run repeated 1000 times.
     *
     * Acceptance criteria per run:
     * - All 3 trains enter and exit.
     * - No kDisco Resource tokens remain occupied.
     * - Peak concurrent trains reaches at least 2 (contention/queuing occurred).
     * - Run completes within the per-run timeout → no deadlock.
     */
    @RepeatedTest(1000)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("ThreeTrainLoop run completes cleanly")
    fun eachRunCompletesCleanly() {
        val startNs = System.nanoTime()
        val context = TestFixtures.loadShuntingXml().use { stream ->
            factory.createContext(stream) as DefaultSimulationContext
        }
        context.use { ctx ->
            ctx.getInOuts()
            val process = ThreeTrainLoop(ctx, endTime = END_TIME)
            ctx.setMainProcess(process)
            ctx.run()
            val wallMs = (System.nanoTime() - startNs) / 1_000_000

            assertThat(process.getTrainsEntered(), name = "trains entered").isEqualTo(3)
            assertThat(process.getTrainsExited(), name = "trains exited").isEqualTo(3)
            assertThat(process.getOccupiedResourceCount(), name = "occupied resources").isZero()
            assertThat(process.getMaxConcurrentTrains(), name = "peak concurrent trains")
                .isGreaterThanOrEqualTo(MIN_CONCURRENT_TRAINS)

            results.add(
                RunResult(
                    wallMs = wallMs,
                    trainsEntered = process.getTrainsEntered(),
                    trainsExited = process.getTrainsExited(),
                    maxConcurrentTrains = process.getMaxConcurrentTrains(),
                    occupiedResources = process.getOccupiedResourceCount()
                )
            )
        }
    }

    /**
     * Aggregate validation after all 1000 repeated runs.
     *
     * Acceptance criteria:
     * - Exactly 1000 results recorded.
     * - Coefficient of variation of wall-clock runtimes stays below 0.5.
     * - Max-min wall-clock spread stays below 2000 ms.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("1000-run aggregate: statistics and runtime stability")
    fun aggregate1000RunStatistics() {
        assertThat(results.size, name = "recorded run count").isEqualTo(EXPECTED_RUNS)

        val wallTimes = results.map { it.wallMs }
        val minMs = wallTimes.min()
        val maxMs = wallTimes.max()
        val mean = wallTimes.average()
        val stdDev = sqrt(wallTimes.map { (it - mean) * (it - mean) }.average())
        val cv = if (mean > 0.0) stdDev / mean else 0.0

        logger.info {
            "ThreeTrainLoop 1000-run race complete: " +
                "runs=$EXPECTED_RUNS, " +
                "min=${minMs}ms, max=${maxMs}ms, mean=${mean}ms, " +
                "stdDev=${stdDev}ms, CV=$cv"
        }

        assertThat(cv, name = "coefficient of variation").isLessThan(MAX_CV)
        assertThat(maxMs - minMs, name = "wall-clock spread").isLessThan(WALL_SPREAD_MS)
    }
}
```

- [ ] **Step 2: Verify imports and syntax**

Run:

```bash
./gradlew :core:compileTestKotlin
```

Expected: BUILD SUCCESSFUL (or compile errors that you fix before continuing).

- [ ] **Step 3: Run the new test class in isolation**

```bash
./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.sim.ThreeTrainLoopRaceTest"
```

Expected: 1001 tests pass (1000 repeated + 1 aggregate). Note: this may take several minutes.

- [ ] **Step 4: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/ThreeTrainLoopRaceTest.kt
git commit -m "test(#589): add 1000-iteration ThreeTrainLoop race test" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Full Quality Gate

**Files:** none (verification)

**Interfaces:** none

- [ ] **Step 1: Run unit tests**

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run integration tests**

```bash
./gradlew :core:integrationTest
```

Expected: BUILD SUCCESSFUL. This is the suite that actually executes `ThreeTrainLoopRaceTest` because it is tagged `@Tag("integration-test")`.

- [ ] **Step 3: Run static analysis**

```bash
./gradlew detekt ktlintCheck
```

Expected: BUILD SUCCESSFUL. No detekt issues and no ktlint formatting violations.

- [ ] **Step 4: Run full build**

```bash
./gradlew clean build detekt ktlintCheck test integrationTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit if any fixes were needed**

If no fixes were needed, no extra commit is required. If you fixed detekt/ktlint issues, commit them with:

```bash
git commit -m "style(#589): fix detekt/ktlint findings in race test" -m "Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Open PR

**Files:** none (GitHub operation)

**Interfaces:** none

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/issue-589-three-train-race-test
```

- [ ] **Step 2: Create a PR to `goal-1`**

Use the PR template at `.github/PULL_REQUEST_TEMPLATE.md`. Title suggestion:

```
test(#589): 1000-iteration ThreeTrainLoop race test for Goal 1 SP6
```

Body should mention:
- Adds `ThreeTrainLoopRaceTest` with `@RepeatedTest(1000)`.
- Verifies 3 trains enter/exit, no resource leaks, peak concurrency ≥ 2, and runtime stability.
- All quality gates (`./gradlew clean build detekt ktlintCheck test integrationTest`) passed locally.
- Does not modify production code.

- [ ] **Step 3: Do not merge**

Wait for external review before merging. Do not use auto-merge.

---

## Self-Review Checklist

1. **Spec coverage:**
   - 1000 runs → Task 2 `@RepeatedTest(1000)`.
   - Per-run completion/cleanup/concurrency checks → Task 2 assertions.
   - Aggregate statistics → Task 2 `aggregate1000RunStatistics`.
   - No production code changes → only one new test file.
   - Branch/PR/do-not-merge → Task 1 and Task 4.
2. **Placeholder scan:** None. All code, commands, and expected outputs are concrete.
3. **Type consistency:** `RunResult`, `ConcurrentLinkedQueue`, and method names are consistent throughout.
