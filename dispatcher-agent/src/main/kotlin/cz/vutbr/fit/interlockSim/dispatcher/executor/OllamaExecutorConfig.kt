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
 * @property ollamaEndpoint HTTP endpoint of local Ollama instance (default: `http://localhost:11434`)
 * @property modelName Ollama model tag, e.g. "qwen2.5:7b-instruct" (per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md)
 * @property temperature Model sampling temperature (0.0–1.0, default 0.7)
 * @property topP Nucleus sampling parameter (0.0–1.0, default 0.9)
 * @property maxTokens Maximum tokens in response (default 1024)
 * @property inferenceTimeout Maximum wall-clock time for inference (default 30 seconds per #532 latency constraint)
 * @property retryAttempts Number of retries on transient failure (default 3)
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
	val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS
) {
	companion object {
		private const val DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434"
		private const val DEFAULT_MODEL_NAME = "qwen2.5:7b-instruct"
		private const val DEFAULT_TEMPERATURE = 0.7f
		private const val DEFAULT_TOP_P = 0.9f
		private const val DEFAULT_MAX_TOKENS = 1024
		private val DEFAULT_INFERENCE_TIMEOUT = Duration.ofSeconds(30)
		private const val DEFAULT_RETRY_ATTEMPTS = 3

		/**
		 * Create a production config with defaults (singleton).
		 *
		 * Used by [DispatcherAgentModule] to provide the global Ollama executor config.
		 */
		fun default(): OllamaExecutorConfig = OllamaExecutorConfig()

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

	init {
		require(temperature in 0f..1f) { "temperature must be in [0, 1], got $temperature" }
		require(topP in 0f..1f) { "topP must be in [0, 1], got $topP" }
		require(maxTokens > 0) { "maxTokens must be positive, got $maxTokens" }
		require(retryAttempts >= 0) { "retryAttempts must be non-negative, got $retryAttempts" }
	}
}
