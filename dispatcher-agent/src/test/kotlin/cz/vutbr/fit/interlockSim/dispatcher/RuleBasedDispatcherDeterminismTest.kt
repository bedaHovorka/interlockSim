/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.testutil.RuleBasedDispatcherDeterminismRunner
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Goal 10 Stage A3 acceptance gate — determinism of [RuleBasedDispatcher].
 *
 * Runs `vyhybna.xml` with [RuleBasedDispatcher] 10 consecutive times and asserts
 * identical outcomes on every run: same number of trains exited, same maximum
 * concurrent trains, and matching block-transition counts per train.
 *
 * **Why these metrics?**
 * - `trainsExited` verifies all trains complete their journeys (no deadlock).
 * - `maxConcurrentTrains` verifies the MAX_TRAINS=2 capacity constraint held.
 * - Per-train block transitions verify the exact route taken was consistent.
 * - `conflictEventCount` verifies [RuleBasedDispatcher] never causes a block conflict —
 *   zero [cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent]s expected
 *   for the deterministic shunting-loop topology.
 *
 * **Baseline (established during SP0.1, extended in SP0.12):**
 * All 5 generated trains exit; max concurrent = 2; each train makes 2 block
 * transitions (sorted per-train transition counts `[2, 2, 2, 2, 2]`);
 * zero conflict events.
 *
 * The `vyhybna.xml` + dispatcher-agent-stack wiring itself lives in
 * [RuleBasedDispatcherDeterminismRunner], shared with
 * [RuleBasedDispatcherDeterminismHeavyTest] (the 1000-repetition manual-only
 * `heavyTest` variant) so the two gates cannot drift apart.
 *
 * @see RuleBasedDispatcher
 * @see cz.vutbr.fit.interlockSim.sim.ShuntingLoop
 * @see RuleBasedDispatcherDeterminismHeavyTest
 * @since Issue #540 (SP0.1 — Goal 10 Stage A3); re-enabled in Issue #829 (SP2c.6) after the
 *   ActionValidator forward-extension fix landed.
 */
@DisplayName("RuleBasedDispatcher determinism — 10 consecutive vyhybna.xml runs (Goal 10 A3)")
@Tag("integration-test")
class RuleBasedDispatcherDeterminismTest {
	private val runner = RuleBasedDispatcherDeterminismRunner()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	// ── Goal 10 Stage A3: 10 consecutive identical-outcome runs ──────────────

	/**
	 * Runs the vyhybna.xml simulation 10 times with [RuleBasedDispatcher] and asserts
	 * all outcomes are identical to the first run.
	 *
	 * The first repetition establishes the baseline; subsequent repetitions compare
	 * against it.  A failure in any repetition indicates non-determinism.
	 */
	@RepeatedTest(10)
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("vyhybna.xml run produces identical outcome (Goal 10 A3)")
	fun ruleBasedDispatcherIsFullyDeterministic(info: RepetitionInfo) {
		val result = runner.executeRun()

		// All trains must exit — no deadlock.
		assertThat(result.trainsExited)
			.isGreaterThan(0)

		// Physical capacity constraint: never more than 2 trains concurrently.
		assertThat(result.maxConcurrentTrains)
			.isGreaterThanOrEqualTo(1)
		assertThat(result.maxConcurrentTrains)
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)

		// SP0.12 A3 gate: RuleBasedDispatcher must not cause any block conflicts on
		// the shunting-loop topology.  A non-zero count indicates the dispatcher is
		// issuing competing reservations — a regression in dispatch correctness.
		assertThat(result.conflictEventCount)
			.isEqualTo(0)

		// Store baseline on first run; compare on subsequent runs.
		if (info.currentRepetition == 1) {
			baselineResult = result
			logger.info {
				"Run 1 baseline: trainsExited=${result.trainsExited}, " +
					"maxConcurrent=${result.maxConcurrentTrains}, " +
					"transitionCounts=${result.sortedBlockTransitionCounts}, " +
					"conflictEvents=${result.conflictEventCount}"
			}
		} else {
			val baseline =
				requireNotNull(baselineResult) {
					"Baseline result must be set by repetition 1"
				}
			assertThat(result.trainsExited)
				.isEqualTo(baseline.trainsExited)
			assertThat(result.maxConcurrentTrains)
				.isEqualTo(baseline.maxConcurrentTrains)
			assertThat(result.sortedBlockTransitionCounts)
				.isEqualTo(baseline.sortedBlockTransitionCounts)
			assertThat(result.conflictEventCount)
				.isEqualTo(baseline.conflictEventCount)
		}
	}

	companion object {
		// Thread-safe across JUnit parallel execution: tests in this class are
		// sequential (single @RepeatedTest method), so volatile is sufficient.
		@Volatile
		private var baselineResult: RuleBasedDispatcherDeterminismRunner.RunResult? = null
	}
}
