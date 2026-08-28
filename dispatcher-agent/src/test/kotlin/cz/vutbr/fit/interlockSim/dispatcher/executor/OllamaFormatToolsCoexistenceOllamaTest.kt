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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.testutil.OllamaTestSeeds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.DisplayName
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

private val logger = KotlinLogging.logger {}

/**
 * Live regression lock for SP2c.27 Spike 2 (`docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md` §2):
 * combining Ollama's `format` (constrained JSON) with `tools` in the **same** request destroys
 * tool-calling on `qwen2.5:7b-instruct` — the spike measured 0/5 trials producing a tool call
 * with `format` set, vs 5/5 without it.
 *
 * That measurement is the entire justification for the design's
 * mutual-exclusivity-by-construction rule (a JSON-only decision mode must be a *separate agent
 * build*, never a runtime flag on the tool-calling one). A spike report alone cannot defend a
 * design decision against a future model or Ollama upgrade; these tests can.
 *
 * ## How to read a failure
 *
 * - [toolsOnlyArmProducesToolCalls] failing means the tool-calling path this project's whole
 *   dispatcher depends on has regressed. Investigate as a genuine breakage.
 * - [formatPlusToolsArmSuppressesToolCalls] failing means the **degradation went away** — a newer
 *   model or Ollama release now honours both. That is good news, not a flake, and it is the
 *   signal to revisit the mutual-exclusivity rule and update the audit document. Do not "fix" it
 *   by loosening the assertion.
 *
 * Requests are built with `kotlinx.serialization` rather than string concatenation: the prompts
 * contain quoted point names, and a hand-spliced JSON body silently produces a malformed request
 * that Ollama answers *without* tool-calling — which would look exactly like the degradation this
 * class is trying to measure.
 *
 * @since Issue #850 (SP2c.27 — Goal 10)
 */
@DisplayName("Live Ollama — format + tools coexistence (SP2c.27 Spike 2 regression lock)")
@ExtendWith(OllamaPrewarmExtension::class)
@Timeout(10, unit = TimeUnit.MINUTES)
class OllamaFormatToolsCoexistenceOllamaTest {
	private companion object {
		/** Trials per arm. Small on purpose — this is a regression lock, not a benchmark. */
		const val TRIALS = 3
	}

	private val config = OllamaExecutorConfig.forLocalTesting()
	private val json = Json { ignoreUnknownKeys = true }

	private val systemPrompt =
		"You are a railway dispatcher. Exactly one queued train, named T1, must travel from " +
			"point A to point B. Call the request_route tool with train_name=T1, from_point=A, " +
			"to_point=B."

	private val requestRouteTool: JsonObject =
		buildJsonObject {
			put("type", "function")
			putJsonObject("function") {
				put("name", "request_route")
				put("description", "Reserve a route for a named train from one point to another.")
				putJsonObject("parameters") {
					put("type", "object")
					putJsonObject("properties") {
						putJsonObject("train_name") {
							put("type", "string")
							put("description", "Name of the train")
						}
						putJsonObject("from_point") {
							put("type", "string")
							put("description", "Departure point")
						}
						putJsonObject("to_point") {
							put("type", "string")
							put("description", "Destination point")
						}
					}
					putJsonArray("required") {
						add("train_name")
						add("from_point")
						add("to_point")
					}
				}
			}
		}

	/** A small, deliberately unrelated schema — the shape a constrained-JSON mode would use. */
	private val unrelatedFormatSchema: JsonObject =
		buildJsonObject {
			put("type", "object")
			putJsonObject("properties") {
				putJsonObject("action") { put("type", "string") }
				putJsonObject("reason") { put("type", "string") }
			}
			putJsonArray("required") {
				add("action")
				add("reason")
			}
		}

	private fun requestBody(
		seed: Int,
		withFormat: Boolean
	): String =
		json.encodeToString(
			JsonObject.serializer(),
			buildJsonObject {
				put("model", config.modelName)
				putJsonArray("messages") {
					add(
						buildJsonObject {
							put("role", "system")
							put("content", systemPrompt)
						}
					)
					add(
						buildJsonObject {
							put("role", "user")
							put("content", "Dispatch train T1 now.")
						}
					)
				}
				put("tools", buildJsonArray { add(requestRouteTool) })
				put("stream", false)
				if (withFormat) put("format", unrelatedFormatSchema)
				putJsonObject("options") {
					put("num_ctx", config.contextWindowTokens)
					put("temperature", 0)
					put("seed", seed)
				}
			}
		)

