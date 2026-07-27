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

import java.time.Duration

/**
 * Configuration for the Ollama LLM executor (SP1.3 skeleton, Issue #548).
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
 * ## SP1 phasing
 *
 * - SP1.3 (this class): Configuration skeleton, endpoint + model selection
 * - SP1.5 (#550): Ollama backend initialization and client setup
 * - SP1.6 (#551): Tool executor wiring into Koog agent
 *
 * ## Design rationale
 *
 * Ollama executor config is a **singleton** because:
 * 1. Endpoint URL is runtime-global (same local Ollama for all agents)
 * 2. Model selection is global policy (all dispatcher agents use the same model)
 * 3. Timeout/retry settings are global tuning (not per-context)
 * 4. Config is immutable after creation → safe to share
 *
 * @property ollamaEndpoint HTTP endpoint of local Ollama instance (default: `http://localhost:11434`,
 *   overridable via the `OLLAMA_BASE_URL` environment variable — see [default])
 * @property modelName Ollama model tag, e.g. "qwen2.5:7b-instruct" (per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md)
 * @property temperature Model sampling temperature (0.0–1.0, default 0.28). Decided via Goal 10
 *   agent-architect + traffic-simulation-expert consultation after a live incident where the LLM
 *   hallucinated a nonexistent endpoint name (`"kA"`/`"kB"`) instead of copying one from its
 *   system prompt's topology. Per `docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md` §7 (A4), the LLM
 *   dispatcher is explicitly **not** this project's reproducibility anchor — `RuleBasedDispatcher`
 *   is, and acceptance is outcome-gated across seed-pinned runs, not decision-for-decision — so
 *   0.28 is **not** chosen for reproducibility. It is chosen for tool-calling correctness (§2's
 *   "reliably emits valid JSON" criterion): low enough to meaningfully reduce hallucinated tool
 *   arguments versus a higher temperature, while staying clear of near-greedy (~0.1) decoding,
 *   where small (7B-class) instruct models tend toward repetitive/degenerate tool-call loops — a
 *   failure mode §6's fallback/loop-guard mitigates but should not be relied on to mask. Revisit
 *   empirically once the §7 benchmark harness lands (tool-call success rate at 0.28 vs. higher).
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
 * @property contextWindowTokens Fixed Ollama context window (`num_ctx`) requested for every prompt,
 *   via [ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.Fixed] (default 32,768, matching
 *   Qwen2.5's documented context length). Koog's own default strategy
 *   ([ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.None]) never sends `num_ctx` at
 *   all, which makes Ollama fall back to a hard 2048-token window — too small to hold the static
 *   station topology SP2b.8 loads into the system prompt once at agent construction. A `Fixed`
 *   value (rather than the adaptive `FitPrompt` strategy) is used deliberately: Ollama reloads the
 *   model every time `num_ctx` changes between requests, so a stable value avoids repeated reloads
 *   during a long-running simulation.
 *
 * **`maxTokens`/`topP` limitation:** as of Koog 1.1.1, `OllamaClient.execute()` only forwards
 * `temperature` (plus the computed context length) to Ollama's `/api/chat` request `options` —
 * `maxTokens` and `topP` have no wiring path to the real Ollama backend in this Koog version.
 * They are kept here as forward-looking configuration (a future Koog release, or a
 * `LLMParams.additionalProperties` escape hatch, may pick them up) but currently have no effect.
 *
 * @since Issue #548 (SP1.3 — Goal 10)
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
		private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768L

		/**
		 * Create a production config with defaults (singleton).
		 *
		 * Used by [DispatcherAgentModule] to provide the global Ollama executor config.
		 *
		 * [ollamaEndpoint][OllamaExecutorConfig.ollamaEndpoint] defaults to `http://localhost:11434`,
		 * overridable via the `OLLAMA_BASE_URL` environment variable. This matters once
		 * dispatcher-agent is wired into the `app` container (Stage B, Issue #770): on
		 * Windows/macOS Docker Desktop, `localhost` inside a container does not reach a native
		 * Ollama install or the host, even under `network_mode: host` — but
		 * `host.docker.internal` does (verified 2026-07-19 on Windows 11 + Docker Desktop 29.5.3,
		 * WSL2 backend, including from a `network_mode: host` container matching `app`'s actual
		 * config). Set `OLLAMA_BASE_URL=http://host.docker.internal:11434` in that scenario.
		 *
		 * @param env Environment variables to read the override from; defaults to the real
		 *   process environment. Exposed as a parameter so tests can inject a fake map instead
		 *   of mutating real environment variables.
		 */
		fun default(env: Map<String, String> = System.getenv()): OllamaExecutorConfig =
			OllamaExecutorConfig(
				ollamaEndpoint = env[OLLAMA_BASE_URL_ENV_VAR]?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_ENDPOINT
			)

		/**
		 * Create a test config for local Ollama testing (@Tag("ollama-test")).
		 *
		 * Reduces inference timeout to 10s for faster test feedback, keeps other defaults.
		 */
		fun forLocalTesting(): OllamaExecutorConfig =
			OllamaExecutorConfig(
				inferenceTimeout = Duration.ofSeconds(10)
			)
	}

	/**
	 * Validate that this config specifies a tool-capable model.
	 *
	 * Rejects bare model tags that predate native function-calling (e.g. "mistral", "llama2").
	 * Accepted tags include version-pinned models like "qwen2.5:7b-instruct", "llama3.1:8b", etc.
	 * per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md §3.
	 *
	 * SP1.5 will enforce this validation at runtime when Ollama client is initialized.
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
	 * @since SP2b.9 review follow-up (PR #811)
	 */
	fun noOpSettingWarnings(): List<String> =
		buildList {
			if (maxTokens != DEFAULT_MAX_TOKENS) {
				add(
					"maxTokens=$maxTokens has no effect under Koog 1.1.1 — OllamaClient.execute() does not " +
						"forward it to Ollama. The value is retained for forward compatibility (a future Koog " +
						"release or an LLMParams.additionalProperties escape hatch may pick it up)."
				)
			}
			if (topP != DEFAULT_TOP_P) {
				add(
					"topP=$topP has no effect under Koog 1.1.1 — OllamaClient.execute() does not forward it " +
						"to Ollama. The value is retained for forward compatibility (a future Koog release or " +
						"an LLMParams.additionalProperties escape hatch may pick it up)."
				)
			}
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
