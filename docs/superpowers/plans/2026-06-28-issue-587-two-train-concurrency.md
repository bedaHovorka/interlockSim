# Issue #587: Two-Train Concurrency Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify the foundational multi-train behaviour via a dedicated `TwoTrainConcurrencyTest` suite: both trains complete routes without deadlock, block-transition ordering is consistent, and same-step arrival does not throw — each assertion verified over 100 consecutive runs via `@RepeatedTest(100)`.

**Architecture:** Three `@RepeatedTest(100)` methods in a single `@Tag("integration-test")` class extending `KoinTestBase`. Uses `MultiTrainLoop` on `TestTopologies.linearPathWithSemaphoreSimulation`. Event ordering is validated by collecting `ContextChangeEvent`s via `addPropertyChangeListener` — no dependency on `#569`'s `onBlockEvent` API. Each test creates a fresh context.

**Tech Stack:** Kotlin/JVM, JUnit 5 (`@RepeatedTest`, `@Timeout`), AssertK, Koin (via `KoinTestBase`), `MultiTrainLoop`, `TestTopologies`

## Global Constraints

- Worktree root: `/home/beda/work/interlockSim/.claude/worktrees/agent-a5a0792b0b9cc28e6`
- Branch: `feat/issue-587-two-train-concurrency-validation`
- Base branch: `goal-1`
- **No kdisco changes.** This plan is fully independent of #569.
- Tests go in `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/`
- Quality gate before PR: `./gradlew :core:test :core:integrationTest detekt ktlintCheck`
- Do not push or create PRs; the coordinator handles that
- `@RepeatedTest(100)` + `@Timeout(30, TimeUnit.SECONDS)` per repetition keeps CI runtime bounded

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt` | Create | All three `@RepeatedTest(100)` scenarios |

---

## Task 1: Implement TwoTrainConcurrencyTest

**Files:**
- Create: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt`

**Interfaces:**
- Consumes: `MultiTrainLoop`, `MultiTrainLoop.TrainSpec`
- Consumes: `TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)` — returns `SimulationContext` with InOuts `"A"` and `"B"`
- Consumes: `DefaultSimulationContext.addPropertyChangeListener(ContextPropertyChangeListener)` for log collection
- Consumes: `SimulationContext.ReportType.TRAIN_APPROVED`, `ReportType.TRAIN_EVENTS`
- Produces: 300 test cases (3 methods × 100 repetitions) verifying completion, ordering, and same-step robustness

- [ ] **Step 1: Verify baseline tests pass**

