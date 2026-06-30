# Design Spec: #589 — Goal 1 SP6: 1000-iteration Three-Train Race Test

**Date:** 2026-06-30
**Issue:** [#589 — Goal 1 SP6: Run 1000-iteration deterministic race test for three-train scenario](https://github.com/bedaHovorka/interlockSim/issues/589)
**Branch:** `feat/issue-589-three-train-race-test`
**PR target:** `goal-1`

---

## 1. Summary

This spec adds a new integration test class, `ThreeTrainLoopRaceTest`, in the `:core` module that runs the existing `ThreeTrainLoop` scenario on `vyhybna.xml` 1000 times. The test verifies the multi-train simulation foundation is stable under repeated concurrency stress and records pass/fail and runtime statistics.

The work is intentionally a **test-only addition**: no production code changes. It follows the existing `TwoTrainLoopTest` / `TwoTrainConcurrencyTest` patterns and reuses `ThreeTrainLoop` introduced in Goal 1 SP4 (#584).

---

## 2. Acceptance Criteria

- 1000 consecutive runs complete without exception or deadlock.
- Every run: all 3 trains enter and exit.
- Every run: no occupied kDisco `Resource` tokens remain after `run()` returns.
- Every run: peak concurrent trains reaches at least 2 (confirms contention/queuing occurred).
- Aggregate: wall-clock runtime stays stable across all 1000 runs.
  - Coefficient of variation < 0.5.
  - Max-min wall-clock spread < 2000 ms.
- Aggregate runtime statistics are logged once at suite end.

---

## 3. Background and Context

### 3.1 Existing building blocks

- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ThreeTrainLoop.kt`
  - Fixed 3-train scenario on `vyhybna.xml`:
    - Train 1: A → B, inTime = 0.0
    - Train 2: B → A, inTime = 1.0
    - Train 3: A → B, inTime = 2.0
  - Extends `MultiTrainLoop`, inheriting `getTrainsEntered()`, `getTrainsExited()`, `getMaxConcurrentTrains()`, and `getOccupiedResourceCount()`.
- `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainLoopTest.kt`
  - Uses a single `@Test` with internal `repeat(100)` for determinism/runtime validation.
- `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/TwoTrainConcurrencyTest.kt`
  - Uses `@RepeatedTest(100)` with per-run assertions.
- `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/testutil/KoinTestBase.kt`
  - Base class that starts/stops Koin for each test.
- `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/TestFixtures.kt`
  - Provides `loadShuntingXml()` for loading `vyhybna.xml`.

### 3.2 Why test-only

Issue #589 explicitly asks for a 1000-iteration race test. The production scenario class already exists; the only deliverable is the test that exercises it at scale and records statistics.

---

## 4. Design

### 4.1 Test class

**File:** `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/ThreeTrainLoopRaceTest.kt`

```kotlin
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@Tag("integration-test")
@DisplayName("ThreeTrainLoop — 1000-iteration deterministic race test (Goal 1 SP6 #589)")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
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

        /** Shared holder for per-run results across @RepeatedTest invocations. */
        private val results: ConcurrentLinkedQueue<RunResult> = ConcurrentLinkedQueue()

        const val EXPECTED_RUNS: Int = 1000
        const val END_TIME: Long = 600L
        const val WALL_SPREAD_MS: Long = 2000L
        const val MAX_CV: Double = 0.5
        const val MIN_CONCURRENT_TRAINS: Int = 2
    }

    @BeforeAll
    fun clearResults() {
        results.clear()
    }

    @Order(1)
    @RepeatedTest(1000)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("ThreeTrainLoop run completes cleanly")
    fun eachRunCompletesCleanly() {
        val startNs = System.nanoTime()
        val context =
            TestFixtures.loadShuntingXml().use { stream ->
                factory.createContext(stream) as DefaultSimulationContext
            }
        context.use { ctx ->
            // Initialize InOut elements before running the scenario.
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

    /** Aggregate validation after all 1000 repeated runs have completed. */
    @Order(2)
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

### 4.2 Design decisions

- **@RepeatedTest(1000) per-run method + final aggregate `@Test`**: Gives per-run reporting in JUnit/IDE plus a clean place for aggregate statistics. A `ConcurrentLinkedQueue` safely collects results across repeated invocations.
- **`@TestMethodOrder(OrderAnnotation::class)` + `@Order`**: Ensures the aggregate `@Test` runs only after all 1000 repetitions have completed and recorded their results.
- **`@TestInstance(PER_CLASS)`**: Optional; keeps `results` logically scoped to the test instance. The companion holder is thread-safe either way.
- **No deterministic transition comparison**: Per discussion, we intentionally do not compare transition sequences across runs; we only assert completion, cleanup, concurrency, and runtime stability.
- **No production code changes**: `ThreeTrainLoop` and `MultiTrainLoop` observability counters are already sufficient.

### 4.3 End-time selection

`endTime = 600L` is the same value used in `TwoTrainConcurrencyTest` for two 20 m trains. The three `ThreeTrainLoop` trains are 40 m and enter at t = 0, 1, 2, so 600 simulation seconds should be ample. If the first local run shows trains not finishing, we will bump `END_TIME` to `900L` during implementation.

### 4.4 Context factory lookup

The test uses Koin property injection, consistent with other `:core` JVM integration tests that extend `KoinTestBase`:

```kotlin
private val factory: SimulationContextFactory by inject()
```

### 4.5 Timeout rationale

- Per-run timeout 30 s: a single failing/deadlocked run surfaces quickly without blocking the entire suite.
- Aggregate timeout 120 s: the aggregate method only computes statistics; 120 s is generous.

---

## 5. Tests

### 5.1 New test file

- `ThreeTrainLoopRaceTest.kt` with two test methods as shown above.

### 5.2 Existing tests to keep green

- `TwoTrainLoopTest`
- `TwoTrainConcurrencyTest`
- `ShuntingLoop*` suite
- `PathReservationServiceTest` (integration-tagged)
- `PathReservationRegistryTest` (integration-tagged)
- Full `./gradlew test integrationTest`

---

## 6. Error Handling and Backward Compatibility

- This is a test-only addition; no public API changes.
- The per-run timeout prevents a single deadlock from hanging CI.
- `ctx.use { ... }` ensures every context is closed even if assertions throw.
- Koin lifecycle is handled by `KoinTestBase`.

---

## 7. Build / Quality Gates

The change must pass:

```bash
./gradlew clean build detekt ktlintCheck test integrationTest
```

---

## 8. Branch and PR Plan

1. Create branch `feat/issue-589-three-train-race-test` from `goal-1`.
2. Add `ThreeTrainLoopRaceTest.kt`.
3. Run the full quality gate locally.
4. Open a PR to `goal-1` with a description following `.github/PULL_REQUEST_TEMPLATE.md`.
5. Do **not** auto-merge; wait for external review (per project memory).

---

## 9. Acceptance Criteria Checklist

- [ ] New `ThreeTrainLoopRaceTest` added in `:core/src/jvmTest/.../sim/`.
- [ ] 1000 runs complete via `@RepeatedTest(1000)`.
- [ ] Per-run assertions: 3 entered, 3 exited, 0 occupied resources, peak concurrency ≥ 2.
- [ ] Aggregate test asserts 1000 results recorded and runtime stability (CV < 0.5, spread < 2000 ms).
- [ ] `./gradlew clean build detekt ktlintCheck test integrationTest` green.
- [ ] PR opened to `goal-1`; not auto-merged.
