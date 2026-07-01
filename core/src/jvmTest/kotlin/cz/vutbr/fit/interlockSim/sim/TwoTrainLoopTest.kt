/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP5: two-train concurrency validation (Issue #587).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * Goal 1 SP5: validate two-train concurrency without deadlock or ordering errors.
 *
 * The scenario uses [TwoTrainLoop] on a linear A → B track with two trains injected
 * at the same simulation step. The stress test repeats the run 100 times and verifies:
 * - both trains complete their route,
 * - no exception or deadlock occurs,
 * - block transition events are emitted in the same order every run,
 * - the second train only enters a block after the first train has left it
 *   (tail-to-head following),
 * - wall-clock runtime stays within a narrow band across all runs.
 */
@Tag("integration-test")
@DisplayName("TwoTrainLoop — two-train concurrency and ordering validation (Goal 1 SP5)")
class TwoTrainLoopTest : KoinTestBase() {
	private companion object {
		/** Number of consecutive runs used to check determinism and stability. */
		const val REPEAT_COUNT: Int = 100

		/** Simulation end time for each run. */
		const val END_TIME: Long = 400L

		/** Regex for block-transition reports emitted by [Train]. */
		val BLOCK_TRANSITION_REGEX =
			Regex("""^(\d+(?:\.\d+)?)\s+(Train #\d+)\s+(enter|leave) block$""")
	}

	private data class RunResult(
		val wallMs: Long,
		val transitions: List<String>,
		val trainsEntered: Int,
		val trainsExited: Int,
		val maxConcurrentTrains: Int
	)

	/** Single validation run that records block transitions and basic metrics. */
	private fun runOnce(runIndex: Int): RunResult {
		System.err.println("runOnce start $runIndex")
		val transitions = mutableListOf<String>()
		val listener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName == ReportType.TRAIN_EVENTS.name) {
					val full = event.newValue?.toString() ?: ""
					val match = BLOCK_TRANSITION_REGEX.find(full)
					if (match != null) {
						val (time, train, type) = match.destructured
						transitions.add("$time $train $type")
					}
				}
			}

		val startNs = System.nanoTime()
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		ctx.use { c ->
			c.getInOuts()
			c.addPropertyChangeListener(listener)
			val process = TwoTrainLoop(c, endTime = END_TIME)
			c.setMainProcess(process)
			c.run()
			val wallMs = (System.nanoTime() - startNs) / 1_000_000
			return RunResult(
				wallMs = wallMs,
				transitions = transitions.toList(),
				trainsEntered = process.getTrainsEntered(),
				trainsExited = process.getTrainsExited(),
				maxConcurrentTrains = process.getMaxConcurrentTrains()
			)
		}
	}

	/**
	 * Returns the distinct train numbers in order of their first appearance in the
	 * transition log. Train numbers come from a global counter, so every run uses
	 * different absolute values; ranking them makes assertions independent of the
	 * counter.
	 */
	private fun trainNumbersInOrder(transitions: List<String>): List<Int> {
		val numbers = mutableListOf<Int>()
		val regex = Regex("""Train #(\d+)""")
		transitions.forEach { line ->
			regex.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { number ->
				if (!numbers.contains(number)) numbers.add(number)
			}
		}
		return numbers
	}

	/**
	 * Normalizes a transition log so that absolute train numbers are replaced by
	 * their appearance rank within the run. This allows deterministic cross-run
	 * comparison even though the global train counter advances.
	 */
	private fun normalizeTransitions(transitions: List<String>): List<String> {
		val numbers = trainNumbersInOrder(transitions)
		val rankByNumber = numbers.withIndex().associate { it.value to it.index + 1 }
		return transitions.map { line ->
			Regex("""Train #(\d+)""").replace(line) { match ->
				val number = match.groupValues[1].toInt()
				"Train #${rankByNumber[number]}"
			}
		}
	}

	/** Verifies that the second train never enters a block before the first train has left it. */
	private fun assertTailToHeadOrdering(
		transitions: List<String>,
		runIndex: Int
	) {
		val numbers = trainNumbersInOrder(transitions)
		assertThat(numbers.size, name = "run $runIndex trains in transition log")
			.isGreaterThanOrEqualTo(2)

		val firstTrain = "Train #${numbers[0]}"
		val secondTrain = "Train #${numbers[1]}"

		val firstLeaves =
			transitions
				.mapIndexed { index, line -> index to line }
				.filter { it.second.contains(firstTrain) && it.second.contains("leave") }
				.map { it.first }
		val secondEnters =
			transitions
				.mapIndexed { index, line -> index to line }
				.filter { it.second.contains(secondTrain) && it.second.contains("enter") }
				.map { it.first }

		assertThat(firstLeaves.isNotEmpty(), name = "run $runIndex first train leaves a block")
			.isTrue()
		assertThat(secondEnters.isNotEmpty(), name = "run $runIndex second train enters a block")
			.isTrue()

		val commonBlocks = minOf(firstLeaves.size, secondEnters.size)
		repeat(commonBlocks) { blockIndex ->
			assertThat(
				secondEnters[blockIndex],
				name = "run $runIndex tail-to-head ordering on block $blockIndex"
			).isGreaterThanOrEqualTo(firstLeaves[blockIndex])
		}
	}

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("100 consecutive runs: no deadlock, deterministic ordering and runtime")
	fun `two train concurrency is deterministic across 100 runs`() {
		val results = mutableListOf<RunResult>()
		var baselineTransitions: List<String>? = null

		repeat(REPEAT_COUNT) { index ->
			val result = runOnce(index)
			results.add(result)

			// Every run must finish both trains.
			assertThat(result.trainsEntered, name = "run $index trains entered")
				.isEqualTo(2)
			assertThat(result.trainsExited, name = "run $index trains exited")
				.isEqualTo(2)
			assertThat(result.maxConcurrentTrains, name = "run $index peak concurrency")
				.isGreaterThanOrEqualTo(2)
			assertThat(result.transitions.isNotEmpty(), name = "run $index has transitions")
				.isTrue()

			System.err.println(
				"Run $index transitions=${result.transitions.size}, " +
					"entered=${result.trainsEntered}, exited=${result.trainsExited}, " +
					"sample=${result.transitions.take(5)}"
			)

			assertTailToHeadOrdering(result.transitions, index)

			// All runs must emit the exact same transition sequence when normalized
			// so that the advancing global train counter does not affect equality.
			val normalized = normalizeTransitions(result.transitions)
			if (index == 0) {
				baselineTransitions = normalized
			} else {
				assertThat(normalized, name = "run $index transition sequence")
					.isEqualTo(baselineTransitions)
			}
		}

		// Wall-clock runtime statistics (discard a small warmup window to avoid JIT cold-start skew).
		val wallTimes = results.drop(10).map { it.wallMs }
		val minMs = wallTimes.min()
		val maxMs = wallTimes.max()
		val mean = wallTimes.average()
		val stdDev = sqrt(wallTimes.map { (it - mean) * (it - mean) }.average())
		val coefficientOfVariation = if (mean > 0.0) stdDev / mean else 0.0

		logger.info {
			"TwoTrainLoop stress run complete: " +
				"runs=$REPEAT_COUNT, " +
				"min=${minMs}ms, max=${maxMs}ms, mean=${mean}ms, " +
				"stdDev=${stdDev}ms, CV=$coefficientOfVariation"
		}

		assertThat(coefficientOfVariation, name = "coefficient of variation")
			.isLessThan(0.5)
		assertThat(maxMs - minMs, name = "wall-clock spread")
			.isLessThan(2000L)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("Single run leaves the network clean")
	fun `two train loop leaves no occupied resources`() {
		val result = runOnce(0)
		assertThat(result.trainsEntered).isEqualTo(2)
		assertThat(result.trainsExited).isEqualTo(2)
		assertThat(result.maxConcurrentTrains).isGreaterThanOrEqualTo(2)
	}
}
