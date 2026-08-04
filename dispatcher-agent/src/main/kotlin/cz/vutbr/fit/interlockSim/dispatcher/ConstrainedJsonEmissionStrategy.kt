/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.SeededOllamaJsonClient
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * [EmissionStrategy] implementation that uses Ollama's constrained-JSON output mode (`format`
 * parameter) rather than native tool-calling (SP2c.13, Issue #836).
 *
 * ## A/B arm purpose
 *
 * This strategy forms the **B arm** of the SP2c.13 head-to-head experiment. The A arm is
 * [ToolCallingEmissionStrategy] / [KoogAgentPlanAdapter] (which uses Koog's `AIAgent` with
 * native tool-calling). B sends the **same four-action semantic interface** as a constrained-JSON
 * schema over Ollama's `format` parameter instead of `tools`, so the model must emit a JSON
 * object matching [ACTION_BATCH_SCHEMA] rather than selecting a function from a tool registry.
 *
 * ## Mutual exclusivity — by construction
 *
 * This class does **not** use Koog's `AIAgent`, does not pass a `ToolRegistry`, and never sets
 * the `tools` field in the Ollama request. [SeededOllamaJsonClient] builds the raw `/api/chat`
 * request with `format` set to [ACTION_BATCH_SCHEMA] and `tools` absent by construction.
 * An agent built with this strategy therefore cannot simultaneously send tool-calling requests;
 * the two modes are separate code paths, not a runtime flag.
 *
 * ## Schema
 *
 * The constrained-JSON schema covers the same four actions as the tool-calling surface
 * (`approve_train`, `request_route`, `cancel_route`, `no_op`):
 *
 * ```json
 * {"type":"object","required":["actions"],"properties":{"actions":{"type":"array","maxItems":3,
 *  "items":{"type":"object","required":["action"],"properties":{
 *    "action":{"type":"string","enum":["approve_train","request_route","cancel_route","no_op"]},
 *    "trainId":{"type":"string"},"fromEndpointName":{"type":"string"},
 *    "toEndpointName":{"type":"string"},"reason":{"type":"string"}}}}}}
 * ```
 *
 * The schema deliberately does **not** express per-action conditional required-fields
 * (`if`/`then`): `Schema.JSON.Basic` is the simplified flavour of constrained decoding,
 * and GBNF conversion of conditionals is unreliable. Missing required fields (e.g. `trainId`
 * on an `approve_train`) are caught by [ActionValidator] as `BLANK_ARGUMENT` — exactly
 * where they belong.
 *
 * ## Parsing
 *
 * The raw JSON string from [SeededOllamaJsonClient.requestJson] is parsed by [parseActions].
 * Entries with an unknown `action` value or completely unparseable structures are silently
 * dropped (they will be reflected as `BLANK_ARGUMENT` or just absent from the tick). The
 * returned list is then passed to [DispatchTickLoop] for validation.
 *
 * ## Author
 *
 * Every [AttributedAction] produced here is tagged [ActionAuthor.LLM] — identical to the
 * tool-calling arm — so SP2c.21 metrics treat both arms identically from an attribution
 * standpoint.
 *
 * @param config Ollama endpoint/model/temperature/context-window configuration.
 * @param systemPrompt System prompt sent to the model. Should describe the four-action vocabulary
 *   and instruct the model to emit a JSON object matching the schema (no tool schema scaffolding
 *   is available in this arm).
 * @param seed Sampling seed forwarded as `options.seed` via [SeededOllamaJsonClient]. Two calls
 *   with the same seed, same prompts, and same [OllamaExecutorConfig.temperature] should — if
 *   Ollama's own seed support is deterministic — produce byte-identical output.
 *
 * @since Issue #836 (SP2c.13 — Goal 10 constrained-JSON A/B arm)
 */
class ConstrainedJsonEmissionStrategy(
	private val config: OllamaExecutorConfig,
	private val systemPrompt: String,
	private val seed: Long
) : EmissionStrategy {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Ollama `format` schema constraining the model to emit a batch of up to 3 dispatch
		 * actions over the four-action vocabulary.
		 *
		 * Deliberately omits `if`/`then` conditionals for per-action required-fields — the
		 * `Schema.JSON.Basic` flavour of constrained decoding does not support them reliably
		 * in GBNF conversion. Missing fields are caught by [ActionValidator].
		 */
		val ACTION_BATCH_SCHEMA: JsonObject =
			buildJsonObject {
				put("type", "object")
				putJsonArray("required") { add(JsonPrimitive("actions")) }
				putJsonObject("properties") {
					putJsonObject("actions") {
						put("type", "array")
						put("maxItems", 3)
						putJsonObject("items") {
							put("type", "object")
							putJsonArray("required") { add(JsonPrimitive("action")) }
							putJsonObject("properties") {
								putJsonObject("action") {
									put("type", "string")
									putJsonArray("enum") {
										add(JsonPrimitive("approve_train"))
										add(JsonPrimitive("request_route"))
										add(JsonPrimitive("cancel_route"))
										add(JsonPrimitive("no_op"))
									}
								}
								putJsonObject("trainId") { put("type", "string") }
								putJsonObject("fromEndpointName") { put("type", "string") }
								putJsonObject("toEndpointName") { put("type", "string") }
								putJsonObject("reason") { put("type", "string") }
							}
						}
					}
				}
			}

		private val json =
			Json {
				ignoreUnknownKeys = true
			}
	}

	private val commandIdCounter: AtomicLong = AtomicLong(0L)

	override val author: ActionAuthor
		get() = ActionAuthor.LLM

	/**
	 * Sends a constrained-JSON request to Ollama and parses the response into [AttributedAction]s.
	 *
	 * The user prompt is built from [prompt] (the rendered observation string from
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer]). [observation] is
	 * passed through to supply the tick index for [AttributedAction.tick].
	 *
	 * Returns `null` on any I/O or parse failure so the caller ([DispatchTickLoop]) substitutes
	 * a [ActionAuthor.TIMEOUT_NOOP] no-op.
	 */
	override suspend fun emit(
		prompt: String,
		observation: DispatcherObservation
	): List<AttributedAction>? =
		try {
			val rawJson =
				SeededOllamaJsonClient.requestJson(
					config = config,
					systemPrompt = systemPrompt,
					userPrompt = prompt,
					jsonSchema = ACTION_BATCH_SCHEMA,
					seed = seed
				)
			logger.debug {
				"ConstrainedJsonEmissionStrategy: received ${rawJson.length} chars at " +
					"simTime=${observation.simTime}"
			}
			parseActions(rawJson, observation.tick)
		} catch (e: Exception) {
			logger.warn(e) {
				"ConstrainedJsonEmissionStrategy: request failed at simTime=${observation.simTime} — returning null"
			}
			null
		}

	/**
	 * Parses the raw JSON string from Ollama into a list of [AttributedAction]s.
	 *
	 * Entries with unknown `action` values or completely unparseable structures are silently
	 * dropped — they will be absent from the tick's action list and are caught downstream by
	 * [ActionValidator] if they would have been invalid anyway.
	 */
	internal fun parseActions(
		rawJson: String,
		tick: Long
	): List<AttributedAction> {
		val root =
			try {
				json.parseToJsonElement(rawJson).jsonObject
			} catch (e: Exception) {
				logger.warn { "ConstrainedJsonEmissionStrategy: could not parse response as JSON object — empty tick" }
				return emptyList()
			}

		val actionsArray =
			root["actions"]?.jsonArray ?: run {
				logger.warn { "ConstrainedJsonEmissionStrategy: response missing 'actions' array — empty tick" }
				return emptyList()
			}

		return actionsArray.mapNotNull { element ->
			try {
				val obj = element.jsonObject
				val actionKind = obj["action"]?.jsonPrimitive?.content ?: return@mapNotNull null
				val trainId = obj["trainId"]?.jsonPrimitive?.content.orEmpty()
				val from = obj["fromEndpointName"]?.jsonPrimitive?.content.orEmpty()
				val to = obj["toEndpointName"]?.jsonPrimitive?.content.orEmpty()
				val reason = obj["reason"]?.jsonPrimitive?.content.orEmpty()

				val action: DispatchAction =
					when (actionKind) {
						"approve_train" ->
							DispatchAction.ApproveTrain(trainId = trainId)
						"request_route" ->
							DispatchAction.RequestRoute(
								trainId = trainId,
								fromEndpointName = from,
								toEndpointName = to
							)
						"cancel_route" ->
							DispatchAction.CancelRoute(trainId = trainId)
						"no_op" -> DispatchAction.NoOp
						else -> {
							logger.warn { "ConstrainedJsonEmissionStrategy: unknown action kind '$actionKind' — skipping" }
							return@mapNotNull null
						}
					}

				AttributedAction(
					commandId = CommandId(commandIdCounter.incrementAndGet()),
					tick = tick,
					action = action,
					author = ActionAuthor.LLM,
					reason = reason
				)
			} catch (e: Exception) {
				logger.warn(e) { "ConstrainedJsonEmissionStrategy: failed to parse action entry — skipping" }
				null
			}
		}
	}
}
