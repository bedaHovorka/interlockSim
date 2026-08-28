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

import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability

/**
 * Outcome of an [OllamaConnectivityChecker] proof-of-connection check (SP1.3, Issue #548).
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
sealed interface OllamaConnectivityResult {
	/**
	 * The configured [OllamaExecutorConfig.modelName] is pulled on the target Ollama
	 * instance and reports tool-calling ([LLMCapability.Tools]) support.
	 */
	data object Available : OllamaConnectivityResult

	/**
	 * The target Ollama instance is reachable, but the configured model is not pulled.
	 */
	data object ModelNotPulled : OllamaConnectivityResult

	/**
	 * The configured model is pulled but does not report [LLMCapability.Tools] support
	 * — the exact failure mode documented in `~/work/roi-hunter-assigment`'s
	 * `OLLAMA_INTEGRATION_STATUS.md` (a model silently lacking tool support sank 14 tests).
	 */
	data object ModelLacksToolSupport : OllamaConnectivityResult
}

/**
 * Proof-of-connection check for [OllamaExecutorConfig] against a real Ollama instance
 * (SP1.3, Issue #548).
 *
 * Split into a network-free decision function ([evaluate]) and a live network call
 * ([check]) so the decision logic is unit-testable without a running Ollama instance,
 * while [check] is exercised by a `@Tag("ollama-test")` test that a "clever"
 * `dispatcher-agent` `integrationTest` Gradle task includes only when Ollama is actually
 * reachable.
 *
 * This is config/connectivity verification only — it does not construct a Koog `AIAgent`
 * or drive a tool-calling loop (that is SP1.4/#549+ scope).
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
object OllamaConnectivityChecker {
	/**
	 * Decide the connectivity outcome from a model's reported capabilities.
	 *
	 * @param capabilities Capabilities of the configured model as reported by Ollama, or
	 *   `null` if the model is not pulled on the target instance.
	 */
	fun evaluate(capabilities: List<LLMCapability>?): OllamaConnectivityResult =
		when {
			capabilities == null -> OllamaConnectivityResult.ModelNotPulled
			LLMCapability.Tools !in capabilities -> OllamaConnectivityResult.ModelLacksToolSupport
			else -> OllamaConnectivityResult.Available
		}

	/**
	 * Query the Ollama instance described by [config] for its configured model and
	 * evaluate the result via [evaluate].
	 *
	 * @throws java.io.IOException (or a Koog client exception) if the Ollama endpoint is
	 *   unreachable — callers that want a graceful "unreachable" outcome instead of a
	 *   thrown exception should catch around this call; the Gradle-level probe used by
	 *   `dispatcher-agent`'s `integrationTest` task decides reachability separately and
	 *   before this function is even invoked (see `dispatcher-agent/build.gradle.kts`).
	 */
	suspend fun check(config: OllamaExecutorConfig): OllamaConnectivityResult {
		val client = OllamaClient(baseUrl = config.ollamaEndpoint)
		try {
			// pullIfMissing = false: a connectivity check must never trigger a multi-GB
			// background model download as a side effect.
			val card = client.getModelOrNull(config.modelName, pullIfMissing = false)
			return evaluate(card?.capabilities)
		} finally {
			client.close()
		}
	}
}
