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
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Unit tests for [ToolResultInliningPromptExecutor] (Issue #847 round 3).
 *
 * ## What this class exists to fix
 *
 * Koog 1.1.1 does forward tool results to Ollama — `Prompt.toOllamaChatMessages` emits each
 * [MessagePart.Tool.Result] as its own `OllamaChatMessageDTO(role = "tool", …)`, and
 * `KoogRealOllamaToolCallingTest.a rejected tool argument is corrected on a later call` proves
 * empirically that a real model reads and acts on them. (PR #891's round-2 comment claimed the
 * opposite; it misread the `"Skipping unsupported message part"` WARN, which comes from a helper
 * that assembles only the *text* half of a user turn and therefore skips tool parts by design.)
 *
 * What the Ollama path genuinely loses is everything *about* the result:
 *
 * 1. `OllamaChatMessageDTO` has no `tool_name` and no `is_error` field, so the model receives an
 *    anonymous, unflagged payload and cannot tell which of the four actuators produced it — nor
 *    that it was a rejection rather than a success.
 * 2. A `Message.User` carrying only tool results still yields a `role="user"` message with empty
 *    content, so the wire sequence is `assistant(tool_calls) → user("") → tool(…)`.
 *
 * Folding each tool result into ordinary user *text* that names the tool and states whether it
 * failed carries strictly more information than the `role="tool"` message can, and — because
 * `OllamaConverters`' `filterIsInstance<MessagePart.Tool.Result>()` then finds nothing — removes
 * the empty turn and the per-part WARN with it.
 *
 * @since Issue #847 (round 3)
 */
@DisplayName("ToolResultInliningPromptExecutor folds tool results into named, flagged user text")
class ToolResultInliningPromptExecutorTest {
	/** Captures the [Prompt] the decorator forwards, so tests can assert on the rewrite. */
	private class CapturingExecutor : PromptExecutor() {
		var capturedPrompt: Prompt? = null
			private set

		override suspend fun execute(
			prompt: Prompt,
			model: LLModel,
			tools: List<ToolDescriptor>
		): Message.Assistant {
			capturedPrompt = prompt
			return Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo(Instant.DISTANT_PAST))
		}

		override fun executeStreaming(
			prompt: Prompt,
			model: LLModel,
			tools: List<ToolDescriptor>
		): Flow<StreamFrame> {
			capturedPrompt = prompt
			return emptyFlow()
		}

		override suspend fun moderate(
			prompt: Prompt,
			model: LLModel
		): ModerationResult {
			capturedPrompt = prompt
			throw UnsupportedOperationException("not used by these tests")
		}

		override fun close() = Unit
	}

	private val model = LLModel(provider = LLMProvider.Ollama, id = "test-model", capabilities = emptyList())

	private fun userWith(vararg parts: MessagePart.RequestPart): Message.User =
		Message.User(parts = parts.toList(), metaInfo = RequestMetaInfo.Empty)

	private fun forward(vararg messages: Message): Prompt {
		val delegate = CapturingExecutor()
		runBlocking {
			ToolResultInliningPromptExecutor(delegate).execute(
				Prompt(messages = messages.toList(), id = "test"),
				model,
				emptyList()
			)
		}
		return checkNotNull(delegate.capturedPrompt) { "delegate was never called" }
	}

	private fun textOf(message: Message): String =
		message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

	@Test
	@DisplayName("the rewritten text names the tool that produced the result")
	fun inlinedTextNamesTheTool() {
		val rewritten =
			forward(
				userWith(
					MessagePart.Tool.Result(
						tool = "request_route",
						output = "Unknown fromEndpointName 'k1' — valid names are: A, B",
						isError = true
					)
				)
			)

		assertThat(textOf(rewritten.messages.single())).contains("request_route")
	}

	@Test
	@DisplayName("an error result is flagged as an error and keeps its full output text")
	fun errorResultIsFlaggedAndPreserved() {
		val output = "Unknown fromEndpointName 'k1' — valid names are: A, B"
		val rewritten =
			forward(userWith(MessagePart.Tool.Result(tool = "request_route", output = output, isError = true)))

		val text = textOf(rewritten.messages.single())
		assertThat(text).contains("error")
		assertThat(text).contains(output)
	}

	@Test
	@DisplayName("a successful result is not flagged as an error")
	fun successResultIsNotFlaggedAsError() {
		val rewritten =
			forward(
				userWith(
					MessagePart.Tool.Result(
						tool = "approve_train",
						output = "emitted approve_train trainId=Train #1",
						isError = false
					)
				)
			)

		val text = textOf(rewritten.messages.single())
		assertThat(text).contains("approve_train")
		assertThat(text).doesNotContain("error")
	}

	/**
	 * The defect-fixing assertion. While a tool-result part survives, `OllamaConverters` emits both
	 * an empty `role="user"` message and a `logger.warn` for the same part.
	 */
	@Test
	@DisplayName("no tool-result part survives, so no empty user turn and no WARN is produced")
	fun noToolResultPartSurvives() {
		val rewritten =
			forward(
				userWith(
					MessagePart.Tool.Result(tool = "request_route", output = "rejected", isError = true),
					MessagePart.Tool.Result(tool = "no_op", output = "emitted no_op", isError = false)
				)
			)

		assertThat(
			rewritten.messages
				.single()
				.parts
				.filterIsInstance<MessagePart.Tool.Result>()
		).isEmpty()
	}

	@Test
	@DisplayName("existing user text is kept alongside the inlined results")
	fun existingUserTextIsKept() {
		val rewritten =
			forward(
				userWith(
					MessagePart.Text("Cycle 7: queued trains are Train #1."),
					MessagePart.Tool.Result(tool = "request_route", output = "rejected", isError = true)
				)
			)

		val text = textOf(rewritten.messages.single())
		assertThat(text).contains("Cycle 7: queued trains are Train #1.")
		assertThat(text).contains("rejected")
	}

	@Test
	@DisplayName("a user message carrying no tool results is forwarded unchanged")
	fun userMessageWithoutToolResultsIsUnchanged() {
		val original = userWith(MessagePart.Text("Cycle 7: nothing to do."))

		assertThat(forward(original).messages.single()).isEqualTo(original)
	}

	@Test
	@DisplayName("system and assistant messages are forwarded unchanged")
	fun otherRolesAreUnchanged() {
		val system = Message.System(content = "You are a dispatcher.", metaInfo = RequestMetaInfo.Empty)
		val assistant = Message.Assistant(content = "Calling a tool.", metaInfo = ResponseMetaInfo(Instant.DISTANT_PAST))

		val rewritten = forward(system, assistant)

		assertThat(rewritten.messages).hasSize(2)
		assertThat(rewritten.messages[0]).isEqualTo(system)
		assertThat(rewritten.messages[1]).isEqualTo(assistant)
	}

	@Test
	@DisplayName("the prompt id and params survive the rewrite")
	fun promptIdentityIsPreserved() {
		val rewritten = forward(userWith(MessagePart.Tool.Result(tool = "no_op", output = "emitted no_op")))

		assertThat(rewritten.id).isEqualTo("test")
	}

	@Test
	@DisplayName("the inlined part is plain text, which is what the Ollama converter can carry")
	fun inlinedPartIsText() {
		val rewritten = forward(userWith(MessagePart.Tool.Result(tool = "no_op", output = "emitted no_op")))

		assertThat(
			rewritten.messages
				.single()
				.parts
				.single()
		).isInstanceOf(MessagePart.Text::class)
	}
}
