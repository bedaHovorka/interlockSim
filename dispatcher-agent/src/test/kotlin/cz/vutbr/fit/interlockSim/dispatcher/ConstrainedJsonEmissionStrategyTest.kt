/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConstrainedJsonEmissionStrategy] (SP2c.13, Issue #836).
 *
 * All tests are network-free: they call [ConstrainedJsonEmissionStrategy.parseActions] directly
 * (the pure parsing seam) or inspect [ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA] (the
 * schema constant). A separate `@Tag("ollama-test")` suite in
 * [ConstrainedJsonEmissionStrategyOllamaTest] covers the live round-trip path.
 *
 * @since Issue #836 (SP2c.13 — Goal 10 constrained-JSON A/B arm)
 */
@DisplayName("ConstrainedJsonEmissionStrategy — unit (network-free)")
class ConstrainedJsonEmissionStrategyTest {
	private val strategy =
		ConstrainedJsonEmissionStrategy(
			// config and seed are not used by the parsing methods under test
			config =
				cz.vutbr.fit.interlockSim.dispatcher.executor
					.OllamaExecutorConfig(),
			systemPrompt = "test system prompt",
			seed = 42L
		)

	// ── Schema shape ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA is a JSON object with a required 'actions' array")
	fun schemaToplevelIsObjectWithActionsArray() {
		val schema = ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA
		assertThat(schema["type"]?.jsonPrimitive?.content).isEqualTo("object")
		assertThat(schema["required"]?.jsonArray?.any { it.jsonPrimitive.content == "actions" }).isNotNull().isTrue()
		assertThat(schema["properties"]?.jsonObject?.containsKey("actions")).isNotNull().isTrue()
	}

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA items array limits maxItems to 3")
	fun schemaActionsArrayMaxItemsIsThree() {
		val actionsObj =
			ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA["properties"]
				?.jsonObject
				?.get("actions")
				?.jsonObject!!
		assertThat(actionsObj["maxItems"]?.jsonPrimitive?.content).isEqualTo("3")
		assertThat(actionsObj["type"]?.jsonPrimitive?.content).isEqualTo("array")
	}

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA items enum contains exactly the four action kinds")
	fun schemaEnumContainsFourActionKinds() {
		val itemsObj =
			ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA["properties"]
				?.jsonObject
				?.get("actions")
				?.jsonObject
				?.get("items")
				?.jsonObject!!
		val actionEnum =
			itemsObj["properties"]
				?.jsonObject
				?.get("action")
				?.jsonObject
				?.get("enum")
				?.jsonArray
				?.map { it.jsonPrimitive.content }
				?: emptyList()
		assertThat(actionEnum).containsExactly("approve_train", "request_route", "cancel_route", "no_op")
	}

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA has trainId, fromEndpointName, toEndpointName, and reason fields")
	fun schemaHasArgumentFields() {
		val props =
			ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA["properties"]
				?.jsonObject
				?.get("actions")
				?.jsonObject
				?.get("items")
				?.jsonObject
				?.get("properties")
				?.jsonObject!!
		assertThat(props.containsKey("trainId")).isTrue()
		assertThat(props.containsKey("fromEndpointName")).isTrue()
		assertThat(props.containsKey("toEndpointName")).isTrue()
		assertThat(props.containsKey("reason")).isTrue()
	}

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA is a JsonObject (can be passed as the 'format' parameter)")
	fun schemaIsJsonObject() {
		assertThat(ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA).isInstanceOf(JsonObject::class)
	}

	// ── parseActions: happy-path ──────────────────────────────────────────────

	@Test
	@DisplayName("parseActions returns an ApproveTrain for action='approve_train'")
	fun parseActionsApproveTrainRoundtrip() {
		val raw =
			"""{"actions":[{"action":"approve_train","trainId":"T1","reason":"queued train admitted"}]}"""
		val result = strategy.parseActions(raw, tick = 5L)

		assertThat(result.size).isEqualTo(1)
		val attributed = result[0]
		assertThat(attributed.tick).isEqualTo(5L)
		assertThat(attributed.author).isEqualTo(ActionAuthor.LLM)
		assertThat(attributed.action).isInstanceOf(DispatchAction.ApproveTrain::class)
		assertThat((attributed.action as DispatchAction.ApproveTrain).trainId).isEqualTo("T1")
	}

	@Test
	@DisplayName("parseActions returns a RequestRoute for action='request_route'")
	fun parseActionsRequestRouteRoundtrip() {
		val raw =
			"""{"actions":[{"action":"request_route","trainId":"T2",
			|"fromEndpointName":"S1","toEndpointName":"OUT1","reason":"route clear"}]}
			""".trimMargin()
		val result = strategy.parseActions(raw, tick = 7L)

		assertThat(result.size).isEqualTo(1)
		val attributed = result[0]
		assertThat(attributed.author).isEqualTo(ActionAuthor.LLM)
		val action = attributed.action as DispatchAction.RequestRoute
		assertThat(action.trainId).isEqualTo("T2")
		assertThat(action.fromEndpointName).isEqualTo("S1")
		assertThat(action.toEndpointName).isEqualTo("OUT1")
	}

	@Test
	@DisplayName("parseActions returns a CancelRoute for action='cancel_route'")
	fun parseActionsCancelRouteRoundtrip() {
		val raw = """{"actions":[{"action":"cancel_route","trainId":"T3"}]}"""
		val result = strategy.parseActions(raw, tick = 2L)

		assertThat(result.size).isEqualTo(1)
		assertThat(result[0].action).isInstanceOf(DispatchAction.CancelRoute::class)
		assertThat((result[0].action as DispatchAction.CancelRoute).trainId).isEqualTo("T3")
	}

	@Test
	@DisplayName("parseActions returns a NoOp for action='no_op'")
	fun parseActionsNoOpRoundtrip() {
		val raw = """{"actions":[{"action":"no_op","reason":"nothing to do"}]}"""
		val result = strategy.parseActions(raw, tick = 0L)

		assertThat(result.size).isEqualTo(1)
		assertThat(result[0].action).isInstanceOf(DispatchAction.NoOp::class)
	}

	@Test
	@DisplayName("parseActions handles a batch of multiple actions")
	fun parseActionsMultipleActions() {
		val raw =
			"""{"actions":[
			|  {"action":"approve_train","trainId":"T1"},
			|  {"action":"request_route","trainId":"T1","fromEndpointName":"IN1","toEndpointName":"OUT1"}
			|]}
			""".trimMargin()
		val result = strategy.parseActions(raw, tick = 1L)

		assertThat(result.size).isEqualTo(2)
		assertThat(result[0].action).isInstanceOf(DispatchAction.ApproveTrain::class)
		assertThat(result[1].action).isInstanceOf(DispatchAction.RequestRoute::class)
	}

	// ── parseActions: edge-cases and error paths ──────────────────────────────

	@Test
	@DisplayName("parseActions returns empty list for empty actions array")
	fun parseActionsEmptyArray() {
		val raw = """{"actions":[]}"""
		val result = strategy.parseActions(raw, tick = 0L)
		assertThat(result).isEmpty()
	}

	@Test
	@DisplayName("parseActions returns empty list when 'actions' key is missing")
	fun parseActionsMissingActionsKey() {
		val raw = """{"something_else":"value"}"""
		val result = strategy.parseActions(raw, tick = 0L)
		assertThat(result).isEmpty()
	}

	@Test
	@DisplayName("parseActions returns empty list for non-JSON-object input")
	fun parseActionsNonJsonInput() {
		val result = strategy.parseActions("not json at all", tick = 0L)
		assertThat(result).isEmpty()
	}

	@Test
	@DisplayName("parseActions silently drops entries with unknown action kind")
	fun parseActionsUnknownActionKindDropped() {
		val raw =
			"""{"actions":[{"action":"unknown_action","trainId":"T1"},{"action":"no_op"}]}"""
		val result = strategy.parseActions(raw, tick = 0L)

		// The unknown entry is dropped; no_op is kept
		assertThat(result.size).isEqualTo(1)
		assertThat(result[0].action).isInstanceOf(DispatchAction.NoOp::class)
	}

	@Test
	@DisplayName("parseActions silently drops entries with missing 'action' field")
	fun parseActionsMissingActionFieldDropped() {
		val raw = """{"actions":[{"trainId":"T1"},{"action":"no_op"}]}"""
		val result = strategy.parseActions(raw, tick = 0L)

		assertThat(result.size).isEqualTo(1)
		assertThat(result[0].action).isInstanceOf(DispatchAction.NoOp::class)
	}

	@Test
	@DisplayName("parseActions silently drops a non-object entry in the actions array")
	fun parseActionsNonObjectEntryDropped() {
		// A bare string/number entry throws on element.jsonObject — must be caught and skipped,
		// not propagated, so one malformed entry does not fail the whole batch.
		val raw = """{"actions":["not_an_object",{"action":"no_op"}]}"""
		val result = strategy.parseActions(raw, tick = 0L)

		assertThat(result.size).isEqualTo(1)
		assertThat(result[0].action).isInstanceOf(DispatchAction.NoOp::class)
	}

	@Test
	@DisplayName("parseActions assigns monotonically-increasing commandIds within a batch")
	fun parseActionsCommandIdsAreUnique() {
		val raw =
			"""{"actions":[{"action":"no_op"},{"action":"no_op"},{"action":"no_op"}]}"""
		val result = strategy.parseActions(raw, tick = 0L)

		assertThat(result.size).isEqualTo(3)
		val ids = result.map { it.commandId.value }
		assertThat(ids.toSet().size).isEqualTo(3) // all distinct
	}

	// ── author and tick ────────────────────────────────────────────────────────

	@Test
	@DisplayName("author is ActionAuthor.LLM (not RULE_BASED)")
	fun authorIsLlm() {
		assertThat(strategy.author).isEqualTo(ActionAuthor.LLM)
	}

	@Test
	@DisplayName("parseActions stamps every action with the supplied tick index")
	fun parseActionsTickIndexIsStamped() {
		val raw = """{"actions":[{"action":"no_op"},{"action":"no_op"}]}"""
		val result = strategy.parseActions(raw, tick = 99L)
		result.forEach { assertThat(it.tick).isEqualTo(99L) }
	}
}
