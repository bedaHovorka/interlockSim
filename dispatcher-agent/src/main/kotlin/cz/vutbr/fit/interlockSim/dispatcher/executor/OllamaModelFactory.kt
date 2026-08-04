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

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Builds the Koog [LLModel] for a given Ollama model tag (SP2b.9, Issue #566).
 *
 * Koog's own `ai.koog.prompt.executor.ollama.client.OllamaModels` catalog has no entry for this
 * project's configured default `"qwen2.5:7b-instruct"` (only smaller tags like `"qwen2.5:0.5b"`
 * are predefined), so the model must be constructed at runtime from
 * [OllamaExecutorConfig.modelName] rather than looked up from the catalog.
 *
 * Kept as its own small, pure, independently-testable object rather than folded into
 * [OllamaSimpleExecutor] (whose own KDoc notes that model/prompt parameters are threaded through
 * per-call, not at executor-construction time) or `KoogAgentFactory` (which would require Koin
 * scope wiring just to unit test this mapping).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
object OllamaModelFactory {
	/**
	 * Capabilities every dispatcher model needs: temperature control, basic JSON-schema tool
	 * arguments, and native tool/function calling. Matches every tool-capable entry in Koog's own
	 * `OllamaModels` catalog (e.g. `Alibaba.QWEN_2_5_05B`).
	 */
	private val TOOL_CAPABLE_CAPABILITIES =
		listOf(LLMCapability.Temperature, LLMCapability.Schema.JSON.Basic, LLMCapability.Tools)

	/**
	 * Capabilities for constrained-JSON mode (SP2c.13, Issue #836): temperature control and
	 * basic JSON-schema output, **without** native tool/function calling.
	 *
	 * The `Tools` capability is intentionally absent: the constrained-JSON arm uses Ollama's
	 * `format` parameter instead of the `tools` field, so declaring `Tools` would be misleading
	 * and could trigger Koog's tool-calling code path if this model were ever accidentally
	 * passed to [ai.koog.agents.core.agent.AIAgent]. Keeping the two model descriptors separate
	 * enforces the mutual-exclusivity-by-construction requirement from SP2c.13.
	 */
	private val CONSTRAINED_JSON_CAPABILITIES =
		listOf(LLMCapability.Temperature, LLMCapability.Schema.JSON.Basic)

	/**
	 * Builds a tool-capable [LLModel] for the Ollama-hosted [modelName].
	 *
	 * @param modelName Ollama model tag, e.g. `"qwen2.5:7b-instruct"`.
	 * @param contextLength The model's real maximum context length in tokens, so
	 *   [ai.koog.prompt.executor.ollama.client.ContextWindowStrategy.Fixed] can clamp a
	 *   configured fixed context window against it instead of silently exceeding it.
	 */
	fun toolCapableModel(
		modelName: String,
		contextLength: Long
	): LLModel =
		LLModel(
			provider = LLMProvider.Ollama,
			id = modelName,
			capabilities = TOOL_CAPABLE_CAPABILITIES,
			contextLength = contextLength
		)

	/**
	 * Builds a constrained-JSON-capable [LLModel] for the Ollama-hosted [modelName]
	 * (SP2c.13, Issue #836).
	 *
	 * The returned model descriptor declares [LLMCapability.Schema.JSON.Basic] but **not**
	 * [LLMCapability.Tools]. This makes the descriptor unsuitable for use with Koog's
	 * `AIAgent` tool-calling loop, which requires `Tools` — mutual exclusivity with the
	 * tool-calling arm is therefore enforced at the model-descriptor level.
	 *
	 * @param modelName Ollama model tag, e.g. `"qwen2.5:7b-instruct"`.
	 * @param contextLength The model's real maximum context length in tokens.
	 */
	fun constrainedJsonCapableModel(
		modelName: String,
		contextLength: Long
	): LLModel =
		LLModel(
			provider = LLMProvider.Ollama,
			id = modelName,
			capabilities = CONSTRAINED_JSON_CAPABILITIES,
			contextLength = contextLength
		)
}
