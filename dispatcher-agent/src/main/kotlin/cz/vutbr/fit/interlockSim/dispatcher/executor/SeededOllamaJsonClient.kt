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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Spike prototype (SP2c.27, Issue #850) proving out a **seeded** constrained-JSON path against
 * Ollama's `/api/chat` endpoint, bypassing Koog's `OllamaClient` entirely.
 *
 * ## Why this exists
 *
 * Spike 1 of #850 traced Koog 1.1.1's actual request-construction code
 * (`ai.koog.prompt.executor.ollama.client.OllamaClient.execute()`) and found there is **no
 * extension seam** to inject a `seed` into the real request:
 * - `OllamaClient` is a `public final class` (not `open`) — it cannot be subclassed.
 * - `OllamaChatRequestDTO` and its nested `Options` (the DTO actually serialized to JSON) are
 *   `internal` to the `ai.koog:prompt-executor-ollama-client` module — no public constructor or
 *   builder exists to add a field to them from outside that module.
 * - `OllamaClient.extractOllamaOptions()` builds `Options(temperature, numCtx)` only — there is
 *   no code path, public or internal-but-reachable, that would ever place a `seed` value inside
 *   the `options` object Ollama actually reads.
 * - `LLMParams.additionalProperties` **does** reach the wire (via
 *   `AdditionalPropertiesFlatteningSerializer`), but that serializer flattens the map into the
 *   **root** of `OllamaChatRequestDTO` — a sibling of `options`, not a merge target inside it.
 *   Ollama's Go server only reads `seed` from inside `options`; a root-level `seed` is silently
 *   dropped by its JSON decoder (unknown top-level field).
 *
 * So the "thin `SeededOllamaClient : LLMClient` delegating to `OllamaClient`" half of #850's
 * Spike 1 task is **not implementable** against the pinned Koog 1.1.1 API surface — there is
 * nothing to delegate *to* that exposes the one field that matters. This class implements the
 * *other* half of the spike instead: a minimal, independent `/api/chat` POST that builds the
 * request itself and places `seed` inside `options` where Ollama actually looks for it.
 *
 * ## Scope — spike only, not production-wired
 *
 * This class deliberately does **not** implement tool-calling, streaming, or the full
 * [ai.koog.prompt.executor.clients.LLMClient] interface — only the constrained-JSON path #850
 * calls out as tractable ("a direct `/api/chat` POST ... which needs no Koog tool machinery").
 * [KoogAgentFactory][cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory] and
 * [DefaultAgentService] are unchanged by this spike and continue to use Koog's `OllamaClient` /
 * `AIAgent` for the real tool-calling dispatch loop. Wiring a seeded path into production
 * (e.g. for a future JSON-only decision mode) is explicitly **out of scope** here — see the
 * SP2c.27 spike report (`docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`) for the recommendation
 * and future-work sizing.
 *
 * @since Issue #850 (SP2c.27 — Goal 10 spike)
 */
object SeededOllamaJsonClient {
	// encodeDefaults=true: without it, kotlinx.serialization omits `stream = false` (a
	// declared-default value) from the wire body entirely — and Ollama's /api/chat defaults an
	// *absent* `stream` field to `true`, silently switching every request to NDJSON streaming
	// instead of a single JSON object. explicitNulls=false keeps the nullable fields (format,
	// options.seed when unset, etc.) omitted rather than serialized as literal `null`.
	private val json =
		Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
			explicitNulls = false
		}

	/** Wall-clock ceiling for a single request, mirroring [OllamaModelPrewarmer]'s pattern. */
	private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
	private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

	@Serializable
	internal data class ChatMessage(
		val role: String,
		val content: String
	)

	@Serializable
	internal data class ChatOptions(
		val temperature: Double? = null,
		@SerialName("num_ctx") val numCtx: Long? = null,
		val seed: Long? = null
	)

	@Serializable
	internal data class ChatRequest(
		val model: String,
		val messages: List<ChatMessage>,
		val format: JsonObject? = null,
		val options: ChatOptions? = null,
		val stream: Boolean = false
	)

	@Serializable
	internal data class ChatResponseMessage(
		val role: String? = null,
		val content: String = ""
	)

	@Serializable
	internal data class ChatResponse(
		val message: ChatResponseMessage? = null,
		val done: Boolean = false
	)

	/**
	 * Sends a single non-streaming, non-tool-calling `/api/chat` request with a pinned [seed]
	 * inside `options`, so — unlike every path through Koog's `OllamaClient` — the seed actually
	 * reaches Ollama.
	 *
	 * @param config Endpoint/model/temperature/context-window configuration; reused as-is so the
	 *   seeded path stays consistent with the rest of the dispatcher's Ollama configuration.
	 * @param systemPrompt Optional system message.
	 * @param userPrompt User message content.
	 * @param jsonSchema The `format` JSON schema constraining the response (Ollama structured
	 *   output), or `null` for unconstrained text.
	 * @param seed Sampling seed forwarded as `options.seed`. Two calls with an identical
	 *   [seed], [jsonSchema], prompts, and [OllamaExecutorConfig.temperature] should — if Ollama's
	 *   own seed support is deterministic for this model/backend — produce byte-identical output.
	 *   That is exactly what this prototype exists to let #850 measure; see the spike report for
	 *   results and for the important caveat that Ollama's own seed guarantee is best-effort, not
	 *   a spec-level contract (it depends on the backend GGML kernel path also being deterministic
	 *   across the two calls, e.g. no concurrent GPU contention).
	 * @return The response message's `content` string (raw JSON text when [jsonSchema] is set).
	 */
	suspend fun requestJson(
		config: OllamaExecutorConfig,
		systemPrompt: String?,
		userPrompt: String,
		jsonSchema: JsonObject?,
		seed: Long
	): String {
		val body = buildRequestBody(config, systemPrompt, userPrompt, jsonSchema, seed)
		val responseBody =
			withContext(Dispatchers.IO) {
				val httpClient =
					HttpClient
						.newBuilder()
						.connectTimeout(CONNECT_TIMEOUT)
						.build()
				val httpRequest =
					HttpRequest
						.newBuilder()
						.uri(URI.create("${config.ollamaEndpoint}$CHAT_PATH"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.timeout(REQUEST_TIMEOUT)
						.build()
				httpClient.use { it.send(httpRequest, HttpResponse.BodyHandlers.ofString()) }
			}
		if (responseBody.statusCode() != HTTP_OK) {
			throw IllegalStateException(
				"Seeded Ollama request failed: HTTP ${responseBody.statusCode()} — ${responseBody.body()}"
			)
		}
		return json
			.decodeFromString<ChatResponse>(responseBody.body())
			.message
			?.content
			.orEmpty()
	}

	/** Exposed for tests; matches the path Koog's own `OllamaClient` targets for `/api/chat`. */
	const val CHAT_PATH: String = "/api/chat"

	private const val HTTP_OK = 200

	/**
	 * Builds the JSON request body a [requestJson] call would send, without performing the HTTP
	 * call — lets tests assert on the exact wire shape (in particular, that `seed` lands inside
	 * `options`) without a live Ollama instance.
	 */
	internal fun buildRequestBody(
		config: OllamaExecutorConfig,
		systemPrompt: String?,
		userPrompt: String,
		jsonSchema: JsonObject?,
		seed: Long
	): String {
		val messages =
			buildList {
				systemPrompt?.let { add(ChatMessage(role = "system", content = it)) }
				add(ChatMessage(role = "user", content = userPrompt))
			}
		val request =
			ChatRequest(
				model = config.modelName,
				messages = messages,
				format = jsonSchema,
				options =
					ChatOptions(
						temperature = config.temperature.toDouble(),
						numCtx = config.contextWindowTokens,
						seed = seed
					),
				stream = false
			)
		return json.encodeToString(request)
	}
}