	private fun chat(
		seed: Int,
		withFormat: Boolean
	): String =
		runBlocking {
			withContext(Dispatchers.IO) {
				val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
				val request =
					HttpRequest
						.newBuilder()
						.uri(URI.create("${config.ollamaEndpoint}${SeededOllamaJsonClient.CHAT_PATH}"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(requestBody(seed, withFormat)))
						.timeout(Duration.ofMinutes(2))
						.build()
				val response = client.use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
				check(response.statusCode() == 200) {
					"Ollama rejected the ${if (withFormat) "format" else "tools-only"} arm request: " +
						"HTTP ${response.statusCode()} — ${response.body()}"
				}
				response.body()
			}
		}

	private fun toolCallCount(responseBody: String): Int =
		json
			.parseToJsonElement(responseBody)
			.jsonObject["message"]
			?.jsonObject
			?.get("tool_calls")
			?.jsonArray
			?.size ?: 0

	private fun trialsWithToolCall(withFormat: Boolean): Int =
		(1..TRIALS).count { seed -> toolCallCount(chat(seed = seed, withFormat = withFormat)) > 0 }

	/**
	 * The baseline arm — the request shape production actually sends. Would fail if the model, the
	 * tool schema, or Ollama's tool-calling support regressed; that is a genuine breakage of the
	 * dispatcher's only decision path.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("tools without format: the model calls request_route")
	fun toolsOnlyArmProducesToolCalls() {
		val hits = trialsWithToolCall(withFormat = false)

		logger.info { "format+tools spike — tools-only arm: $hits/$TRIALS trials produced a tool call" }
		assertThat(hits).isGreaterThanOrEqualTo(1)
	}

	/**
	 * The degradation arm. SP2c.27 measured 0/5; this asserts the collapse is still total.
	 *
	 * **A failure here is a design signal, not a flake** — see the class KDoc. It means `format`
	 * and `tools` now coexist and the mutual-exclusivity-by-construction rule can be revisited.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("format + tools in one request: tool-calling collapses entirely (SP2c.27 §2)")
	fun formatPlusToolsArmSuppressesToolCalls() {
		val hits = trialsWithToolCall(withFormat = true)

		logger.info { "format+tools spike — format arm: $hits/$TRIALS trials produced a tool call" }
		assertThat(hits).isEqualTo(0)
	}

	/**
	 * Not just "no tool call" — the model actively answers the *unrelated* `format` schema
	 * instead, which is what makes the failure mode silent and dangerous: the caller receives a
	 * well-formed response that simply never actuated anything.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("with format set, the model answers the unrelated schema instead of acting")
	fun formatArmAnswersTheUnrelatedSchema() {
		val content =
			json
				.parseToJsonElement(chat(seed = OllamaTestSeeds.PRIMARY.toInt(), withFormat = true))
				.jsonObject["message"]
				?.jsonObject
				?.get("content")
				?.toString()
				.orEmpty()

		logger.info { "format+tools spike — format-arm content: $content" }
		assertThat(content.contains("action") || content.contains("reason")).isTrue()
	}

	/**
	 * Guards the guard: if the tools-only arm's request were malformed, Ollama would answer
	 * without tool-calling and [toolsOnlyArmProducesToolCalls] would fail for a reason that has
	 * nothing to do with model behaviour. Asserting the arm is accepted separates "bad request"
	 * from "model declined to call the tool".
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("both arms are well-formed requests Ollama accepts")
	fun bothArmsAreAcceptedRequests() {
		val toolsOnly =
			json.parseToJsonElement(chat(seed = OllamaTestSeeds.PRIMARY.toInt(), withFormat = false)).jsonObject
		val withFormat =
			json.parseToJsonElement(chat(seed = OllamaTestSeeds.PRIMARY.toInt(), withFormat = true)).jsonObject

		assertThat(toolsOnly["error"]).isEqualTo(null)
		assertThat(withFormat["error"]).isEqualTo(null)
	}
}
