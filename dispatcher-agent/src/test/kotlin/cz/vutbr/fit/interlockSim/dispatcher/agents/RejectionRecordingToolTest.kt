/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RejectionRecordingTool] (Issue #847 round 4).
 *
 * Counting rejections has to happen at the tool boundary, because that is the only place a live
 * `shuntingLoopAI` run rejects anything: the LLM calls tools directly through `SinkHolder`, and
 * `ActionValidator` — the component that would otherwise produce [RejectionCode]s — runs only
 * inside the test-only `DispatchTickLoop`.
 *
 * The decorator must be invisible to the model: it never alters the [ToolResult] the agent sees,
 * because the message is what teaches the model what to send next.
 */
@DisplayName("RejectionRecordingTool — counts rejections without changing what the model sees")
class RejectionRecordingToolTest {
	private class StubTool(
		override val name: String,
		private val result: ToolResult
	) : DomainTool {
		override val description: String = "stub"
		override val parameters: List<DomainToolParameter> = emptyList()

		var callCount: Int = 0
			private set

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount++
			return result
		}
	}

	@Test
	@DisplayName("a coded rejection is reported with its tool name and code")
	fun codedRejectionIsReported() {
		val reported = mutableListOf<Pair<String, RejectionCode>>()
		val tool =
			RejectionRecordingTool(
				StubTool("request_route", ToolResult.Error("nope", rejection = RejectionCode.ENDPOINT_IS_BLOCK_ID))
			) { toolName, code -> reported += toolName to code }

		runBlocking { tool.execute(emptyMap()) }

		assertThat(reported, "reported rejections")
			.containsExactly("request_route" to RejectionCode.ENDPOINT_IS_BLOCK_ID)
	}

	@Test
	@DisplayName("the delegate's result reaches the caller unchanged")
	fun resultIsPassedThroughUnchanged() {
		val error = ToolResult.Error("Unknown trainId 'Ghost'", rejection = RejectionCode.UNKNOWN_TRAIN)
		val tool = RejectionRecordingTool(StubTool("approve_train", error)) { _, _ -> }

		val result = runBlocking { tool.execute(emptyMap()) }

		// Load-bearing: the message is the only channel that teaches the model a valid argument.
		assertThat(result, "result").isEqualTo(error)
	}

	@Test
	@DisplayName("a successful call reports nothing")
	fun successReportsNothing() {
		val reported = mutableListOf<Pair<String, RejectionCode>>()
		val tool =
			RejectionRecordingTool(StubTool("no_op", ToolResult.Success("done"))) { toolName, code ->
				reported += toolName to code
			}

		runBlocking { tool.execute(emptyMap()) }

		assertThat(reported, "reported rejections").isEmpty()
	}

	/**
	 * An error without a code is a port failure or an unavailable sensor, not an argument the model
	 * got wrong. Counting it would inflate the hallucination rate this metric exists to measure.
	 */
	@Test
	@DisplayName("an uncoded error is not counted as a rejection")
	fun uncodedErrorIsNotCounted() {
		val reported = mutableListOf<Pair<String, RejectionCode>>()
		val tool =
			RejectionRecordingTool(StubTool("cancel_route", ToolResult.Error("port unavailable"))) { toolName, code ->
				reported += toolName to code
			}

		runBlocking { tool.execute(emptyMap()) }

		assertThat(reported, "reported rejections").isEmpty()
	}

	@Test
	@DisplayName("the decorator preserves the delegate's identity in the tool schema")
	fun schemaIsPreserved() {
		val delegate = StubTool("request_route", ToolResult.Success(null))
		val tool = RejectionRecordingTool(delegate) { _, _ -> }

		assertThat(tool.name, "name").isEqualTo(delegate.name)
		assertThat(tool.description, "description").isEqualTo(delegate.description)
		assertThat(tool.parameters, "parameters").isEqualTo(delegate.parameters)
	}
}
