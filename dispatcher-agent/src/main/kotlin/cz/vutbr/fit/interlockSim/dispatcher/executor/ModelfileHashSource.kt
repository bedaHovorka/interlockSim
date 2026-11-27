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

import cz.vutbr.fit.interlockSim.dispatcher.planner.ModelProfileRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Duration

/**
 * Reads the model profile of an LLM run at run start (Issue #1342): which Modelfile the Ollama tag
 * resolves to, and its `num_predict` cap. An interface so the run wiring can be tested without a
 * live Ollama; [OllamaModelfileHashSource] is the production source.
 */
fun interface ModelfileHashSource {
	/**
	 * Returns the profile of [tag]. Never throws: a failed read returns the tag with both figures
	 * `null` (not measured), and the run continues.
	 */
	fun fetch(tag: String): ModelProfileRecord
}

/**
 * [ModelfileHashSource] over Ollama's `POST /api/show {"model": <tag>}`, called once at run start
 * before the first cycle (Issue #1342).
 *
 * The hash is the SHA-256 of the returned `modelfile` string as UTF-8 bytes, in lowercase hex;
 * `num_predict` is parsed from the returned `parameters` text (one `name value` pair per line).
 * The call runs on its own short-lived Ktor CIO client, bounded by [timeout] and closed after use —
 * it is not a Koog `OllamaClient`, so the dispatcher's client funnel (`cancellableOllamaClient`)
 * does not apply. Any failure (unreachable, error status, unparseable reply, timeout) records `null`
 * for both figures and logs one warning.
 *
 * @param endpoint The Ollama base URL, without a trailing slash (`OllamaExecutorConfig.ollamaEndpoint`).
 * @param timeout Upper bound of the whole call, connect included.
 */
class OllamaModelfileHashSource(
	private val endpoint: String,
	private val timeout: Duration = DEFAULT_TIMEOUT
) : ModelfileHashSource {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default bound of the `/api/show` call; it only reads metadata, so a few seconds suffice. */
		val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)

		/** The Ollama endpoint that returns a tag's Modelfile and parameters. */
		const val SHOW_PATH: String = "/api/show"

		private val NUM_PREDICT_LINE = Regex("""^\s*num_predict\s+(-?\d+)\s*$""", RegexOption.MULTILINE)
		private val json = Json { ignoreUnknownKeys = true }

		/** SHA-256 of [text] as UTF-8 bytes, in lowercase hex. */
		internal fun sha256Hex(text: String): String =
			MessageDigest
				.getInstance("SHA-256")
				.digest(text.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }

		/** The `num_predict` value of an `/api/show` `parameters` text, or `null` when it sets none. */
		internal fun numPredictOf(parameters: String?): Int? =
			parameters?.let {
				NUM_PREDICT_LINE
					.find(it)
					?.groupValues
					?.get(1)
					?.toIntOrNull()
			}
	}

	override fun fetch(tag: String): ModelProfileRecord =
		try {
			runBlocking { withTimeout(timeout.toMillis()) { show(tag) } }
		} catch (e: CancellationException) {
			// withTimeout's TimeoutCancellationException: the bound expired. runBlocking is the
			// outermost coroutine here, so no parent cancellation can arrive through it.
			unmeasured(tag, "the call did not finish within ${timeout.toMillis()} ms", e)
		} catch (e: Exception) {
			unmeasured(tag, e.message ?: e.javaClass.simpleName, e)
		}

	private suspend fun show(tag: String): ModelProfileRecord {
		val client =
			HttpClient(CIO) {
				install(HttpTimeout) {
					requestTimeoutMillis = timeout.toMillis()
					connectTimeoutMillis = timeout.toMillis()
				}
			}
		return client.use {
			val response =
				it.post("$endpoint$SHOW_PATH") {
					contentType(ContentType.Application.Json)
					setBody(buildJsonObject { put("model", tag) }.toString())
				}
			val body = response.bodyAsText()
			if (!response.status.isSuccess()) {
				return unmeasured(tag, "Ollama answered HTTP ${response.status.value}", null)
			}
			val reply =
				json.parseToJsonElement(body) as? JsonObject
					?: return unmeasured(tag, "the reply is not a JSON object", null)
			val modelfile =
				reply["modelfile"]?.jsonPrimitive?.contentOrNull
					?: return unmeasured(tag, "the reply has no modelfile", null)
			ModelProfileRecord(
				tag = tag,
				modelfileSha256 = sha256Hex(modelfile),
				numPredict = numPredictOf(reply["parameters"]?.jsonPrimitive?.contentOrNull)
			)
		}
	}

	private fun unmeasured(
		tag: String,
		reason: String,
		cause: Throwable?
	): ModelProfileRecord {
		logger.warn {
			"OllamaModelfileHashSource: could not read the Modelfile hash of '$tag' from $endpoint$SHOW_PATH " +
				"($reason); the run record keeps it absent and the run continues" +
				(cause?.let { " [${it.javaClass.simpleName}]" } ?: "")
		}
		return ModelProfileRecord(tag = tag, modelfileSha256 = null, numPredict = null)
	}
}
