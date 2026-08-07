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

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.flow.Flow

/**
 * [PromptExecutor] decorator that folds each [MessagePart.Tool.Result] into ordinary user text
 * naming the tool that produced it and whether it failed (Issue #847 round 3).
 *
 * ## Why
 *
 * Koog 1.1.1 *does* forward tool results to Ollama: `Prompt.toOllamaChatMessages` emits every
 * tool-result part as its own `OllamaChatMessageDTO(role = "tool", …)`, and
 * `KoogRealOllamaToolCallingTest.a rejected tool argument is corrected on a later call` proves
 * empirically that a real model reads such a result and corrects its next call from it. PR #891's
 * round-2 comment claimed the opposite — that `OllamaConverters.kt:115` drops them — but that line
 * is inside a helper assembling only the *text* half of a user turn, which skips tool parts by
 * design because the caller emits them separately. The `"Skipping unsupported message part"` WARN
 * it logs is noise, not data loss.
 *
 * What the Ollama transport genuinely cannot carry is everything *about* a result:
 *
 * 1. `OllamaChatMessageDTO` declares only `role`/`content`/`thinking`/`images`/`tool_calls` — no
 *    `tool_name`, no `is_error`. With four actuators on the surface, the model receives an
 *    anonymous payload and cannot tell which call it answers, nor that it was a rejection.
 * 2. A `Message.User` carrying only tool results still produces a `role="user"` message with empty
 *    content, so the wire sequence becomes `assistant(tool_calls) → user("") → tool(…)`.
 *
 * Plain text naming the tool and its outcome carries strictly more than the `role="tool"` message
 * can, and because `OllamaConverters`' `filterIsInstance<MessagePart.Tool.Result>()` then matches
 * nothing, the empty turn and the per-part WARN disappear with it.
 *
 * Applied to every provider rather than Ollama alone: the rewrite is a lossless upgrade for any
 * transport, and gating it on provider would make the prompt the model sees depend on which client
 * happened to be configured.
 *
 * @property delegate The real executor this one wraps; receives the rewritten [Prompt].
 *
 * @since Issue #847 (round 3)
 */
class ToolResultInliningPromptExecutor(
	private val delegate: PromptExecutor
) : PromptExecutor() {
	override suspend fun execute(
		prompt: Prompt,
		model: LLModel,
		tools: List<ToolDescriptor>
	): Message.Assistant = delegate.execute(prompt.withInlinedToolResults(), model, tools)

	override fun executeStreaming(
		prompt: Prompt,
		model: LLModel,
		tools: List<ToolDescriptor>
	): Flow<StreamFrame> = delegate.executeStreaming(prompt.withInlinedToolResults(), model, tools)

	override suspend fun executeMultipleChoices(
		prompt: Prompt,
		model: LLModel,
		tools: List<ToolDescriptor>
	): LLMChoice = delegate.executeMultipleChoices(prompt.withInlinedToolResults(), model, tools)

	override suspend fun moderate(
		prompt: Prompt,
		model: LLModel
	): ModerationResult = delegate.moderate(prompt.withInlinedToolResults(), model)

	override suspend fun models(): List<LLModel> = delegate.models()

	override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
		delegate.getStandardJsonSchemaGenerator(model)

	override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
		delegate.getBasicJsonSchemaGenerator(model)

	override fun close() = delegate.close()
}

/**
 * Returns this prompt with every user message's tool results rewritten as text, or the same
 * instance when there is nothing to rewrite.
 */
private fun Prompt.withInlinedToolResults(): Prompt {
	val needsRewrite = messages.any { it is Message.User && it.parts.any { part -> part is MessagePart.Tool.Result } }
	return if (needsRewrite) {
		copy(messages = messages.map { if (it is Message.User) it.withInlinedToolResults() else it })
	} else {
		this
	}
}

private fun Message.User.withInlinedToolResults(): Message.User {
	val results = parts.filterIsInstance<MessagePart.Tool.Result>()
	if (results.isEmpty()) return this

	val retained = parts.filter { it !is MessagePart.Tool.Result }
	// Separated from any retained text so the two do not run together in the converter's
	// StringBuilder, which concatenates text parts with no delimiter of its own.
	val separator = if (retained.any { it is MessagePart.Text }) "\n" else ""
	val rendered = results.joinToString(separator = "\n", prefix = separator) { it.render() }
	return copy(parts = retained + MessagePart.Text(rendered))
}

private fun MessagePart.Tool.Result.render(): String =
	if (isError) {
		"Tool $tool returned an error: $output"
	} else {
		"Tool $tool returned: $output"
	}
