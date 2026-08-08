/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.sweep

import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Declarative parameter grid for the `aiSweep` driver (SP2c.24, Issue #847).
 *
 * ## Format
 *
 * ```json
 * {
 *   "endTimeSeconds": 600,
 *   "repeat": 10,
 *   "perRunTimeoutSeconds": 900,
 *   "axes": {
 *     "example": ["shuntingLoopAI"],
 *     "model": ["qwen2.5:7b-instruct"],
 *     "temperature": [0.28, 0.5],
 *     "tickPeriodMs": [0],
 *     "historyN": [3],
 *     "maxActionsPerTick": [3]
 *   }
 * }
 * ```
 *
 * Every axis is a list; the driver runs the cartesian product of all axes, [repeat] times each.
 * An omitted axis falls back to a single-element list holding the production default, so the
 * smallest useful grid is `{"repeat": 10}`.
 *
 * ## `example` is an axis, not a mode
 *
 * A4 compares the LLM arm against the rule-based one, and both are ordinary registered examples
 * (`shuntingLoopAI` and `shuntingLoop`). Treating the example as a grid axis lets one sweep
 * produce both arms and therefore one report that compares them, instead of requiring two
 * invocations whose outputs must then be merged by hand.
 *
 * ## Why a file and not flags
 *
 * The grid is a description of a measurement, and it has to be quotable in the report and in an
 * issue comment. A file is reviewable, diffable and can be committed next to the results; a shell
 * line reconstructed from memory three weeks later is not.
 *
 * @property endTimeSeconds Simulated end time handed to each run as the example's `endTime`.
 * @property repeat Number of runs per grid cell. A4 asks for N ≥ 10 per measured configuration.
 * @property perRunTimeoutSeconds Wall-clock budget for one run before the driver kills it and
 *   records [cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause.TIMEOUT_ABORT].
 * @property axes The swept dimensions.
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
@kotlinx.serialization.Serializable
data class SweepGrid(
	val endTimeSeconds: Long = DEFAULT_END_TIME_SECONDS,
	val repeat: Int = DEFAULT_REPEAT,
	val perRunTimeoutSeconds: Long = DEFAULT_PER_RUN_TIMEOUT_SECONDS,
	val axes: SweepAxes = SweepAxes()
) {
	init {
		require(endTimeSeconds > 0) { "endTimeSeconds must be > 0, was $endTimeSeconds" }
		require(repeat > 0) { "repeat must be > 0, was $repeat" }
		require(perRunTimeoutSeconds > 0) { "perRunTimeoutSeconds must be > 0, was $perRunTimeoutSeconds" }
	}

	/**
	 * Expands the grid into the runs to perform, in a stable order: cells in axis-declaration
	 * order, and within a cell repeat `1..repeat`.
	 *
	 * Stability matters for resumption — the driver decides what to skip by run id, and a run id
	 * is derived from the cell, so the same grid file must always describe the same run ids.
	 */
	fun expand(): List<SweepRun> =
		axes.cells().flatMap { cell ->
			(1..repeat).map { repeatIndex -> SweepRun(cell, repeatIndex, endTimeSeconds) }
		}

	companion object {
		const val DEFAULT_END_TIME_SECONDS: Long = 600L

		/** A4's bar: "report success over N ≥ 10 runs, gate at ≥ 8/10". */
		const val DEFAULT_REPEAT: Int = 10

		/**
		 * 15 minutes. A 600 s `shuntingLoopAI` run measured ~320 s wall clock against
		 * `qwen2.5:7b-instruct`, so this is roughly 3× the observed cost — generous enough not to
		 * kill a merely slow run, tight enough that a stalled one cannot eat an unattended night.
		 */
		const val DEFAULT_PER_RUN_TIMEOUT_SECONDS: Long = 900L

		private val json =
			Json {
				ignoreUnknownKeys = false
				isLenient = false
			}

		/**
		 * Parses a grid from [file].
		 *
		 * @throws SweepGridException if the file cannot be read or is not a valid grid. Unknown
		 *   keys are rejected rather than ignored: a typo in an axis name would otherwise silently
		 *   sweep the default instead of what was asked for, and the run JSONs would look correct.
		 */
		fun load(file: Path): SweepGrid {
			val text =
				try {
					Files.readString(file)
				} catch (e: java.io.IOException) {
					throw SweepGridException("Cannot read sweep grid file '$file': ${e.message}", e)
				}
			return try {
				json.decodeFromString(serializer(), text)
			} catch (e: SerializationException) {
				throw SweepGridException("Sweep grid file '$file' is not valid: ${e.message}", e)
			} catch (e: IllegalArgumentException) {
				throw SweepGridException("Sweep grid file '$file' is not valid: ${e.message}", e)
			}
		}
	}
}

/**
 * The swept dimensions of a [SweepGrid]. Each is a list of values; the grid is their product.
 *
 * All five parameter axes are honoured by the live dispatcher path — `temperature` and `model`
 * through [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig], and
 * `tickPeriodMs` / `historyN` / `maxActionsPerTick` through the wiring SP2c.24 added
 * ([cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver],
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory] and
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder] respectively).
 *
 * There is deliberately **no `seed` axis**. Koog 1.1.1 exposes no path from this project's
 * executor configuration to Ollama's `seed` option, so a seed column would be a value the runs do
 * not actually use — and P8's reproducibility claim rests on the pinned seed being real.
 */
@kotlinx.serialization.Serializable
data class SweepAxes(
	val example: List<String> = listOf(DEFAULT_EXAMPLE),
	val model: List<String> = listOf(""),
	val temperature: List<Double> = listOf(-1.0),
	val tickPeriodMs: List<Long> = listOf(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS),
	val historyN: List<Int> = listOf(DispatcherRunConfig.DEFAULT_HISTORY_N),
	val maxActionsPerTick: List<Int> = listOf(DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK),
	/**
	 * Per-cycle LLM inference deadline in seconds, or the [UNSET_INFERENCE_TIMEOUT_SECONDS]
	 * sentinel meaning "leave [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]'s
	 * own default (30s) in place" — mirrors [model]/[temperature]'s "omitted axis" pattern rather
	 * than [tickPeriodMs]/[historyN]/[maxActionsPerTick]'s "always pass the production default"
	 * pattern, because the whole point (Issue #893 iteration 2) is that the production default
	 * stays 30s and only the sweep grid may ask for something else, such as 90s.
	 *
	 * @since Issue #893 iteration 2
	 */
	val inferenceTimeoutSeconds: List<Long> = listOf(UNSET_INFERENCE_TIMEOUT_SECONDS)
) {
	init {
		require(example.isNotEmpty()) { "axes.example must not be empty" }
		require(model.isNotEmpty()) { "axes.model must not be empty" }
		require(temperature.isNotEmpty()) { "axes.temperature must not be empty" }
		require(tickPeriodMs.isNotEmpty()) { "axes.tickPeriodMs must not be empty" }
		require(historyN.isNotEmpty()) { "axes.historyN must not be empty" }
		require(maxActionsPerTick.isNotEmpty()) { "axes.maxActionsPerTick must not be empty" }
		require(inferenceTimeoutSeconds.isNotEmpty()) { "axes.inferenceTimeoutSeconds must not be empty" }
		require(example.all { it.isNotBlank() }) { "axes.example must not contain a blank name" }
		require(tickPeriodMs.all { it >= 0 }) { "axes.tickPeriodMs values must be >= 0" }
		require(historyN.all { it >= 0 }) { "axes.historyN values must be >= 0" }
		require(maxActionsPerTick.all { it >= 1 }) { "axes.maxActionsPerTick values must be >= 1" }
		// Issue #893 review (Copilot): only the UNSET sentinel (-1) means "omitted axis". Any
		// other non-positive value is a configuration mistake that `cells()`'s `takeIf { it > 0 }`
		// would otherwise silently collapse to `null` — indistinguishable from an omitted axis
		// (same `it-default` slug, same 30 s run). Reject it here so invalid grids fail at load.
		require(
			inferenceTimeoutSeconds.all { it == UNSET_INFERENCE_TIMEOUT_SECONDS || it >= 1 }
		) {
			"axes.inferenceTimeoutSeconds values must be $UNSET_INFERENCE_TIMEOUT_SECONDS " +
				"(unset) or >= 1, was $inferenceTimeoutSeconds"
		}
	}

	/** The cartesian product, in axis-declaration order. */
	fun cells(): List<SweepCell> =
		example.flatMap { exampleName ->
			model.flatMap { modelName ->
				temperature.flatMap { temp ->
					tickPeriodMs.flatMap { period ->
						historyN.flatMap { history ->
							maxActionsPerTick.flatMap { maxActions ->
								inferenceTimeoutSeconds.map { timeout ->
									SweepCell(
										example = exampleName,
										model = modelName.takeIf { it.isNotBlank() },
										temperature = temp.takeIf { it >= 0.0 },
										tickPeriodMs = period,
										historyN = history,
										maxActionsPerTick = maxActions,
										inferenceTimeoutSeconds = timeout.takeIf { it > 0 }
									)
								}
							}
						}
					}
				}
			}
		}

	companion object {
		const val DEFAULT_EXAMPLE: String = "shuntingLoopAI"

		/** Marks "no inferenceTimeoutSeconds axis value" — mirrors [temperature]'s `-1.0` sentinel. */
		const val UNSET_INFERENCE_TIMEOUT_SECONDS: Long = -1L
	}
}

/** Raised when a grid file cannot be read or does not describe a valid grid. */
class SweepGridException(
	message: String,
	cause: Throwable? = null
) : Exception(message, cause)
