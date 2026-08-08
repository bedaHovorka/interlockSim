/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Per-run dispatcher settings supplied from outside the JVM (SP2c.24, Issue #847).
 *
 * ## Why this exists
 *
 * #847's parameter grid needs to vary the dispatcher's behaviour between runs. Before this class
 * there was no way to do that at all: `model` and `temperature` were compile-time defaults on
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig], and `tickPeriodMs`,
 * `historyN` and `maxActionsPerTick` were recorded into every run JSON as the `-1`
 * "not applicable" sentinel because their only consumers
 * ([cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] and [ActionValidator]) are never
 * constructed in production — the live path is [AgentLoopDriver].
 *
 * ## Why system properties rather than environment variables
 *
 * The sweep driver forks one JVM per run so it can impose a real wall-clock timeout and keep runs
 * isolated. `-D` flags are the natural per-process channel there, they need no shell, and they are
 * trivially settable from a test. `OLLAMA_BASE_URL` stays an environment variable — it is machine
 * configuration, not a grid axis.
 *
 * Every property is optional; an absent or unparseable value falls back to the corresponding
 * default, which reproduces the behaviour that existed before this class. An unparseable value is
 * logged at WARN rather than failing the run: a malformed `-D` must not silently become a
 * *different* measurement, but it must also not lose an hour of unattended sweep.
 *
 * @property model Ollama model tag; `null` keeps [executor.OllamaExecutorConfig]'s default.
 * @property temperature Sampling temperature; `null` keeps the executor default.
 * @property tickPeriodMs Minimum wall-clock spacing between driver cycles, enforced by
 *   [AgentLoopDriver]. `0` (the default) means "as fast as the snapshot signal allows", which is
 *   the pre-#847 behaviour. See [AgentLoopDriver] for why this is a wall-clock and not a
 *   simulated-time period on the async path.
 * @property historyN Capacity of the per-cycle history block rendered into the LLM prompt.
 *   `0` disables the block entirely, which is what makes it usable as an A/B axis.
 * @property maxActionsPerTick Cap on non-NoOp actuator emissions accepted per agent cycle,
 *   enforced by [cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder].
 * @property runId Run identifier the recorder must use instead of a fresh UUID. The sweep driver
 *   assigns a deterministic id per grid cell so an interrupted sweep can tell which cells already
 *   have a result file.
 * @property runsRoot Directory the per-run JSON is written under; `null` keeps
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore.DEFAULT_ROOT].
 * @property inferenceTimeoutSeconds Maximum wall-clock time a single LLM cycle may take before
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] gives up and falls back
 *   to the rule-based dispatcher for that cycle. Defaults to
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS]
 *   (30s), reproducing the pre-Issue-#893-iteration-2 behaviour — the production default is
 *   unchanged; a longer budget (e.g. 90s) is a grid-only measurement value, never a production
 *   default (traffic-simulation-expert + agent-architect ruling, Issue #893).
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid);
 *   `inferenceTimeoutSeconds` added in Issue #893 iteration 2
 */
