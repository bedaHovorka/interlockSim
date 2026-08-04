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
import cz.vutbr.fit.interlockSim.dispatcher.testutil.OllamaTestSeeds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
				seed = OllamaTestSeeds.PRIMARY
			)

		// The whole point of this class: seed must be a sibling of temperature/num_ctx inside
		// "options", which is exactly where Koog's OllamaClient never puts it (Spike 1 finding).
		assertThat(body).contains("\"options\":{")
		val optionsIndex = body.indexOf("\"options\":{")
		val seedIndex = body.indexOf("\"seed\":${OllamaTestSeeds.PRIMARY}")
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
				seed = OllamaTestSeeds.PRIMARY
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
				seed = OllamaTestSeeds.PRIMARY
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
				seed = OllamaTestSeeds.PRIMARY
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
		val seed = OllamaTestSeeds.PRIMARY

		val first =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, seed)
			}
		val second =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, seed)
			}

		// Guard against a degenerate empty response comparing equal to itself and looking
		// "deterministic" — the spike's central claim must hold over real content.
		assertThat(first.isNotBlank()).isTrue()
		assertThat(second).isEqualTo(first)
	}

	/**
	 * Strengthens the two-run claim above. The audit document explicitly records "2 repeats, one
	 * machine, one model" as a limitation; three runs is still a small sample, but it turns a
	 * single coincidence into a pattern at negligible extra cost.
	 */
	@Test
	@Tag("ollama-test")
	fun `three requests with the same seed all produce identical content`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val systemPrompt = "Reply with strict JSON only, matching the schema. Do not add commentary."
		val userPrompt = "Describe a single railway dispatch action for a train named T1 going from A to B."

		val runs =
			(1..3).map {
				runBlocking {
					SeededOllamaJsonClient.requestJson(
						config,
						systemPrompt,
						userPrompt,
						schema,
						seed = OllamaTestSeeds.PRIMARY
					)
				}
			}

		assertThat(runs[0].isNotBlank()).isTrue()
		assertThat(runs.toSet().size).isEqualTo(1)
	}

	/**
	 * The `format` half of the client's contract: the response must actually satisfy the schema it
	 * was constrained by. Would fail if `format` stopped being sent (the `jsonSchema` argument
	 * dropped from [SeededOllamaJsonClient.buildRequestBody]) — the model would then answer in
	 * prose and this parse would blow up or the key would be missing.
	 */
	@Test
	@Tag("ollama-test")
	fun `a schema-constrained response parses as JSON carrying the schema's key`() {
		val config = OllamaExecutorConfig.forLocalTesting()

		val content =
			runBlocking {
				SeededOllamaJsonClient.requestJson(
					config,
					systemPrompt = "Reply with strict JSON only, matching the schema. Do not add commentary.",
					userPrompt = "Name one railway dispatch action.",
					jsonSchema = schema,
					seed = OllamaTestSeeds.PRIMARY
				)
			}

		val parsed = Json.parseToJsonElement(content).jsonObject
		assertThat(parsed.containsKey("action")).isTrue()
	}

	/**
	 * Two different seeds are not *required* to differ — a short, tightly-constrained answer can
	 * legitimately be identical. What must hold is that both seeds still produce a schema-valid,
	 * non-blank answer: a seed value must never break the request.
	 */
	@Test
	@Tag("ollama-test")
	fun `different seeds both produce well-formed schema-valid responses`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val systemPrompt = "Reply with strict JSON only, matching the schema. Do not add commentary."
		val userPrompt = "Name one railway dispatch action."

		val first =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, OllamaTestSeeds.PRIMARY)
			}
		val second =
			runBlocking {
				SeededOllamaJsonClient.requestJson(config, systemPrompt, userPrompt, schema, OllamaTestSeeds.SECONDARY)
			}

		assertThat(Json.parseToJsonElement(first).jsonObject.containsKey("action")).isTrue()
		assertThat(Json.parseToJsonElement(second).jsonObject.containsKey("action")).isTrue()
	}

	/**
	 * Without a schema the client must still work — the seeded path is not schema-only. Would fail
	 * if `format` became a required field rather than a nullable one.
	 */
	@Test
	@Tag("ollama-test")
	fun `a request without a schema still returns non-blank content`() {
		val content =
			runBlocking {
				SeededOllamaJsonClient.requestJson(
					OllamaExecutorConfig.forLocalTesting(),
					systemPrompt = null,
					userPrompt = "Reply with the single word: ok",
					jsonSchema = null,
					seed = OllamaTestSeeds.PRIMARY
				)
			}

		assertThat(content.isNotBlank()).isTrue()
	}

	/**
	 * The client sends the production `contextWindowTokens` as `num_ctx`. SP2c.27 Spike 3 raised
	 * that value to 16384 specifically to avoid silent oldest-message truncation, so a request
	 * carrying it must be accepted rather than rejected by the running model.
	 */
	@Test
	@Tag("ollama-test")
	fun `the configured contextWindowTokens is accepted as num_ctx by the live model`() {
		val config = OllamaExecutorConfig.forLocalTesting()

		val requestBody = SeededOllamaJsonClient.buildRequestBody(config, null, "ok", null, OllamaTestSeeds.PRIMARY)
		val numCtx =
			Json
				.parseToJsonElement(requestBody)
				.jsonObject["options"]
				?.jsonObject
				?.get("num_ctx")
				?.jsonPrimitive
				?.long

		assertThat(numCtx).isEqualTo(config.contextWindowTokens)

		val content =
			runBlocking {
				SeededOllamaJsonClient.requestJson(
					config,
					null,
					"Reply with the single word: ok",
					null,
					OllamaTestSeeds.PRIMARY
				)
			}

		assertThat(content.isNotBlank()).isTrue()
	}
}
