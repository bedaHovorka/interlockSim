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
 * Ollama AI executor for local LLM inference.
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
 * when a `Prompt` is built and executed, not here.
 *
 * ## Design rationale
 *
 * **Singleton scoping:** Ollama client is a stateful, expensive resource (network connection,
 * model cache). Created once at application startup, reused across all simulation contexts.
 *
 * **Single-point initialization:** This class centralizes Ollama setup, error handling, and
 * lifecycle management. Koin binds this executor as a singleton; agents receive it via DI.
 *
 * @property config Ollama endpoint, model name, and inference parameters (from Koin singleton)
 */
class OllamaSimpleExecutor(
	private val config: OllamaExecutorConfig
) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Set to `true` by the first [close] call, after which [getExecutor] rejects with
	 * [IllegalStateException]. [close] is terminal: a closed executor is not re-opened.
	 * `@Volatile` for visibility across threads; read by [getExecutor] before returning the
	 * cached executor so a post-close call observes the closed state.
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
	 * Held as an explicit [Lazy] (rather than only as a `by lazy` delegated property) so [close]
	 * can query [Lazy.isInitialized] directly instead of tracking a separate flag: `Lazy<T>`
	 * exposes `isInitialized()` as a real member — under the default `SYNCHRONIZED` mode (also
	 * true for `PUBLICATION`, but not `LazyThreadSafetyMode.NONE`) it is backed by a `@Volatile`
	 * field internally, so it is already visibility-safe across threads — it is only the
	 * unrelated `KProperty0.isInitialized()` reflection extension that is `lateinit`-only.
	 */
	private val promptExecutorLazy: Lazy<PromptExecutor> =
		lazy {
			logger.debug {
				"Creating Ollama executor: endpoint=${config.ollamaEndpoint}, model=${config.modelName}, " +
					"contextWindowTokens=${config.contextWindowTokens}"
			}

			// Warn once at startup about any no-op settings a maintainer may have tuned expecting an
			// effect (maxTokens/topP are not forwarded to Ollama under Koog 1.1.1 — see
			// OllamaExecutorConfig.noOpSettingWarnings). Singleton-scoped, so this fires exactly once
			// per application.
			config.noOpSettingWarnings().forEach { logger.warn { it } }

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
			// Fold tool results into named, error-flagged user text before they reach the transport.
			// They do reach the model without this, but Ollama's message DTO carries no tool_name and
			// no is_error field, so the model cannot tell which of the four actuators answered or
			// whether it was rejected. See ToolResultInliningPromptExecutor's KDoc.
			ToolResultInliningPromptExecutor(MultiLLMPromptExecutor(LLMProvider.Ollama to client))
		}

	/** Delegated accessor for [promptExecutorLazy]. */
	private val promptExecutor: PromptExecutor by promptExecutorLazy

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
	 * ### Usage
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
	 * Koin can wire shutdown hooks during context cleanup.
	 *
	 * ### Concurrency note
	 *
	 * A [close] call racing with a still-in-progress first [getExecutor] initialization is not
	 * synchronized against it: [promptExecutorLazy]'s `isInitialized()` only flips to `true`
	 * after the lazy initializer returns, so a [close] that observes `false` mid-initialization
	 * will skip closing the executor that finishes building moments later, leaking its
	 * underlying [OllamaClient] connection. This is a narrow, pre-existing race (not introduced
	 * by the current implementation) and is considered acceptable given [close] is only called
	 * once, at application/context shutdown, well after normal [getExecutor] usage has settled.
	 */
	fun close() {
		closed = true
		// Only close if the executor has actually been built — avoids triggering the network
		// connection just to immediately tear it down.
		if (promptExecutorLazy.isInitialized()) {
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
