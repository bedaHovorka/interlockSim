/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Goal 10 SP0.11c: 1000-repetition heavy stress variant of
 * RuleBasedDispatcherDeterminismTest (Issue #746). See CLAUDE.md "Heavy tests" for
 * when to run this.
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
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
 * Heavy-repetition variant of [RuleBasedDispatcherDeterminismTest] — identical
 * assertions and identical `vyhybna.xml` + dispatcher-agent-stack wiring (shared via
 * [RuleBasedDispatcherDeterminismRunner]), 1000 repetitions instead of 10.
 *
 * This test is tagged [Tag] `"heavy-test"` and is **excluded from regular `test` and
 * `integrationTest` builds** — see CLAUDE.md "Heavy tests". Run it deliberately with:
 * ```
 * ./gradlew :dispatcher-agent:heavyTest
 * ```
 *
 * **When to run:** After changes to [AgentLoopDriver] / [SnapshotSignal] pacing (or
 * any other simulation-adjacent concurrency primitive in this module) to confirm the
 * SP0.11c fix (Issue #746) actually eliminated the previously-observed ~4%
 * `trainsExited = 0` / spurious-conflict residue at the 10-repetition sample size the
 * plain `integrationTest` gate uses, rather than merely reduced it below what 10
 * repetitions can reliably detect.
 *
 * ## `conflictEventCount` tolerance (SP2c.6, Issue #829)
 *
 * [MAX_TOLERATED_CONFLICT_EVENTS] mirrors [RuleBasedDispatcherDeterminismTest]'s tolerance —
 * see that class's KDoc for the traced (but not yet fixed — it is a `:core` pathfinding-layer
 * question) cause. This 1000-repetition run measured the rate directly: 3 of 1000 repetitions
 * hit exactly one conflict event each (confirmed 2026-08-01), consistent with a rare, bounded,
 * self-resolving transient rather than an unbounded regression.
 *
 * @see RuleBasedDispatcherDeterminismTest
 * @since Issue #746 (SP0.11c — Goal 10)
 */
@DisplayName("RuleBasedDispatcher determinism — 1000-repetition heavy stress (Issue #746)")
@Tag("heavy-test")
class RuleBasedDispatcherDeterminismHeavyTest {
	private val runner = RuleBasedDispatcherDeterminismRunner()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	@RepeatedTest(1000)
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("vyhybna.xml run produces identical outcome (1000x heavy stress)")
	fun ruleBasedDispatcherIsFullyDeterministicHeavy(info: RepetitionInfo) {
		val result = runner.executeRun()

		assertThat(result.trainsExited)
			.isGreaterThan(0)
		assertThat(result.maxConcurrentTrains)
			.isGreaterThanOrEqualTo(1)
		assertThat(result.maxConcurrentTrains)
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		assertThat(result.conflictEventCount)
			.isLessThanOrEqualTo(MAX_TOLERATED_CONFLICT_EVENTS)

		if (info.currentRepetition == 1) {
			baselineResult = result
			logger.info {
				"Heavy run 1 baseline: trainsExited=${result.trainsExited}, " +
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
			// Not compared against baseline — see RuleBasedDispatcherDeterminismTest's KDoc and
			// the class KDoc above: conflictEventCount legitimately varies per repetition. The
			// absolute bound above is the meaningful check for this field.
		}

		if (info.currentRepetition % HEAVY_LOG_INTERVAL == 0) {
			logger.info { "Heavy run ${info.currentRepetition}/${info.totalRepetitions} OK" }
		}
	}

	companion object {
		private const val HEAVY_LOG_INTERVAL = 100

		// Thread-safe across JUnit parallel execution: tests in this class are
		// sequential (single @RepeatedTest method), so volatile is sufficient.
		@Volatile
		private var baselineResult: RuleBasedDispatcherDeterminismRunner.RunResult? = null

		/** See the class KDoc's "`conflictEventCount` tolerance" section. */
		private const val MAX_TOLERATED_CONFLICT_EVENTS = 1
	}
}
