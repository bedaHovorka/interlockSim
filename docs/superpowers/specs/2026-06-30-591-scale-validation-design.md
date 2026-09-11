# Design Spec: #591 — Goal 1 SP7: Scale Validation for 5-Train Correctness and 20-Train Performance

**Date:** 2026-06-30
**Issue:** [#591 — Goal 1 SP7: Scale validation for 5-train correctness and 20-train performance](https://github.com/bedaHovorka/interlockSim/issues/591)
**Branch:** `feat/issue-591-scale-validation`
**PR target:** `goal-1`

---

## 1. Summary

This spec adds a single multiplatform integration test class, `MultiTrainScaleValidationTest`, in the `:core` module. The test exercises the existing `MultiTrainLoop` deterministic dispatcher on the existing `praha-hlavni-nadrazi.xml` fixture to confirm the Goal 1 success criteria for scale and performance:

- **5-train correctness case:** one deterministic run asserting all trains complete and release resources.
- **20-train stress case:** 10 consecutive runs measuring the raw wall-clock real-time ratio, asserting no deadlocks/livelocks and that the simulation runs at least as fast as real time.

The work is intentionally **test-only**: no new production scenario classes and no XML topology changes. A small `NetworkResources` extension is required to expose the Praha fixture to commonTest.

---

## 2. Acceptance Criteria

- A 5-train scenario on `praha-hlavni-nadrazi.xml` completes all routes with no exceptions or occupancy errors.
- A 20-train stress scenario on `praha-hlavni-nadrazi.xml` runs at raw real-time ratio >= 1x.
- No deadlocks or livelocks are observed in 10 consecutive 20-train runs.
- Per-run metrics (wall time, real-time ratio, entered/exited counts, peak concurrency, occupied resources) are logged.
- Aggregate metrics (min/mean/max real-time ratio) are logged after the stress loop.
- `./gradlew clean build detekt ktlintCheck test integrationTest` stays green.
- The test runs on both JVM and linuxX64 because it lives in `:core/src/commonTest`.

---

## 3. Background and Context

### 3.1 Existing building blocks

- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainLoop.kt`
  - Deterministic multi-train dispatcher using kDisco `Resource` setup-time gating and `PathReservationService` ownership.
  - Accepts caller-supplied `TrainSpec` list, `endTime`, `maxConcurrentTrains`, and `enableRealTimeSync`.
  - Provides observability counters: `getTrainsEntered()`, `getTrainsExited()`, `getMaxConcurrentTrains()`, `getOccupiedResourceCount()`.
- `core-test/src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/praha-hlavni-nadrazi.xml`
  - Hand-tuned Praha main station topology.
  - 11 InOuts (5 north entries, 6 south exits), 50 switches, 37 signals, 117 track blocks.
- `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/NetworkResources.kt`
  - Multiplatform fixture loader via `Resources.read`.
  - Currently exposes `VYHYBNA_XML`, `LINEAR_TRACK_XML`, etc. but **not** Praha.
- `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonKoinTestBase.kt`
  - Multiplatform Koin test base used by `:core/src/commonTest`.
- `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonTestFixtures.kt`
  - `parseSimulationContext(xml, processFactory)` for building a `DefaultSimulationContext` from XML in commonTest.

### 3.2 Why test-only

Issue #591 explicitly asks for scale validation, not new user-facing scenarios. The production machinery (`MultiTrainLoop`) already supports arbitrary train rosters and topologies. Adding dedicated `FiveTrainLoop` / `TwentyTrainLoop` classes would create production code for what is fundamentally a validation artifact. Keeping the work in a commonTest integration test makes it multiplatform, focused, and low-risk.

---

## 4. Design

### 4.1 NetworkResources extension

Add a Praha XML constant to `NetworkResources` so commonTest can load the fixture without duplicating the path string.

```kotlin
/** Praha Hlavní Nádraží — large station topology for scale validation. */
val PRAHA_HLAVNI_NADRAZI_XML: String by lazy { fixture("praha-hlavni-nadrazi.xml") }
```

This is the only production-adjacent change; it is in the `:core-test` module (test infrastructure, not production code).

### 4.2 Test class

**File:** `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt`

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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.PlatformTime
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
        private const val TWENTY_TRAIN_END_TIME: Long = 1200L
        private const val HEADWAY_SECONDS: Double = 5.0
        private const val TRAIN_LENGTH: Double = 40.0
        private const val MAX_CONCURRENT_TRAINS: Int = 20
        private const val STRESS_RUNS: Int = 10
        private const val MIN_REAL_TIME_RATIO: Double = 1.0

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

        private fun twentyTrainSpecs(): List<MultiTrainLoop.TrainSpec> {
            val specs = mutableListOf<MultiTrainLoop.TrainSpec>()
            repeat(STRESS_RUNS * 2) { i ->
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

    private fun runScenario(
        endTime: Long,
        specs: List<MultiTrainLoop.TrainSpec>
    ): ScenarioResult {
        val context = loadPrahaContext()
        context.use { ctx ->
            val startNs = PlatformTime.nanoTime()
            val process = MultiTrainLoop(
                ctx,
                endTime = endTime,
                trainSpecs = specs,
                maxConcurrentTrains = MAX_CONCURRENT_TRAINS
            )
            ctx.setMainProcess(process)
            ctx.run()
            val wallSeconds = (PlatformTime.nanoTime() - startNs) / 1_000_000_000.0
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

    private data class ScenarioResult(
        val wallSeconds: Double,
        val realTimeRatio: Double,
        val trainsEntered: Int,
        val trainsExited: Int,
        val maxConcurrentTrains: Int,
        val occupiedResources: Int
    )
}
```

### 4.3 Design decisions

- **commonTest location:** Runs on JVM and linuxX64. `PlatformTime.nanoTime()` is the existing multiplatform time source.
- **No real-time sync:** The simulation runs as fast as the CPU allows. The real-time ratio is `simSeconds / wallSeconds`. This measures whether the core simulation can keep up with real time, without forcing a 20-minute wall-clock sleep.
- **Praha fixture reused:** Already validated in XMLContextFactoryTest; 5 entries and 6 exits avoid trivial contention.
- **Round-robin roster:** Spreads trains across entries/exits so the path reservation service exercises the whole topology.
- **Headway 5s, train length 40m:** Matches `ThreeTrainLoop` length; keeps queueing safe without making the scenario artificially short.
- **`MAX_CONCURRENT_TRAINS = 20`:** High enough that the dispatcher cap does not serialize the stress test artificially.
- **End times tunable:** `400L` for 5 trains and `1200L` for 20 trains are conservative first guesses. If the first local run shows trains not finishing, we will raise them.

### 4.4 Potential risk: unknown Praha path validity

`MultiTrainLoop` calls `topologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)`. We have not yet verified that every north→south InOut pair in Praha has a valid topological path. If some pairs return empty paths, the corresponding trains will be generated but never started, causing the entered/exited assertions to fail.

Mitigation: during implementation, run a one-off check of the 30 north→south pairs. If any pair lacks a path, we adjust the roster to use only verified pairs. This is a discovery step, not a design change.

---

## 5. Tests

### 5.1 New test file

- `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainScaleValidationTest.kt`
- Two test methods: `fiveTrainCompleteness()` and `twentyTrainStress()`.

### 5.2 Test infrastructure change

- `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/NetworkResources.kt`
  - Add `PRAHA_HLAVNI_NADRAZI_XML` lazy constant.

### 5.3 Existing tests to keep green

- `ThreeTrainLoopRaceTest`
- `TwoTrainConcurrencyTest`
- `MultiTrainLoopTest`
- `ShuntingLoop*` suite
- `PathReservationServiceTest` / `PathReservationRegistryTest`
- Full `./gradlew test integrationTest`
- `./gradlew :core:linuxX64Test` (native commonTest subset)

---

## 6. Error Handling and Backward Compatibility

- This is a test-only addition plus a `:core-test` fixture constant. No public production API changes.
- Each run creates a fresh context and closes it with `.use {}`, so resource leaks are isolated per run.
- Koin lifecycle is handled by `CommonKoinTestBase`.
- If the real-time ratio falls below 1.0 on the development machine, the test fails and the logged metrics become the documented bottleneck summary.

---

## 7. Build / Quality Gates

The change must pass:

```bash
./gradlew clean build detekt ktlintCheck test integrationTest :core:linuxX64Test
```

Note: the 20-train stress test may take several real minutes because it runs 10 full simulations of 1200 simulation seconds each. If it makes CI too slow, we can reduce `STRESS_RUNS` or `TWENTY_TRAIN_END_TIME` after the first local timing run.

---

## 8. Branch and PR Plan

1. Create branch `feat/issue-591-scale-validation` from `goal-1`.
2. Add `PRAHA_HLAVNI_NADRAZI_XML` to `NetworkResources.kt`.
3. Add `MultiTrainScaleValidationTest.kt`.
4. Run the full quality gate locally, including `:core:linuxX64Test` if the native toolchain is available.
5. Open a PR to `goal-1` with a description following `.github/PULL_REQUEST_TEMPLATE.md`.
6. Do **not** auto-merge; wait for external review (per project memory).

---

## 9. Acceptance Criteria Checklist

- [ ] `PRAHA_HLAVNI_NADRAZI_XML` added to `NetworkResources`.
- [ ] `MultiTrainScaleValidationTest` added in `:core/src/commonTest/.../sim/`.
- [ ] 5-train case asserts all 5 trains enter, exit, and release resources.
- [ ] 20-train stress case runs 10 times and asserts all 20 trains enter, exit, release resources, and real-time ratio >= 1.0.
- [ ] Aggregate ratio metrics logged.
- [ ] `./gradlew clean build detekt ktlintCheck test integrationTest` green.
- [ ] `./gradlew :core:linuxX64Test` green (or at least no new failures attributable to this test).
- [ ] PR opened to `goal-1`; not auto-merged.
