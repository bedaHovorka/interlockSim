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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends a minimal warm-up request to Ollama before the first real inference cycle.
 *
 * ## Rationale (Issue #815)
 *
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] guards each LLM dispatch
 * cycle with [OllamaExecutorConfig.inferenceTimeout] (default 30 s).  On a cold Ollama instance
 * the very first `/api/chat` call triggers a full model load — which can consume the entire
 * timeout budget and cause a spurious fallback to
 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] even though the model is perfectly
 * functional.
 *
 * [warmUp] fires a **minimal** `/api/generate` request (empty prompt, `num_predict=1`) **before**
 * the simulation's first dispatch cycle begins, decoupling model-load latency from the per-cycle
 * timeout entirely.  Because [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]
 * calls [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory.createAgent] outside of its
 * `withTimeout` block, the warm-up time is not charged against `inferenceTimeout`.
 *
 * The request uses the same `num_ctx` as the production
 * [ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.Fixed] value
 * ([OllamaExecutorConfig.contextWindowTokens]) so Ollama does **not** reload the model between
 * the warm-up and the first real inference (a context-window change forces a reload and would
 * defeat the purpose — see [OllamaExecutorConfig.contextWindowTokens] KDoc for the full
 * `ContextWindowStrategy.Fixed` rationale).
 *
 * ## Failure handling
 *
 * Warm-up failures are **non-fatal**: network errors, connection refusals (Ollama not yet
 * started), or unexpected HTTP responses are caught, logged at `WARN` level, and the call
 * returns normally.  The simulation continues; the first real cycle may be slower, but the
 * system will not crash.
 *
 * ## Usage
 *
 * ```kotlin
 * // In KoogAgentFactory.createAgent():
 * OllamaModelPrewarmer.warmUp(ollamaConfig)
 * // ... build agent as usual
 * ```
 *
 * For `@Tag("ollama-test")` JUnit tests, use the companion
 * `OllamaPrewarmExtension` (defined in the test source set) to ensure the model is already
 * loaded before the first test method runs.
 *
 * @since Issue #815 (SP2b.9 warm-up follow-up — Goal 10)
 */
object OllamaModelPrewarmer {
	private val logger = KotlinLogging.logger {}

	/**
	 * Wall-clock ceiling for the entire warm-up request (connection + model load + response).
	 *
	 * Intentionally longer than [OllamaExecutorConfig.DEFAULT_INFERENCE_TIMEOUT] (30 s): on a
	 * cold system the very first model load can exceed 30 s, and absorbing that latency here —
	 * outside the per-cycle [inferenceTimeout] budget — is the whole point of this step.
	 */
	val WARMUP_TIMEOUT: Duration = Duration.ofSeconds(120)

	/**
	 * Maximum time to wait for the TCP connection to be established.
	 *
	 * A short value ensures fast failure when Ollama is not running (connection refused on
	 * localhost is immediate, but a firewalled remote endpoint would otherwise block for the
	 * OS default).  The overall [WARMUP_TIMEOUT] still applies once connected.
	 */
	private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

	/**
	 * Warms up the Ollama model by sending a minimal `/api/generate` request.
	 *
	 * Non-fatal: any exception is caught and logged at `WARN` level; the call always returns
	 * normally so agent construction continues even if Ollama is temporarily unavailable.
	 *
	 * @param config Endpoint and model configuration.  The warm-up uses [config.modelName] and
	 *   [OllamaExecutorConfig.contextWindowTokens] so the model is loaded with the same
	 *   context window as the production requests.
	 */
	suspend fun warmUp(config: OllamaExecutorConfig) {
		logger.info {
			"Warming up Ollama model '${config.modelName}' at ${config.ollamaEndpoint} …"
		}
		try {
			val statusCode = doWarmUp(config)
			if (statusCode == 200) {
				logger.info { "Ollama model '${config.modelName}' preloaded successfully (warm-up complete)" }
			} else {
				logger.warn {
					"Ollama warm-up returned HTTP $statusCode for model '${config.modelName}' — " +
						"first inference may be slower than expected"
				}
			}
		} catch (e: Exception) {
			logger.warn(e) {
				"Ollama warm-up failed for model '${config.modelName}' (non-fatal) — " +
					"ensure Ollama is running at ${config.ollamaEndpoint}; " +
					"first dispatch cycle may be slower due to cold model load"
			}
		}
	}

	/**
	 * Issues the HTTP request on [Dispatchers.IO] and returns the HTTP response status code.
	 */
	private suspend fun doWarmUp(config: OllamaExecutorConfig): Int =
		withContext(Dispatchers.IO) {
			val client =
				HttpClient.newBuilder()
					.connectTimeout(CONNECT_TIMEOUT)
					.build()
			val request =
				HttpRequest.newBuilder()
					.uri(URI.create("${config.ollamaEndpoint}/api/generate"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(config)))
					.timeout(WARMUP_TIMEOUT)
					.build()
			client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
		}

	/**
	 * Builds the minimal Ollama `/api/generate` JSON body for the warm-up request.
	 *
	 * - `prompt` — empty string: no tokens to process, just loads the model into memory.
	 * - `stream` — `false`: collect the full response before returning (simpler error handling).
	 * - `num_predict` — `1`: generate at most one output token to minimise compute cost.
	 * - `num_ctx` — [OllamaExecutorConfig.contextWindowTokens]: matches the
	 *   [ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.Fixed] value used in
	 *   production so Ollama does not reload the model when the first real inference starts.
	 *
	 * @param config Source of [OllamaExecutorConfig.modelName] and
	 *   [OllamaExecutorConfig.contextWindowTokens].
	 * @return JSON string suitable for posting to `{ollamaEndpoint}/api/generate`.
	 */
	internal fun buildRequestBody(config: OllamaExecutorConfig): String =
		"""{"model":"${config.modelName}","prompt":"","stream":false,"options":{"num_predict":1,"num_ctx":${config.contextWindowTokens}}}"""
}