```bash
cd /home/beda/work/interlockSim/.claude/worktrees/agent-a5a0792b0b9cc28e6
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Create TwoTrainConcurrencyTest.kt**

Create `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt`:

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP5: Two-train concurrency validation (Issue #587).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Tag("integration-test")
@DisplayName("TwoTrainConcurrencyTest — Goal 1 SP5 (#587)")
class TwoTrainConcurrencyTest : KoinTestBase() {

    private var context: DefaultSimulationContext? = null

    @AfterEach
    fun closeContext() {
        context?.close()
        context = null
    }

    /** Creates a fresh linear-with-semaphore context for each test repetition. */
    private fun newLinearContext(): DefaultSimulationContext {
        val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
            as DefaultSimulationContext
        ctx.getInOuts()
        context = ctx
        return ctx
    }

    // ------------------------------------------------------------------
    // Test 1: both trains complete without deadlock (100 runs)
    // ------------------------------------------------------------------

    /**
     * Acceptance criteria:
     * - Both trains enter and exit.
     * - No occupied resources (kDisco Resource tokens) remain after run.
     * - Run completes within timeout → no deadlock.
     */
    @RepeatedTest(100)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Both trains complete without deadlock")
    fun bothTrainsCompleteWithoutDeadlock() {
        val ctx = newLinearContext()
        val process = MultiTrainLoop(
            ctx,
            endTime = 600L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 1.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        assertThat(process.getTrainsEntered()).isEqualTo(2)
        assertThat(process.getTrainsExited()).isEqualTo(2)
        assertThat(process.getOccupiedResourceCount()).isZero()
    }

    // ------------------------------------------------------------------
    // Test 2: block-transition ordering is consistent (100 runs)
    // ------------------------------------------------------------------

    /**
     * Acceptance criteria:
     * - Every TRAIN_APPROVED message (route announcement) appears in the log
     *   before the corresponding "ends" message for the same train.
     * - Two trains approved → two trains ended, in a consistent order.
     */
    @RepeatedTest(100)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("TRAIN_APPROVED precedes ends for each train (100 runs)")
    fun trainApprovedPrecedesEndsForEachTrain() {
        val ctx = newLinearContext()

        // Collect all TRAIN_APPROVED and TRAIN_EVENTS messages in arrival order.
        // CopyOnWriteArrayList not needed (single sim thread), but clarifies intent.
        data class LogEntry(val type: ReportType, val message: String)
        val log = mutableListOf<LogEntry>()

        ctx.addPropertyChangeListener(
            ContextPropertyChangeListener { event ->
                when (event.propertyName) {
                    ReportType.TRAIN_APPROVED.name ->
                        log.add(LogEntry(ReportType.TRAIN_APPROVED, event.newValue?.toString() ?: ""))
                    ReportType.TRAIN_EVENTS.name ->
                        log.add(LogEntry(ReportType.TRAIN_EVENTS, event.newValue?.toString() ?: ""))
                }
            }
        )

        val process = MultiTrainLoop(
            ctx,
            endTime = 600L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 1.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run()

        val approvedEntries = log.filter { it.type == ReportType.TRAIN_APPROVED }
        val endsEntries = log.filter { it.type == ReportType.TRAIN_EVENTS && it.message.contains("ends") }

        // Both trains must have been approved and must have ended
        assertThat(approvedEntries.size).isEqualTo(2)
        assertThat(endsEntries.size).isEqualTo(2)

        // All approval events must appear before all "ends" events in the log
        val lastApprovalIdx = log.indexOfLast { it.type == ReportType.TRAIN_APPROVED }
        val firstEndsIdx = log.indexOfFirst { it.type == ReportType.TRAIN_EVENTS && it.message.contains("ends") }
        assertThat(firstEndsIdx).isGreaterThan(lastApprovalIdx)
    }

    // ------------------------------------------------------------------
    // Test 3: same-step arrival does not cause exception (100 runs)
    // ------------------------------------------------------------------

    /**
     * Acceptance criteria:
     * - Both trains injected at inTime = 0.0 (simultaneous arrival edge case).
     * - Both complete; no exception thrown; no resources leaked.
     * This exercises the scheduler tie-breaking path where two activations
     * land in the same kDisco event slot.
     */
    @RepeatedTest(100)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Same-step arrival (inTime=0.0 for both) completes without exception")
    fun sameStepArrivalNoException() {
        val ctx = newLinearContext()
        val process = MultiTrainLoop(
            ctx,
            endTime = 600L,
            trainSpecs = listOf(
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
                MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
            ),
            maxConcurrentTrains = 10
        )
        ctx.setMainProcess(process)
        ctx.run() // must not throw

        assertThat(process.getTrainsEntered()).isEqualTo(2)
        assertThat(process.getTrainsExited()).isEqualTo(2)
        assertThat(process.getOccupiedResourceCount()).isZero()
    }
}
```

- [ ] **Step 3: Run just the new test class once to verify it works**

```bash
./gradlew :core:integrationTest --tests "cz.vutbr.fit.interlockSim.sim.TwoTrainConcurrencyTest"
```

Expected: BUILD SUCCESSFUL. All three test methods pass (300 repetitions total).
Watch for: any `@Timeout` failure or assertion failure in the repetition output.

If Test 1 or Test 3 times out: the simulation is deadlocking. Investigate with `--info` flag to see which step hangs.

If Test 2 fails: the log entries are not in the expected order — check that `ctx.addPropertyChangeListener` is being called before `ctx.setMainProcess` / `ctx.run()`.

- [ ] **Step 4: Run full test suite**

```bash
./gradlew :core:test :core:integrationTest
```

Expected: BUILD SUCCESSFUL, all tests green including the 300 new repetitions.

- [ ] **Step 5: Run quality checks**

```bash
./gradlew :core:detekt :core:ktlintCheck
```

Expected: BUILD SUCCESSFUL. Fix any reported issues.

- [ ] **Step 6: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt
git commit -m "test(#587): TwoTrainConcurrencyTest — 100-run deadlock, ordering, same-step validation"
```

- [ ] **Step 7: Final quality gate**

```bash
./gradlew clean build detekt ktlintCheck :core:test :core:integrationTest
```

Expected: BUILD SUCCESSFUL.
