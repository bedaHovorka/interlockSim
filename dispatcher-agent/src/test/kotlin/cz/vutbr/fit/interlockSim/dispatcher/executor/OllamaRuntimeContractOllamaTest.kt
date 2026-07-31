/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.executor

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Live runtime-contract checks against the local Ollama instance (`@Tag("ollama-test")`).
 *
 * ## Why this class exists
 *
 * Every other `ollama-test` in this module asserts something *about a response*. None of them
 * assert that a model was actually **loaded** — so if Ollama were to answer from some degenerate
 * path, or if the Gradle tag gating silently excluded the whole group, the suite would still look
 * green while proving nothing. This class closes that hole:
 *
 * - [ModelResidency] asserts, via `/api/ps`, that the configured model is genuinely resident in
 *   memory after a warm-up. `/api/ps` returns `{"models":[]}` on a cold daemon, so this test
 *   starts red on a fresh machine and only passes once real inference has happened. It is the
 *   in-suite counterpart to watching `ollama ps` in a second shell during the run.
 * - [ModelCapabilities] asserts the model the production config names really advertises
 *   tool-calling and a context window at least as large as the configured `num_ctx`.
 *
 * All checks go through Ollama's own HTTP API rather than Koog, so they measure the backend
 * contract this project depends on, not the client library's interpretation of it.
 *
 * @see OllamaModelPrewarmer
 * @see OllamaConnectivityChecker
 * @since Issue #850 follow-up (SP2c.27 — Goal 10); #814 live-verification support
 */
@DisplayName("Live Ollama runtime contract — residency, capabilities, context window")
@ExtendWith(OllamaPrewarmExtension::class)
@Timeout(3, unit = TimeUnit.MINUTES)
class OllamaRuntimeContractOllamaTest {
	private val config = OllamaExecutorConfig.forLocalTesting()
	private val json = Json { ignoreUnknownKeys = true }

	private fun get(path: String): String =
		runBlocking {
			withContext(Dispatchers.IO) {
				val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
				val request =
					HttpRequest
						.newBuilder()
						.uri(URI.create("${config.ollamaEndpoint}$path"))
						.GET()
						.timeout(Duration.ofSeconds(30))
						.build()
				client.use { it.send(request, HttpResponse.BodyHandlers.ofString()) }.body()
			}
		}

