/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.executor

import cz.vutbr.fit.interlockSim.dispatcher.DispatcherDefaultsResource
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Configuration for the Ollama LLM executor.
 *
 * ## Responsibility
 *
 * Holds stateless configuration for communicating with a local Ollama instance:
 * - Endpoint URL (default `http://localhost:11434`)
 * - Model selection and parameters (temperature, top_p, max tokens, etc.)
 * - Timeout settings for inference requests
 * - Retry policy for transient failures
 *
 * This is a singleton-scoped component: once created, it is shared across all
 * simulation contexts and agents (the config itself has no mutable state that
 * varies per context).
 *
 * Singleton because config is runtime-global and immutable — the same endpoint, model, and
 * tuning apply to every dispatcher agent, and there is nothing per-context to vary.
 *
 * @property ollamaEndpoint HTTP endpoint of local Ollama instance (default: `http://localhost:11434`,
 *   overridable via the `OLLAMA_BASE_URL` environment variable — see [default])
 * @property modelName Ollama model tag, e.g. "qwen2.5:7b-instruct" (per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md).
 *   [default]'s code-fallback constant is [DEFAULT_MODEL_NAME]; see [default] for the full
 *   `-D` property > committed file > code constant precedence (Issue #834, SP2c.11).
 * @property temperature Model sampling temperature (0.0–1.0, default 0.28; see [default] for the
 *   full `-D` property > committed file > code constant precedence, Issue #834, SP2c.11). Chosen after a live
 *   incident where the LLM hallucinated a nonexistent endpoint name (`"kA"`/`"kB"`) instead of
 *   copying one from its system prompt's topology. Per `docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md`
 *   §7 (A4), the LLM dispatcher is explicitly **not** this project's reproducibility anchor —
 *   `RuleBasedDispatcher` is, and acceptance is outcome-gated across seed-pinned runs, not
 *   decision-for-decision — so 0.28 is **not** chosen for reproducibility. It is chosen for
 *   tool-calling correctness (§2's "reliably emits valid JSON" criterion): low enough to
 *   meaningfully reduce hallucinated tool arguments versus a higher temperature, while staying
 *   clear of near-greedy (~0.1) decoding, where small (7B-class) instruct models tend toward
 *   repetitive/degenerate tool-call loops — a failure mode §6's fallback/loop-guard mitigates but
 *   should not be relied on to mask. Revisit empirically once the §7 benchmark harness lands
 *   (tool-call success rate at 0.28 vs. higher).
 * @property topP Nucleus sampling parameter (0.0–1.0, default 0.9)
 * @property maxTokens Maximum tokens in response (default 1024)
 * @property inferenceTimeout Maximum wall-clock time for inference (default 30 seconds per #532 latency constraint)
 * @property retryAttempts Number of retries on transient failure (default 3)
 * @property maxAgentIterations Maximum number of LLM/tool round-trips [ai.koog.agents.core.agent.AIAgent]
 *   performs within a single [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent.decideAsync]
 *   call (default 20). An initial estimate of 8 (a "couple of perception calls plus one actuator
 *   call") proved too tight in practice: a live `shuntingLoopAI` run against `qwen2.5:7b-instruct`
 *   showed `AIAgentMaxNumberOfIterationsReachedException` on some cycles even in the happy path
 *   (4 perception tool calls + 1 actuator call already consumes most of an 8-step budget once
 *   Koog counts individual node traversals, not LLM turns). Hitting the cap is not unsafe — it is
 *   just another path into [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]'s
 *   exception-based fallback — but hitting it *unnecessarily* means the rule-based fallback runs
 *   more often than intended. 20 gives the model comfortable headroom while [inferenceTimeout]
 *   remains the real safety bound on total wall-clock time per cycle.
 *
 *   Confirmed directly against the pinned Koog 1.1.1 source
 *   (`ai.koog.agents.core.agent.entity.AIAgentSubgraph.executeWithInnerContext`): the iteration
 *   counter increments once per `while(true)` loop pass — i.e. once per node execution — and the
 *   `singleRunStrategy()` graph alternates an LLM-call node with a tool-execution node, so each
 *   tool call consumes roughly two iterations, not one. `20` is the correct production value; a
 *   live regression test ([cz.vutbr.fit.interlockSim.dispatcher.agents.KoogRealOllamaToolCallingTest])
 *   asserts no `AIAgentMaxNumberOfIterationsReachedException` on a multi-tool happy-path cycle at
 *   this value.
 * @property contextWindowTokens Fixed Ollama context window (`num_ctx`) requested for every prompt,
 *   via [ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.Fixed] (default 16,384).
 *   Koog's own default strategy ([ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.None])
 *   never sends `num_ctx` at all, which makes Ollama fall back to a hard 2048-token window — too
 *   small to hold the static station topology SP2b.8 loads into the system prompt once at agent
 *   construction. A `Fixed` value (rather than the adaptive `FitPrompt` strategy) is used
 *   deliberately: Ollama reloads the model every time `num_ctx` changes between requests, so a
 *   stable value avoids repeated reloads during a long-running simulation — **never** change this
 *   value mid-run.
 *
 *   The static per-tick system prompt (topology + instructions) measures ≈2,100 tokens against
 *   `qwen2.5:7b-instruct` on this project's synthetic-but-representative station fixture. That
 *   number alone would suggest 8,192 is generous. However, Koog's `singleRunStrategy()` resends
 *   the **entire accumulated conversation** on every LLM round trip within a single
 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent.decideAsync] call — up to
 *   [maxAgentIterations] node traversals — and full-network perception tools (e.g.
 *   `all_block_occupancies`) return a complete snapshot on every call, not a diff. A live
 *   measurement of 5 simulated tool-call/result rounds against a 60-block synthetic topology grew
 *   the prompt from 2,102 to 4,510 tokens (≈482 tokens/round) — so the worst-case prompt near the
 *   end of a `maxAgentIterations`-heavy cycle is materially larger than the static topology alone.
 *   16,384 was chosen over the initially-considered 8,192 for that reason: it keeps ~2× headroom
 *   above the measured growth trend (8,192 would risk **silent** truncation of the oldest
 *   messages — including the topology in the system prompt — rather than a visible error, since
 *   Ollama trims context that exceeds `num_ctx` instead of rejecting the request) while still
 *   capturing nearly all of 32,768's latency/memory cost reduction: measured cold-load on this dev
 *   machine dropped from 6.53s (32,768, 6.14 GiB VRAM) to 4.75s (16,384, 5.23 GiB VRAM) — 8,192
 *   measured 4.85s / 4.78 GiB, i.e. almost no further benefit over 16,384. Prompt-eval duration
 *   and decode throughput were unaffected by `num_ctx` in all three measurements, as expected:
 *   `num_ctx` is a KV-cache preallocation ceiling, not a per-token processing cost. Full
 *   methodology and raw numbers: `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`.
 *
 * **`maxTokens`/`topP` limitation:** as of Koog 1.1.1, `OllamaClient.execute()` only forwards
 * `temperature` (plus the computed context length) to Ollama's `/api/chat` request `options` —
 * `maxTokens` and `topP` have no wiring path to the real Ollama backend in this Koog version.
 * They are kept here as forward-looking configuration (a future Koog release, or a
 * `LLMParams.additionalProperties` escape hatch, may pick them up) but currently have no effect.
 */
data class OllamaExecutorConfig(
	val ollamaEndpoint: String = DEFAULT_OLLAMA_ENDPOINT,
	val modelName: String = DEFAULT_MODEL_NAME,
	val temperature: Float = DEFAULT_TEMPERATURE,
	val topP: Float = DEFAULT_TOP_P,
	val maxTokens: Int = DEFAULT_MAX_TOKENS,
	val inferenceTimeout: Duration = DEFAULT_INFERENCE_TIMEOUT,
	val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
	val maxAgentIterations: Int = DEFAULT_MAX_AGENT_ITERATIONS,
	val contextWindowTokens: Long = DEFAULT_CONTEXT_WINDOW_TOKENS
) {
	companion object {
		private const val OLLAMA_BASE_URL_ENV_VAR = "OLLAMA_BASE_URL"
		private const val DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434"
		private const val DEFAULT_MODEL_NAME = "qwen2.5:7b-instruct"
		private const val DEFAULT_TEMPERATURE = 0.28f
		private const val DEFAULT_TOP_P = 0.9f
		private const val DEFAULT_MAX_TOKENS = 1024
		private val DEFAULT_INFERENCE_TIMEOUT = Duration.ofSeconds(30)
		private const val DEFAULT_RETRY_ATTEMPTS = 3
		private const val DEFAULT_MAX_AGENT_ITERATIONS = 20

		// Right-sized from 32,768 to 16,384 — see contextWindowTokens KDoc for the measured
		// latency/memory rationale and why 8,192 was rejected in favor of this value.
		private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384L

		/**
		 * Create a production config with defaults (singleton).
		 *
		 * Used by [DispatcherAgentModule] to provide the global Ollama executor config.
		 *
		 * [ollamaEndpoint][OllamaExecutorConfig.ollamaEndpoint] defaults to `http://localhost:11434`,
		 * overridable via the `OLLAMA_BASE_URL` environment variable. This matters once
		 * dispatcher-agent is wired into the `app` container: on Windows/macOS Docker Desktop,
		 * `localhost` inside a container does not reach a native Ollama install or the host, even
		 * under `network_mode: host` — but `host.docker.internal` does (verified on Windows 11 +
		 * Docker Desktop 29.5.3, WSL2 backend, including from a `network_mode: host` container
		 * matching `app`'s actual config). Set `OLLAMA_BASE_URL=http://host.docker.internal:11434`
		 * in that scenario. `OLLAMA_BASE_URL` stays an environment variable, never a `-D` property
		 * or a [fileProperties] key: it is machine configuration (which host has Ollama), not a
		 * tunable dispatcher default.
		 *
		 * [modelName][OllamaExecutorConfig.modelName] and [temperature][OllamaExecutorConfig.temperature]
		 * resolve from [fileProperties] (committed `dispatcher-defaults.properties`, falling back to
		 * [DEFAULT_MODEL_NAME]/[DEFAULT_TEMPERATURE] on an absent/malformed entry) — the code-constant
		 * tier of Issue #834's `-D` property > committed file > code constant precedence. The `-D`
		 * property tier is applied one layer up, in `DispatcherAgentModule`'s
		 * `runConfig.model ?: base.modelName` override: [DispatcherRunConfig.fromProperties] reads
		 * the same keys from `-D` flags only (no file fallback there — see its KDoc "Configuration
		 * precedence" section for why splitting the file-read here, rather than duplicating it in
		 * both classes, avoids two competing sources of the same value) and wins when a value is
		 * given.
		 *
		 * @param env Environment variables to read the [ollamaEndpoint] override from; defaults to
		 *   the real process environment. Exposed as a parameter so tests can inject a fake map
		 *   instead of mutating real environment variables.
		 * @param fileProperties Committed-resource lookup for [modelName]/[temperature], defaulting
		 *   to [DispatcherDefaultsResource.shipped]. Injectable so tests can pin the file tier
		 *   without depending on the real classpath resource's contents.
		 */
		fun default(
			env: Map<String, String> = System.getenv(),
			fileProperties: (String) -> String? = { key -> DispatcherDefaultsResource.shipped.lookup(key) }
		): OllamaExecutorConfig =
			OllamaExecutorConfig(
				ollamaEndpoint = env[OLLAMA_BASE_URL_ENV_VAR]?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_ENDPOINT,
				modelName = resolveModelName(fileProperties),
				temperature = resolveTemperature(fileProperties)
			)

		/** File-tier resolution for [OllamaExecutorConfig.modelName]; see [default]'s KDoc. */
		private fun resolveModelName(fileProperties: (String) -> String?): String =
			fileProperties(DispatcherRunConfig.PROP_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_NAME

		/**
		 * File-tier resolution for [OllamaExecutorConfig.temperature]; see [default]'s KDoc. A
		 * present-but-unparseable value is a WARN plus [DEFAULT_TEMPERATURE], mirroring
		 * [DispatcherRunConfig]'s "log and fall back, never fail the run" policy for a bad `-D`
		 * value.
		 */
		private fun resolveTemperature(fileProperties: (String) -> String?): Float {
			val raw =
				fileProperties(DispatcherRunConfig.PROP_TEMPERATURE)?.takeIf { it.isNotBlank() }
					?: return DEFAULT_TEMPERATURE
			return raw.toFloatOrNull() ?: run {
				logger.warn {
					"Ignoring unparseable ${DispatcherRunConfig.PROP_TEMPERATURE}='$raw' in " +
						"dispatcher-defaults.properties; using $DEFAULT_TEMPERATURE"
				}
				DEFAULT_TEMPERATURE
			}
		}

		/**
		 * Create a test config for local Ollama testing (@Tag("ollama-test")).
		 *
		 * Reduces inference timeout to 10s for faster test feedback, keeps other defaults.
		 */
		fun forLocalTesting(): OllamaExecutorConfig =
			OllamaExecutorConfig(
				inferenceTimeout = Duration.ofSeconds(10)
			)

		/**
		 * Build the shared warning text for a setting that Koog 1.1.1 accepts but never forwards
		 * to Ollama (see [noOpSettingWarnings] and the class KDoc's "`maxTokens`/`topP`
		 * limitation").
		 */
		private fun noOpSettingWarning(
			settingName: String,
			value: Any
		): String =
			"$settingName=$value has no effect under Koog 1.1.1 — OllamaClient.execute() does not " +
				"forward it to Ollama. The value is retained for forward compatibility (a future Koog " +
				"release or an LLMParams.additionalProperties escape hatch may pick it up)."
	}

	/**
	 * Validate that this config specifies a tool-capable model.
	 *
	 * Rejects bare model tags that predate native function-calling (e.g. "mistral", "llama2").
	 * Accepted tags include version-pinned models like "qwen2.5:7b-instruct", "llama3.1:8b", etc.
	 * per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md §3.
	 *
	 * Enforced at runtime when the Ollama client is lazily initialized in [OllamaSimpleExecutor].
	 *
	 * @throws IllegalArgumentException if modelName is not tool-capable
	 */
	fun validateToolCapableModel() {
		val toolIncapable = setOf("mistral", "llama2")
		if (modelName in toolIncapable) {
			throw IllegalArgumentException(
				"Model '$modelName' does not support tool calling (function calling). " +
					"Use version-specific tag like 'mistral:7b-instruct-v0.3' or " +
					"'llama3.1:8b' per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md"
			)
		}
	}

	/**
	 * Warnings for settings that have no effect under the pinned Koog version (1.1.1).
	 *
	 * As of Koog 1.1.1, `OllamaClient.execute()` only forwards `temperature` (plus the computed
	 * context length) to Ollama's `/api/chat` `options` — [maxTokens] and [topP] have no wiring
	 * path to the real backend (see the class KDoc's "`maxTokens`/`topP` limitation"). A maintainer
	 * who tunes either expecting a behavior change gets none, and the silence is easily mistaken
	 * for the setting taking effect. This function surfaces each no-op setting currently set to a
	 * non-default value as a human-readable warning string; [OllamaSimpleExecutor] logs them once
	 * at executor construction (singleton → once per application startup).
	 *
	 * @return Warning messages for any no-op setting at a non-default value; empty when all
	 *   no-op settings are at their defaults (the common case, since [default] only overrides
	 *   the endpoint).
	 */
	fun noOpSettingWarnings(): List<String> =
		buildList {
			if (maxTokens != DEFAULT_MAX_TOKENS) add(noOpSettingWarning("maxTokens", maxTokens))
			if (topP != DEFAULT_TOP_P) add(noOpSettingWarning("topP", topP))
		}

	init {
		require(temperature in 0f..1f) { "temperature must be in [0, 1], got $temperature" }
		require(topP in 0f..1f) { "topP must be in [0, 1], got $topP" }
		require(maxTokens > 0) { "maxTokens must be positive, got $maxTokens" }
		require(retryAttempts >= 0) { "retryAttempts must be non-negative, got $retryAttempts" }
		require(maxAgentIterations > 0) { "maxAgentIterations must be positive, got $maxAgentIterations" }
		require(contextWindowTokens > 0) { "contextWindowTokens must be positive, got $contextWindowTokens" }
	}
}
