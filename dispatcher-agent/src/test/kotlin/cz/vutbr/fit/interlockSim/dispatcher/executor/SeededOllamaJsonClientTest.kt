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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [SeededOllamaJsonClient] (SP2c.27 spike prototype, Issue #850).
 *
 * The network-free tests assert on the exact wire shape [SeededOllamaJsonClient.buildRequestBody]
 * produces — in particular, that `seed` is nested inside `options` (where Ollama's `/api/chat`
 * actually reads it from), unlike anything reachable through Koog 1.1.1's `OllamaClient` (see the
 * class KDoc for the full source-level trace). The single `@Tag("ollama-test")` test is the spike's
 * actual empirical payload: it proves — or disproves — decode determinism against a real local
 * Ollama instance, which is the entire point of #850's Spike 1.
 *
 * @since Issue #850 (SP2c.27 — Goal 10 spike)
 */
class SeededOllamaJsonClientTest {
	private val schema =
		buildJsonObject {
			put("type", "object")
			putJsonObject("properties") {
				putJsonObject("action") { put("type", "string") }
			}
		}

	@Test
	fun `buildRequestBody nests seed inside options, not at the request root`() {
		val config = OllamaExecutorConfig()

		val body =
			SeededOllamaJsonClient.buildRequestBody(
				config = config,
				systemPrompt = "system text",
				userPrompt = "user text",
				jsonSchema = null,
				seed = 42L
			)

		// The whole point of this class: seed must be a sibling of temperature/num_ctx inside
		// "options", which is exactly where Koog's OllamaClient never puts it (Spike 1 finding).
		assertThat(body).contains("\"options\":{")
		val optionsIndex = body.indexOf("\"options\":{")
		val seedIndex = body.indexOf("\"seed\":42")
		assertThat(seedIndex > optionsIndex).isTrue()
	}

	@Test
	fun `buildRequestBody includes the configured model and context window`() {
		val config = OllamaExecutorConfig(modelName = "qwen2.5:7b-instruct", contextWindowTokens = 16_384L)

		val body =
			SeededOllamaJsonClient.buildRequestBody(
				config = config,
				systemPrompt = null,
				userPrompt = "hello",
				jsonSchema = null,
				seed = 1L
			)

		assertThat(body).contains("\"qwen2.5:7b-instruct\"")
		assertThat(body).contains("\"num_ctx\":16384")
	}

	@Test
	fun `buildRequestBody omits the system message when systemPrompt is null`() {
		val config = OllamaExecutorConfig()

		val body =
			SeededOllamaJsonClient.buildRequestBody(
				config = config,
				systemPrompt = null,
				userPrompt = "hello",
				jsonSchema = null,
				seed = 1L
			)

		assertThat(body).contains("\"role\":\"user\"")
		assertThat(body.contains("\"role\":\"system\"")).isFalse()
	}

	@Test
	fun `buildRequestBody includes the format schema when provided`() {
		val config = OllamaExecutorConfig()

		val body =
			SeededOllamaJsonClient.buildRequestBody(
				config = config,
				systemPrompt = null,
				userPrompt = "hello",
				jsonSchema = schema,
				seed = 1L
			)

		assertThat(body).contains("\"format\":{\"type\":\"object\"")
	}

	@Test
	fun `CHAT_PATH matches the chat endpoint Koog's OllamaClient also targets`() {
		assertThat(SeededOllamaJsonClient.CHAT_PATH).isEqualTo("/api/chat")
	}

	/**
	 * The actual spike payload: two independent seeded requests, identical in every parameter,
	 * must produce byte-identical decoded output for [SeededOllamaJsonClient] to be worth
	 * building out further. Results (pass/fail on this dev machine) are recorded in
	 * `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`.
	 */
	@Test
	@Tag("ollama-test")
	fun `two requests with the same seed produce identical content against real Ollama`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val systemPrompt = "Reply with strict JSON only, matching the schema. Do not add commentary."
		val userPrompt = "Describe a single railway dispatch action for a train named T1 going from A to B."
		val seed = 12345L

		val first =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, seed)
			}
		val second =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, seed)
			}

		assertThat(second).isEqualTo(first)
	}
}