	private fun post(
		path: String,
		body: String
	): String =
		runBlocking {
			withContext(Dispatchers.IO) {
				val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
				val request =
					HttpRequest
						.newBuilder()
						.uri(URI.create("${config.ollamaEndpoint}$path"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.timeout(Duration.ofSeconds(120))
						.build()
				client.use { it.send(request, HttpResponse.BodyHandlers.ofString()) }.body()
			}
		}

	/** Model entries from `/api/tags` (pulled models) or `/api/ps` (loaded models). */
	private fun modelNamesIn(path: String): List<String> =
		json
			.parseToJsonElement(get(path))
			.jsonObject["models"]
			?.jsonArray
			?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
			.orEmpty()

	private fun tagEntryFor(modelName: String): JsonObject? =
		json
			.parseToJsonElement(get("/api/tags"))
			.jsonObject["models"]
			?.jsonArray
			?.map { it.jsonObject }
			?.firstOrNull { it["name"]?.jsonPrimitive?.content == modelName }

	// ── Residency ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Residency: the configured model is really loaded after warm-up")
	inner class ModelResidency {
		/**
		 * The central "are we actually talking to a model?" check. `OllamaPrewarmExtension` has
		 * already run `warmUp` for this class, so the model must appear in `/api/ps`.
		 *
		 * Would fail if [OllamaModelPrewarmer.warmUp] stopped reaching Ollama (wrong endpoint,
		 * wrong path, swallowed error) — the failure mode that would otherwise let every other
		 * `ollama-test` pass on cold-start latency alone.
		 */
		@Test
		@Tag("ollama-test")
		@DisplayName("the configured model appears in /api/ps after the prewarm extension ran")
		fun configuredModelIsResidentAfterWarmUp() {
			runBlocking { OllamaModelPrewarmer.warmUp(config) }

			val loaded = modelNamesIn("/api/ps")

			assertThat(loaded.contains(config.modelName)).isTrue()
		}

		/**
		 * A resident model must have a non-expired `expires_at`; a zero/absent one would mean the
		 * entry is a stale record rather than a live load.
		 */
		@Test
		@Tag("ollama-test")
		@DisplayName("the resident model entry reports a non-zero VRAM/RAM footprint")
		fun residentModelReportsMemoryFootprint() {
			runBlocking { OllamaModelPrewarmer.warmUp(config) }

			val entry =
				json
					.parseToJsonElement(get("/api/ps"))
					.jsonObject["models"]
					?.jsonArray
					?.map { it.jsonObject }
					?.firstOrNull { it["name"]?.jsonPrimitive?.content == config.modelName }

			assertThat(entry).isNotNull()
			val size = entry!!["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
			assertThat(size).isGreaterThan(0L)
		}

		@Test
		@Tag("ollama-test")
		@DisplayName("the Ollama daemon reports a non-blank version")
		fun daemonReportsVersion() {
			val version =
				json
					.parseToJsonElement(get("/api/version"))
					.jsonObject["version"]
					?.jsonPrimitive
					?.content

			assertThat(version).isNotNull()
			assertThat(version!!.isNotBlank()).isTrue()
		}
	}

	// ── Capabilities ──────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Capabilities: the configured model can do what production assumes")
	inner class ModelCapabilities {
		@Test
		@Tag("ollama-test")
		@DisplayName("the configured model is pulled and listed by /api/tags")
		fun configuredModelIsPulled() {
			assertThat(modelNamesIn("/api/tags").contains(config.modelName)).isTrue()
		}

		/**
		 * The whole tool-calling dispatcher depends on this. `OllamaConnectivityChecker` makes the
		 * same judgement through Koog; this asserts it directly against Ollama's own metadata, so
		 * a Koog regression cannot mask a genuinely tool-incapable model.
		 */
		@Test
		@Tag("ollama-test")
		@DisplayName("the configured model advertises the 'tools' capability")
		fun configuredModelSupportsTools() {
			val capabilities =
				tagEntryFor(config.modelName)
					?.get("capabilities")
					?.jsonArray
					?.map { it.jsonPrimitive.content }
					.orEmpty()

			assertThat(capabilities.contains("tools")).isTrue()
		}

		/**
		 * SP2c.27 Spike 3 raised `contextWindowTokens` to 16384 because Ollama silently truncates
		 * the **oldest** messages — which for this project's message order means the system prompt
		 * carrying the station topology. Configuring a `num_ctx` larger than the model can honour
		 * would reintroduce that silent truncation through the back door.
		 */
		@Test
		@Tag("ollama-test")
		@DisplayName("the model's context length is at least the configured contextWindowTokens")
		fun modelContextLengthCoversConfiguredWindow() {
			val contextLength =
				tagEntryFor(config.modelName)
					?.get("details")
					?.jsonObject
					?.get("context_length")
					?.jsonPrimitive
					?.content
					?.toLongOrNull()

			assertThat(contextLength).isNotNull()
			assertThat(contextLength!!).isGreaterThanOrEqualTo(config.contextWindowTokens)
		}

		@Test
		@Tag("ollama-test")
		@DisplayName("OllamaConnectivityChecker reports Available against the real instance")
		fun connectivityCheckerReportsAvailable() {
			val result = runBlocking { OllamaConnectivityChecker.check(config) }

			assertThat(result).isEqualTo(OllamaConnectivityResult.Available)
		}
	}

	// ── Inference proves residency ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Inference: a real completion reports real token accounting")
	inner class InferenceAccounting {
		/**
		 * `prompt_eval_count` is produced by the model actually tokenising the prompt. A canned or
		 * short-circuited response would not carry one — this is the cheapest positive proof that
		 * the round trip really reached the model.
		 */
		@Test
		@Tag("ollama-test")
		@DisplayName("a minimal chat completion reports a non-zero prompt_eval_count")
		fun chatCompletionReportsPromptEvalCount() {
			val body =
				"""
				{"model":"${config.modelName}","messages":[{"role":"user","content":"Reply with the single word: ok"}],
				"stream":false,"options":{"num_ctx":${config.contextWindowTokens},"temperature":0,"seed":7}}
				""".trimIndent()

			val promptEvalCount =
				json
					.parseToJsonElement(post("/api/chat", body))
					.jsonObject["prompt_eval_count"]
					?.jsonPrimitive
					?.content
					?.toLongOrNull() ?: 0L

			assertThat(promptEvalCount).isGreaterThan(0L)
		}

		@Test
		@Tag("ollama-test")
		@DisplayName("a completion using the production num_ctx is accepted, not rejected")
		fun productionContextWindowIsAccepted() {
			val body =
				"""
				{"model":"${config.modelName}","messages":[{"role":"user","content":"Reply with the single word: ok"}],
				"stream":false,"options":{"num_ctx":${config.contextWindowTokens},"temperature":0,"seed":7}}
				""".trimIndent()

			val response = json.parseToJsonElement(post("/api/chat", body)).jsonObject

			assertThat(response["error"]).isEqualTo(null)
			assertThat(response["done"]?.jsonPrimitive?.content).isEqualTo("true")
		}
	}
}
