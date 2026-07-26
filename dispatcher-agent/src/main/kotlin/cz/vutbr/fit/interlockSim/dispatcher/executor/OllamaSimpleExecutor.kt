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

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Ollama AI executor for local LLM inference (SP1.5, Issue #550).
 *
 * ## Responsibility
 *
 * Builds a Koog [MultiLLMPromptExecutor] wrapping a single [OllamaClient] and manages its
 * lifecycle. Provides a single entry point for creating and configuring the LLM executor
 * backend with a local Ollama instance.
 *
 * **Why not Koog's `simpleOllamaAIExecutor()` convenience function:** it lives in module
 * `ai.koog:prompt-executor-llms-all`, which is not published as a standalone artifact at the
 * pinned `koogVersion` (1.0.0) — only pre-release `1.0.0-beta-preview*` versions exist on Maven
 * Central for that module. This class does exactly what that function does internally
 * (`MultiLLMPromptExecutor(LLMProvider.Ollama to OllamaClient(baseUrl = ...))`), using only
 * `prompt-executor-model` / `prompt-executor-ollama-client` / `prompt-llm`, which are already
 * transitive dependencies of `koog-agents` at 1.0.0.
 *
 * - Creates one shared [PromptExecutor] per application (singleton), backed by an [OllamaClient]
 *   at [OllamaExecutorConfig.ollamaEndpoint]
 * - Validates the configured model is tool-capable (deferred to the first [getExecutor] call,
 *   not at Koin wiring / construction time — see [getExecutor])
 * - Provides the executor to Koog agents for LLM-based decision-making
 *
 * Model/temperature/topP/maxTokens from [OllamaExecutorConfig] are per-call `Prompt`/`LLModel`
 * parameters in Koog's API, not executor-construction parameters — they are threaded through
 * when a `Prompt` is built and executed (SP1.6, #551), not here.
 *
 * ## Design rationale
 *
 * **Singleton scoping:** Ollama client is a stateful, expensive resource (network connection,
 * model cache). Created once at application startup, reused across all simulation contexts.
 *
 * **Single-point initialization:** This class centralizes Ollama setup, error handling, and
 * lifecycle management. Koin binds this executor as a singleton; agents receive it via DI.
 *
 * ## SP1 phasing
 *
 * - SP1.3 (#548): Configuration skeleton and connectivity checker
 * - **SP1.5 (#550): Ollama executor backend (THIS CLASS) and Koin binding**
 * - SP1.6 (#551): Full Koog agent construction wiring this executor + tool definitions
 *
 * ## Relationship to SP1.3 and SP1.6
 *
 * - **SP1.3 ([OllamaExecutorConfig])**: Configuration and validation only; no executor
 * - **SP1.5 (this class)**: Executor initialization; validation deferred from config to runtime
 * - **SP1.6 ([KoogAgentFactory] and [AgentService])**: Receives this executor and wires it into agents
 *
 * @property config Ollama endpoint, model name, and inference parameters (from Koin singleton)
 *
 * @since Issue #550 (SP1.5 — Goal 10)
 */
class OllamaSimpleExecutor(
	private val config: OllamaExecutorConfig
) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Tracks whether [promptExecutor] has been built, so [close] doesn't itself trigger lazy
	 * initialization of a network connection just to immediately tear it down. `by lazy`
	 * delegated properties don't expose `KProperty0.isInitialized` (that reflection facility
	 * is `lateinit`-only), so this flag is tracked explicitly. `@Volatile` for visibility
	 * across threads; set only after [promptExecutor]'s initializer completes successfully, so a
	 * failed construction leaves it `false` and a retry is possible on the next [getExecutor]
	 * call.
	 */
	@Volatile
	private var executorInitialized = false

	/**
	 * Set to `true` by the first [close] call, after which [getExecutor] rejects with
	 * [IllegalStateException]. [close] is terminal: a closed executor is not re-opened.
	 * `@Volatile` for visibility across threads; read by [getExecutor] before returning the
	 * cached executor so a post-close call observes the closed state.
	 *
	 * @since SP1.5 — part of the [close] / [getExecutor] terminal contract (PR #767 review)
	 */
	@Volatile
	private var closed = false

	/**
	 * Shared Koog prompt executor (lazy-initialized on first access).
	 *
	 * Constructed as a [MultiLLMPromptExecutor] wrapping a single [OllamaClient] at
	 * [OllamaExecutorConfig.ollamaEndpoint] — the same construction Koog's
	 * `simpleOllamaAIExecutor()` convenience function performs internally (see the class-level
	 * doc for why we don't depend on that function directly). Lazy initialization defers network
	 * connectivity check until the executor is actually used, not at Koin module startup — this
	 * allows applications to start even if Ollama is temporarily unavailable (e.g., in offline
	 * dev scenarios).
	 *
	 * The executor is a heavyweight stateful resource (network connection); creating it once
	 * per application and reusing it across all agents minimizes overhead.
	 *
	 * @since SP1.5 — initialized on first call to [getExecutor]
	 */
	private val promptExecutor: PromptExecutor by lazy {
		logger.debug {
			"Creating Ollama executor: endpoint=${config.ollamaEndpoint}, model=${config.modelName}, " +
				"contextWindowTokens=${config.contextWindowTokens}"
		}

		// Validate that the model is tool-capable before constructing the executor
		config.validateToolCapableModel()

		// SP2b.9: request a Fixed context window rather than Koog's default (None), which never
		// sends `num_ctx` at all and leaves Ollama defaulting to a 2048-token window — too small
		// to hold the static station topology loaded into the system prompt (see
		// OllamaExecutorConfig.contextWindowTokens KDoc for the full rationale).
		val client =
			OllamaClient(
				baseUrl = config.ollamaEndpoint,
				contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(config.contextWindowTokens)
			)
		val result = MultiLLMPromptExecutor(LLMProvider.Ollama to client)
		executorInitialized = true // set only after construction succeeds
		result
	}

	/**
	 * Get the Koog prompt executor for LLM-based agent decisions.
	 *
	 * Returns a lazy-initialized shared executor. First call triggers:
	 * 1. Model validation (checks tool-capability support)
	 * 2. Ollama client + executor construction (network connection)
	 *
	 * Subsequent calls return the cached executor without re-initializing.
	 *
	 * ### Error handling
	 *
	 * - **Connection failure:** the underlying `OllamaClient` throws; propagates to caller
	 * - **Model not pulled:** the underlying `OllamaClient` throws; propagates to caller
	 * - **Model lacks tool support:** [OllamaExecutorConfig.validateToolCapableModel] throws;
	 *   propagates to caller
	 *
	 * ### Usage (SP1.6+)
	 *
	 * ```kotlin
	 * val executor = OllamaSimpleExecutor(config)
	 * val promptExecutor = executor.getExecutor()
	 * // Pass promptExecutor to Koog agent for LLM calls
	 * ```
	 *
	 * @return Koog [PromptExecutor] ready for LLM inference
	 * @throws IllegalArgumentException if model is not tool-capable
	 * @throws IllegalStateException if [close] has already been called (terminal contract)
	 * @throws java.io.IOException if Ollama endpoint is unreachable
	 * @throws Exception if model is not pulled on the Ollama instance
	 *
	 * @since SP1.5
	 */
	fun getExecutor(): PromptExecutor {
		if (closed) {
			throw IllegalStateException(
				"OllamaSimpleExecutor has been closed; getExecutor() must not be called after close()"
			)
		}
		return promptExecutor
	}

	/**
	 * Close the underlying Ollama-backed executor (resource cleanup).
	 *
	 * Called during application shutdown or context disposal. Safe to call multiple times;
	 * idempotent (closing an already-closed executor has no effect).
	 *
	 * ### Terminal contract
	 *
	 * [close] is **terminal**: once called, [getExecutor] rejects with [IllegalStateException]
	 * rather than handing out a closed executor. This class does not support re-opening after
	 * close — a singleton that has been closed is meant to be replaced by Koin with a fresh
	 * instance for the next application run. [closed] is set unconditionally (even if the
	 * executor was never initialized) so a post-close [getExecutor] fails fast without touching
	 * the network.
	 *
	 * ### Design note
	 *
	 * Koog's [PromptExecutor] implements `AutoCloseable`; we explicitly expose [close] so that
	 * Koin can wire shutdown hooks (in SP1.6+, this will be called during context cleanup).
	 *
	 * @since SP1.5
	 */
	fun close() {
		closed = true
		// Only close if the executor has actually been built — avoids triggering the network
		// connection just to immediately tear it down.
		if (executorInitialized) {
			logger.debug { "Closing Ollama executor" }
			try {
				promptExecutor.close()
			} catch (e: InterruptedException) {
				// Preserve the interrupt status rather than silently swallowing it.
				Thread.currentThread().interrupt()
				logger.warn(e) { "Interrupted while closing Ollama executor" }
			} catch (e: Exception) {
				logger.warn(e) { "Exception closing Ollama executor" }
			}
		}
	}
}
