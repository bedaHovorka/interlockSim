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

import cz.vutbr.fit.interlockSim.dispatcher.agents.PromptVariant
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
 * ## Configuration precedence (Issue #834, SP2c.11)
 *
 * [tickPeriodMs], [historyN], [maxActionsPerTick], [inferenceTimeoutSeconds] and [promptVariant]
 * each resolve in this order:
 *
 * ```
 * JVM system property (-Dinterlocksim.dispatcher.*)  >  committed properties resource  >  code
 * fallback constant (DEFAULT_*)
 * ```
 *
 * The committed resource is [DispatcherDefaultsResource.shipped]
 * (`dispatcher-defaults.properties`, same key names as the `-D` properties). This is the layer
 * #834 adds: a chosen default can now be *committed* — edited in that file, reviewed in a diff,
 * cited in a commit body — without touching Kotlin source. The system property still wins, so the
 * sweep driver's forked-JVM `-D` mechanism is unaffected by this file's existence. A missing or
 * malformed resource behaves exactly like an absent/malformed `-D` value: WARN and fall back to
 * the code constant, never an exception (see [DispatcherDefaultsResource]'s KDoc for the file-level
 * failure handling, and [parseOrDefault] for the shared value-level handling).
 *
 * [model] and [temperature] deliberately do **not** gain this file tier here: they are `null`
 * unless a `-D` flag names them, and their file-backed code-fallback resolution lives in
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.default] instead (wired
 * together in `DispatcherAgentModule`'s `runConfig.model ?: base.modelName` override). Reading the
 * same file from two places for the same key would be a second, competing source of the same
 * value; routing both through the single `default()` call keeps there being exactly one.
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
 * @property promptVariant Which revision of the DISPATCHER system prompt
 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory] assembles. Defaults to
 *   [PromptVariant.DEFAULT] ([PromptVariant.BASELINE]), the prompt PR #896 shipped, so this knob's
 *   existence changes no run that does not ask to be changed. Unlike every other knob here the
 *   value is an enum rather than a number, so an unrecognised name is the realistic failure mode:
 *   it is handled exactly like an unparseable number — WARN, then the default — because losing an
 *   unattended sweep to a typo would cost far more than the one mis-labelled measurement that a
 *   hard failure would prevent.
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid);
 *   `inferenceTimeoutSeconds` added in Issue #893 iteration 2; `promptVariant` added in Issue #834
 *   (SP2c.11)
 */
data class DispatcherRunConfig(
	val model: String? = null,
	val temperature: Float? = null,
	val tickPeriodMs: Long = DEFAULT_TICK_PERIOD_MS,
	val historyN: Int = DEFAULT_HISTORY_N,
	val maxActionsPerTick: Int = DEFAULT_MAX_ACTIONS_PER_TICK,
	val runId: String? = null,
	val runsRoot: String? = null,
	val inferenceTimeoutSeconds: Long = DEFAULT_INFERENCE_TIMEOUT_SECONDS,
	val promptVariant: PromptVariant = DEFAULT_PROMPT_VARIANT
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
		const val PROP_PROMPT_VARIANT: String = "${PREFIX}promptVariant"

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
		 * Delegates to [PromptVariant.DEFAULT] rather than naming a variant here, so there is one
		 * place in the codebase that decides which prompt an unconfigured run gets — see that
		 * constant's KDoc for why flipping it is a measurement decision.
		 */
		val DEFAULT_PROMPT_VARIANT: PromptVariant = PromptVariant.DEFAULT

		/**
		 * Reads the configuration from JVM system properties, falling back to the committed
		 * properties resource and then to the code constant — see this class's KDoc
		 * "Configuration precedence" section.
		 *
		 * @param fileProperties Committed-resource lookup, defaulting to
		 *   [DispatcherDefaultsResource.shipped]. Injectable so tests can pin the file tier without
		 *   depending on the real classpath resource's contents.
		 * @param properties System property lookup, defaulting to [System.getProperty]. Injectable
		 *   so tests do not have to mutate global JVM state. Kept as the last parameter so existing
		 *   single-trailing-lambda call sites (`fromProperties { ... }`) keep binding to this one.
		 */
		fun fromProperties(
			fileProperties: (String) -> String? = { key -> DispatcherDefaultsResource.shipped.lookup(key) },
			properties: (String) -> String? = System::getProperty
		): DispatcherRunConfig =
			DispatcherRunConfig(
				model = properties(PROP_MODEL)?.takeIf { it.isNotBlank() },
				temperature = parseOrDefault(properties(PROP_TEMPERATURE), PROP_TEMPERATURE, null, String::toFloatOrNull),
				tickPeriodMs =
					parseOrDefault(
						resolveRaw(PROP_TICK_PERIOD_MS, properties, fileProperties),
						PROP_TICK_PERIOD_MS,
						DEFAULT_TICK_PERIOD_MS
					) { it.toLongOrNull()?.takeIf { parsed -> parsed >= 0 } },
				historyN =
					parseOrDefault(
						resolveRaw(PROP_HISTORY_N, properties, fileProperties),
						PROP_HISTORY_N,
						DEFAULT_HISTORY_N
					) { it.toIntOrNull()?.takeIf { parsed -> parsed >= 0 } },
				maxActionsPerTick =
					parseOrDefault(
						resolveRaw(PROP_MAX_ACTIONS_PER_TICK, properties, fileProperties),
						PROP_MAX_ACTIONS_PER_TICK,
						DEFAULT_MAX_ACTIONS_PER_TICK
					) { it.toIntOrNull()?.takeIf { parsed -> parsed >= 1 } },
				runId = properties(PROP_RUN_ID)?.takeIf { it.isNotBlank() },
				runsRoot = properties(PROP_RUNS_ROOT)?.takeIf { it.isNotBlank() },
				inferenceTimeoutSeconds =
					parseOrDefault(
						resolveRaw(PROP_INFERENCE_TIMEOUT_SECONDS, properties, fileProperties),
						PROP_INFERENCE_TIMEOUT_SECONDS,
						DEFAULT_INFERENCE_TIMEOUT_SECONDS
					) { it.toLongOrNull()?.takeIf { parsed -> parsed >= 1 } },
				promptVariant =
					parseOrDefault(
						resolveRaw(PROP_PROMPT_VARIANT, properties, fileProperties),
						PROP_PROMPT_VARIANT,
						DEFAULT_PROMPT_VARIANT,
						PromptVariant::parse
					)
			)

		/**
		 * Resolves the raw string for [propertyName]: the system property if present and
		 * non-blank, otherwise the committed-file value if present and non-blank, otherwise `null`
		 * (meaning "use the code constant", handled by [parseOrDefault]).
		 */
		private fun resolveRaw(
			propertyName: String,
			properties: (String) -> String?,
			fileProperties: (String) -> String?
		): String? =
			properties(propertyName)?.takeIf { it.isNotBlank() }
				?: fileProperties(propertyName)?.takeIf { it.isNotBlank() }

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
