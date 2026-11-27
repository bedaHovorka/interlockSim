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
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * What the `/api/chat` replies of ONE dispatcher cycle said about how their generation ended
 * (Issue #1342).
 *
 * Koog 1.1.1 drops Ollama's `done_reason`: its `OllamaChatResponseDTO` decodes only `model`,
 * `message`, `done`, `prompt_eval_count` and `eval_count`, and `OllamaClient.parseResponse` leaves
 * `Message.Assistant.finishReason` null. A generation cut by the model profile's `num_predict`
 * (`done_reason == "length"`) therefore looks to Koog like an ordinary, possibly empty answer.
 * [OllamaReplyObserver] reads the reply on the module's own Ktor base client and records it here.
 *
 * A coroutine-context element: `KoogAgentPlanAdapter` installs a fresh instance around each
 * cycle's inference (`withContext(replies)`), Koog's agent and client run inside that context, and
 * the plugin hook finds it with `currentCoroutineContext()`. A request made outside a cycle (the
 * connectivity check, a test) finds none and records nothing.
 *
 * Rule: a cycle is [generationCut] when ANY of its replies was cut — a cycle can make several
 * `/api/chat` calls (a tool call, then the final reply).
 */
class OllamaCycleReplies : AbstractCoroutineContextElement(Key) {
	/** The context key of [OllamaCycleReplies]. */
	companion object Key : CoroutineContext.Key<OllamaCycleReplies>

	private val cut = AtomicBoolean(false)
	private val replies = AtomicInteger(0)
	private val evalCount = AtomicReference<Int?>(null)

	/** `true` when at least one `/api/chat` reply of the cycle ended with `done_reason == "length"`. */
	val generationCut: Boolean
		get() = cut.get()

	/** How many `/api/chat` replies of the cycle were observed and parsed. */
	val replyCount: Int
		get() = replies.get()

	/** `eval_count` of the last observed reply, or `null` when none was observed or it had none. */
	val lastEvalCount: Int?
		get() = evalCount.get()

	internal fun record(
		doneReason: String?,
		evalCount: Int?
	) {
		replies.incrementAndGet()
		this.evalCount.set(evalCount)
		if (doneReason == OllamaReplyObserver.DONE_REASON_LENGTH) {
			cut.set(true)
		}
	}
}

/**
 * The Ktor client plugin that reads `done_reason` and `eval_count` from every Ollama `/api/chat`
 * reply and records them on the calling cycle's [OllamaCycleReplies] (Issue #1342, option A of the
 * agent-architect ruling).
 *
 * Installed on the base client of [cancellableOllamaHttpClientFactory], so every Koog
 * `OllamaClient` of the module carries it: `KtorKoogHttpClient.Factory` derives its served client
 * with `baseClient.config(...)`, which copies installed plugins. Ktor 3 saves a non-streaming
 * response body, so reading it here leaves it intact for Koog's own `bodyAsText()` (pinned by
 * `OllamaReplyObserverProbeTest`).
 *
 * Only `/api/chat` is observed. The model prewarmer's `/api/generate` request (`num_predict=1`,
 * always cut) goes over the JDK client and never reaches this plugin; the path filter would ignore
 * it anyway. An unparseable reply records nothing and logs one warning; a field of the wrong JSON
 * shape reads as absent. The hook never fails the request it observes.
 */
object OllamaReplyObserver {
	/** The Ollama path whose replies are observed. */
	const val CHAT_PATH: String = "/api/chat"

	/** Ollama's `done_reason` for a generation stopped by `num_predict`. */
	const val DONE_REASON_LENGTH: String = "length"

	private val json = Json { ignoreUnknownKeys = true }

	/** The plugin instance; install with [installOllamaReplyObserver]. */
	val plugin: ClientPlugin<Unit> =
		createClientPlugin("OllamaReplyObserver") {
			onResponse { response ->
				if (!response.call.request.url.encodedPath
						.endsWith(CHAT_PATH)
				) {
					return@onResponse
				}
				val replies = currentCoroutineContext()[OllamaCycleReplies] ?: return@onResponse
				observe(response.bodyAsText(), replies)
			}
		}

	/** Parses one `/api/chat` reply body and records it on [replies]; exposed for unit tests. */
	internal fun observe(
		body: String,
		replies: OllamaCycleReplies
	) {
		val reply =
			try {
				json.parseToJsonElement(body) as? JsonObject
			} catch (e: SerializationException) {
				null
			}
		if (reply == null) {
			logger.warn { "OllamaReplyObserver: could not parse an Ollama /api/chat reply; the cycle is not marked" }
			return
		}
		// `as? JsonPrimitive`, never `jsonPrimitive`: an object or array here must not throw out of
		// the plugin hook, which would fail the dispatcher's request instead of marking nothing.
		replies.record(
			doneReason = (reply["done_reason"] as? JsonPrimitive)?.contentOrNull,
			evalCount = (reply["eval_count"] as? JsonPrimitive)?.intOrNull
		)
	}
}

/** Installs [OllamaReplyObserver] on this client configuration. */
fun HttpClientConfig<*>.installOllamaReplyObserver() {
	install(OllamaReplyObserver.plugin)
}
