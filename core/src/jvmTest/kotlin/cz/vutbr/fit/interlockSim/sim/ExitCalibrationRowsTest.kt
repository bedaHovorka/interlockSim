/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B exit calibration (#591 part 1, Issue #1346): the non-gating measurement rows.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Measurement rows of the Goal 1B exit calibration (#591 part 1, Issue #1346): heavier traffic on
 * the same Praha stress path, logged per run for `docs/goal9b-demands/SP11-exit-calibration.md`.
 *
 * **Log-only.** Neither row asserts anything: no functional assertion, no wall-time budget, no
 * real-time ratio. Each run logs entered, exited, max concurrent, the wall time and the
 * simulation time at which the first entry was refused ("blocking begins at"); each row ends with
 * an aggregate line. Both rows use the stress configuration of `MultiTrainScaleStressTest`
 * ([MultiTrainStressSpecs]: the legal pairs in rotation, a concurrency cap of 20, 1,800 entry
 * attempts, 3,600 s), so the entry gap is the only difference between them. Every row line carries
 * that configuration ("cap 20, 1,800 attempts, rotating legal pairs"): the rows' blocking points
 * must never be compared with the blocking points of the exit gate's cap of 4. "Max concurrent"
 * counts admitted trains, including those still waiting for their entry route:
 *
 * - **20-train high traffic:** 10 runs of [MultiTrainStressSpecs.twentyTrainSpecs], headway 5 s.
 * - **Shoulder traffic:** 10 runs of [MultiTrainStressSpecs.shoulderTrafficSpecs], gaps drawn from
 *   15 to 30 s, seeded with [LowTrafficExitScenario.LOW_TRAFFIC_SEEDS].
 *
 * Tagged `heavy-test` (manual only, never in CI); run with:
 * ```
 * ./gradlew :core:heavyTest --tests '*ExitCalibrationRowsTest*'
 * ```
 * The `@Timeout` is a hang detector only.
 */
@Tag("heavy-test")
@DisplayName("MultiTrainLoop — exit calibration measurement rows (heavy, log-only)")
class ExitCalibrationRowsTest : KoinTestBase() {
	private companion object {
		private const val RUNS: Int = 10

		/** Hang detector for one row of [RUNS] runs, not a budget. */
		private const val HANG_CEILING_SECONDS: Long = 600L

		/**
		 * The stress configuration both rows run, printed in every row line so a row is never read
		 * against the exit gate's cap of 4: "cap 20, 1,800 attempts, rotating legal pairs".
		 */
		private val ROW_CONFIGURATION: String =
			"cap ${MultiTrainStressSpecs.MAX_CONCURRENT_TRAINS}, " +
				"${MultiTrainStressSpecs.MAX_ENTRY_ATTEMPTS.withThousandsSeparator()} attempts, rotating legal pairs"

		private fun Int.withThousandsSeparator(): String =
			toString()
				.reversed()
				.chunked(3)
				.joinToString(",")
				.reversed()
	}

	@Test
	@Timeout(value = HANG_CEILING_SECONDS, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("20-train high traffic: 10 runs x 20 trains, headway 5 s (log-only)")
	fun twentyTrainHighTrafficRow() {
		val runs =
			List(RUNS) { index ->
				runStressConfiguration(
					"20-train run ${index + 1}/$RUNS ($ROW_CONFIGURATION)",
					MultiTrainStressSpecs.twentyTrainSpecs()
				)
			}
		logAggregate("20-train high traffic", runs)
	}

	@Test
	@Timeout(value = HANG_CEILING_SECONDS, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("Shoulder traffic: 10 runs x 20 trains, gaps 15-30 s (log-only)")
	fun shoulderTrafficRow() {
		val runs =
			LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.map { seed ->
				runStressConfiguration(
					"shoulder seed $seed ($ROW_CONFIGURATION)",
					MultiTrainStressSpecs.shoulderTrafficSpecs(seed)
				)
			}
		logAggregate("shoulder traffic", runs)
	}

	private fun runStressConfiguration(
		label: String,
		specs: List<MultiTrainLoop.TrainSpec>
	): MultiTrainRun =
		LowTrafficExitScenario.runScenario(
			label = label,
			specs = specs,
			maxConcurrentTrains = MultiTrainStressSpecs.MAX_CONCURRENT_TRAINS,
			endTime = MultiTrainStressSpecs.END_TIME,
			maxEntryAttempts = MultiTrainStressSpecs.MAX_ENTRY_ATTEMPTS,
			processFactory = getKoin().get<SimulationProcessFactory>()
		)

	private fun logAggregate(
		row: String,
		runs: List<MultiTrainRun>
	) {
		val walls = runs.map { it.wallMs }
		val blocking = runs.mapNotNull { it.firstBlockedSimTime }
		val entryFailureTimes = runs.mapNotNull { it.firstEntryFailureSimTime }
		val stallTimes = runs.mapNotNull { it.stallSimTime }
		LowTrafficExitScenario.logger.info {
			"Exit calibration row '$row' ($ROW_CONFIGURATION; never compare with the exit cap 4): runs=${runs.size}, " +
				"allEnteredAndExited=${runs.count { it.meetsFunctionalAssertions }}/${runs.size}, " +
				"maxConcurrent=${runs.minOf { it.maxConcurrent }}..${runs.maxOf { it.maxConcurrent }}, " +
				"blockingBeginsAt=${blocking.minOrNull() ?: "never"}..${blocking.maxOrNull() ?: "never"} " +
				"(${blocking.size}/${runs.size} runs blocked), " +
				"firstEntryFailureAt=${entryFailureTimes.minOrNull() ?: "none"}, " +
				"stallAt=${stallTimes.minOrNull() ?: "none"}, " +
				"wallMs min=${walls.min()} mean=${walls.average().toLong()} max=${walls.max()} total=${walls.sum()}"
		}
	}
}
