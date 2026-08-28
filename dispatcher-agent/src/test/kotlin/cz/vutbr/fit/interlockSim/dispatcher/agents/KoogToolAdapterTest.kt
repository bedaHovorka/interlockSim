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

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.JSONUnquotedPrimitive
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.messageContains
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KoogToolAdapter] (SP2b.9, Issue #566).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogToolAdapterTest {
	private class FakeDomainTool(
		override val name: String = "fake_tool",
		override val description: String = "A fake tool",
		override val parameters: List<DomainToolParameter> = emptyList(),
		private val onExecute: suspend (Map<String, Any?>) -> ToolResult = { ToolResult.Success("ok") }
	) : DomainTool {
		override suspend fun execute(args: Map<String, Any?>): ToolResult = onExecute(args)
	}

	// A dummy JSONSerializer instance — none of decodeArgs/encodeArgs under test actually
	// delegates to it (both are overridden to bypass the default reflection-based codec).
	private val serializer = mockk<JSONSerializer>()

	@Test
	fun `descriptor splits required and optional parameters and maps their types`() {
		val tool =
			FakeDomainTool(
				parameters =
					listOf(
						DomainToolParameter(
							name = "train_name",
							description = "Train identifier",
							type = DomainToolParameterType.String,
							required = true
						),
						DomainToolParameter(
							name = "position",
							description = "Switch position",
							type = DomainToolParameterType.Enum(listOf("MAIN", "BRANCH")),
							required = false
						)
					)
			)

		val descriptor = KoogToolAdapter(tool).descriptor

		assertThat(descriptor.name).isEqualTo("fake_tool")
		assertThat(descriptor.requiredParameters.size).isEqualTo(1)
		assertThat(descriptor.requiredParameters.single().name).isEqualTo("train_name")
		assertThat(descriptor.requiredParameters.single().type).isEqualTo(ToolParameterType.String)
		assertThat(descriptor.optionalParameters.size).isEqualTo(1)
		assertThat(descriptor.optionalParameters.single().name).isEqualTo("position")
		assertThat(descriptor.optionalParameters.single().type)
			.isEqualTo(ToolParameterType.Enum(arrayOf("MAIN", "BRANCH")))
	}

	@Test
	fun `decodeArgs converts every JSONElement variant to the matching Kotlin value`() {
		val adapter = KoogToolAdapter(FakeDomainTool())
		val rawArgs =
			JSONObject(
				mapOf(
					"trainName" to JSONPrimitive("T1"),
					"count" to JSONPrimitive(3),
					"speed" to JSONPrimitive(2.5),
					"holdDuration" to JSONNull,
					"waypoints" to JSONArray(listOf(JSONPrimitive("A"), JSONPrimitive("B"))),
					"nested" to JSONObject(mapOf("inner" to JSONPrimitive(true)))
				)
			)

		val decoded = adapter.decodeArgs(rawArgs, serializer)

		assertThat(decoded["trainName"] as String).isEqualTo("T1")
		assertThat(decoded["count"] as Int).isEqualTo(3)
		assertThat(decoded["speed"] as Double).isEqualTo(2.5)
		assertThat(decoded["holdDuration"]).isNull()
		@Suppress("UNCHECKED_CAST")
		val waypoints = decoded["waypoints"] as List<String>
		assertThat(waypoints).isEqualTo(listOf("A", "B"))
		@Suppress("UNCHECKED_CAST")
		val nested = decoded["nested"] as Map<String, Any?>
		assertThat(nested["inner"] as Boolean).isEqualTo(true)
	}

	/**
	 * A non-string primitive whose content parses as neither Int, Double, nor Boolean falls
	 * through to the raw content string. [JSONUnquotedPrimitive] is the only way to build such
	 * a value (an unquoted literal like `abc123` in lenient model output); the fallback must
	 * stay reachable, so this pins it.
	 */
	@Test
	fun `decodeArgs falls back to the raw content string for an unparseable non-string primitive`() {
		val adapter = KoogToolAdapter(FakeDomainTool())
		val rawArgs = JSONObject(mapOf("token" to JSONUnquotedPrimitive("abc123")))

		val decoded = adapter.decodeArgs(rawArgs, serializer)

		assertThat(decoded["token"]).isEqualTo("abc123")
	}

	@Test
	fun `execute returns the raw success string unquoted`() {
		val adapter = KoogToolAdapter(FakeDomainTool(onExecute = { ToolResult.Success("MAIN") }))

		val result = runBlocking { adapter.execute(emptyMap()) }

		assertThat(result).isEqualTo("MAIN")
		assertThat(adapter.encodeResultToString("MAIN", serializer)).isEqualTo("MAIN")
	}

	@Test
	fun `execute renders a null success payload as legible text, not the bare string null`() {
		val adapter = KoogToolAdapter(FakeDomainTool(onExecute = { ToolResult.Success(null) }))

		val result = runBlocking { adapter.execute(emptyMap()) }

		assertThat(result).isEqualTo("(no matching data found)")
	}

	@Test
	fun `execute renders an empty list success payload as legible text, not the bare string brackets`() {
		val adapter = KoogToolAdapter(FakeDomainTool(onExecute = { ToolResult.Success(emptyList<Any>()) }))

		val result = runBlocking { adapter.execute(emptyMap()) }

		assertThat(result).isEqualTo("(empty — nothing to report)")
	}

	@Test
	fun `execute passes non-empty list success payloads through unchanged`() {
		val adapter = KoogToolAdapter(FakeDomainTool(onExecute = { ToolResult.Success(listOf("a", "b")) }))

		val result = runBlocking { adapter.execute(emptyMap()) }

		assertThat(result).isEqualTo(listOf("a", "b").toString())
	}

	@Test
	fun `execute throws ToolException ValidationFailure on ToolResult Error`() {
		val adapter = KoogToolAdapter(FakeDomainTool(onExecute = { ToolResult.Error("bad switch position") }))

		assertFailure { runBlocking { adapter.execute(emptyMap()) } }
			.isInstanceOf<ToolException.ValidationFailure>()
			.messageContains("bad switch position")
	}
}