data class DispatcherRunConfig(
	val model: String? = null,
	val temperature: Float? = null,
	val tickPeriodMs: Long = DEFAULT_TICK_PERIOD_MS,
	val historyN: Int = DEFAULT_HISTORY_N,
	val maxActionsPerTick: Int = DEFAULT_MAX_ACTIONS_PER_TICK,
	val runId: String? = null,
	val runsRoot: String? = null,
	val inferenceTimeoutSeconds: Long = DEFAULT_INFERENCE_TIMEOUT_SECONDS
) {
	init {
		require(tickPeriodMs >= 0) { "tickPeriodMs must be >= 0, was $tickPeriodMs" }
		require(historyN >= 0) { "historyN must be >= 0, was $historyN" }
		require(maxActionsPerTick >= 1) { "maxActionsPerTick must be >= 1, was $maxActionsPerTick" }
		require(inferenceTimeoutSeconds >= 1) {
			"inferenceTimeoutSeconds must be >= 1, was $inferenceTimeoutSeconds"
		}
	}

	companion object {
		/** Property-name prefix for every knob this class reads. */
		const val PREFIX: String = "interlocksim.dispatcher."

		const val PROP_MODEL: String = "${PREFIX}model"
		const val PROP_TEMPERATURE: String = "${PREFIX}temperature"
		const val PROP_TICK_PERIOD_MS: String = "${PREFIX}tickPeriodMs"
		const val PROP_HISTORY_N: String = "${PREFIX}historyN"
		const val PROP_MAX_ACTIONS_PER_TICK: String = "${PREFIX}maxActionsPerTick"
		const val PROP_RUN_ID: String = "${PREFIX}runId"
		const val PROP_RUNS_ROOT: String = "${PREFIX}runsRoot"
		const val PROP_INFERENCE_TIMEOUT_SECONDS: String = "${PREFIX}inferenceTimeoutSeconds"

		/**
		 * No enforced spacing. The snapshot signal already paces the driver at one cycle per
		 * control step, and at the measured 10–25 s inference latency an imposed period below p95
		 * latency changes nothing — so the default must not pretend to.
		 */
		const val DEFAULT_TICK_PERIOD_MS: Long = 0L

		/** Matches [cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer]'s own default (C5: "N = 3 to start"). */
		const val DEFAULT_HISTORY_N: Int = 3

		/** Matches [ActionValidator]'s default and §5.5's "0–3 actions per step". */
		const val DEFAULT_MAX_ACTIONS_PER_TICK: Int = 3

		/**
		 * Matches [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS].
		 * The production default is unchanged by Issue #893 iteration 2 — only the sweep grid may
		 * override it, per the traffic-simulation-expert + agent-architect ruling.
		 */
		const val DEFAULT_INFERENCE_TIMEOUT_SECONDS: Long = 30L

		/**
		 * Reads the configuration from JVM system properties.
		 *
		 * @param properties Property lookup, defaulting to [System.getProperty]. Injectable so
		 *   tests do not have to mutate global JVM state.
		 */
		fun fromProperties(properties: (String) -> String? = System::getProperty): DispatcherRunConfig =
			DispatcherRunConfig(
				model = properties(PROP_MODEL)?.takeIf { it.isNotBlank() },
				temperature = parseOrDefault(properties(PROP_TEMPERATURE), PROP_TEMPERATURE, null, String::toFloatOrNull),
				tickPeriodMs =
					parseOrDefault(
						properties(PROP_TICK_PERIOD_MS),
						PROP_TICK_PERIOD_MS,
						DEFAULT_TICK_PERIOD_MS
					) { it.toLongOrNull()?.takeIf { parsed -> parsed >= 0 } },
				historyN =
					parseOrDefault(
						properties(PROP_HISTORY_N),
						PROP_HISTORY_N,
						DEFAULT_HISTORY_N
					) { it.toIntOrNull()?.takeIf { parsed -> parsed >= 0 } },
				maxActionsPerTick =
					parseOrDefault(
						properties(PROP_MAX_ACTIONS_PER_TICK),
						PROP_MAX_ACTIONS_PER_TICK,
						DEFAULT_MAX_ACTIONS_PER_TICK
					) { it.toIntOrNull()?.takeIf { parsed -> parsed >= 1 } },
				runId = properties(PROP_RUN_ID)?.takeIf { it.isNotBlank() },
				runsRoot = properties(PROP_RUNS_ROOT)?.takeIf { it.isNotBlank() },
				inferenceTimeoutSeconds =
					parseOrDefault(
						properties(PROP_INFERENCE_TIMEOUT_SECONDS),
						PROP_INFERENCE_TIMEOUT_SECONDS,
						DEFAULT_INFERENCE_TIMEOUT_SECONDS
					) { it.toLongOrNull()?.takeIf { parsed -> parsed >= 1 } }
			)

		private fun <T> parseOrDefault(
			raw: String?,
			propertyName: String,
			fallback: T,
			parse: (String) -> T?
		): T {
			if (raw.isNullOrBlank()) return fallback
			val parsed = parse(raw)
			if (parsed == null) {
				logger.warn { "Ignoring unparseable $propertyName='$raw'; using $fallback" }
				return fallback
			}
			return parsed
		}
	}
}
