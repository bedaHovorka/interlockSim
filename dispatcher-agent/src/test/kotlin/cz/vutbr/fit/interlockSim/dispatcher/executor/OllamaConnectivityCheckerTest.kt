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

import ai.koog.prompt.llm.LLMCapability
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The /api/tags answer of a model list holding exactly [MODEL_NAME] — what Koog's
 * `OllamaClient.listModels` decodes. Shared by the HTTP tests and the cancellation test, which
 * serves it from [HoldingOllamaServer] as the request it answers before holding the next one.
 */
private const val MODEL_NAME = "qwen2.5:7b-instruct"

private val TAGS_WITH_MODEL =
	"""{"models":[{"name":"$MODEL_NAME","model":"$MODEL_NAME",""" +
		""""modified_at":"2026-01-01T00:00:00.000000000Z","size":4683087536,""" +
		""""digest":"sha256:testdigest","details":{"parent_model":"","format":"gguf",""" +
		""""family":"qwen2","families":["qwen2"],"parameter_size":"7B",""" +
		""""quantization_level":"Q4_K_M"}}]}"""

/**
 * Unit tests for [OllamaConnectivityChecker.evaluate] (SP1.3, Issue #548).
 *
 * These exercise the network-free decision logic only, against plain
 * `List<LLMCapability>?` fixtures (the model-name-to-capabilities lookup a real
 * `OllamaModelCard` provides), so coverage of this logic does not depend on a locally
 * running Ollama instance. `OllamaModelCard` itself has an `internal` constructor in
 * Koog's ollama-client artifact and cannot be built from outside it, which is exactly
 * why [OllamaConnectivityChecker.evaluate] takes the capability list rather than the
 * whole card. The live [OllamaConnectivityChecker.check] path is exercised live by the
 * `@Tag("ollama-test")` test in [OllamaExecutorConfigTest], and network-free below by
 * [OllamaConnectivityCheckerHttpTest] (Issue #1341 round) and
 * [OllamaConnectivityCheckerCancellationTest].
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
class OllamaConnectivityCheckerTest {
	@Test
	fun `evaluate returns ModelNotPulled when capabilities is null`() {
		val result = OllamaConnectivityChecker.evaluate(null)

		assertThat(result).isEqualTo(OllamaConnectivityResult.ModelNotPulled)
	}

	@Test
	fun `evaluate returns ModelLacksToolSupport when capabilities has no Tools entry`() {
		val result = OllamaConnectivityChecker.evaluate(listOf(LLMCapability.Completion))

		assertThat(result).isEqualTo(OllamaConnectivityResult.ModelLacksToolSupport)
	}

	@Test
	fun `evaluate returns Available when capabilities includes Tools`() {
		val result = OllamaConnectivityChecker.evaluate(listOf(LLMCapability.Tools, LLMCapability.Completion))

		assertThat(result).isEqualTo(OllamaConnectivityResult.Available)
	}
}

/**
 * Network-free [OllamaConnectivityChecker.check] tests (Issue #1341 round) — previously that
 * whole path was covered only by a `@Tag("ollama-test")` test that the gate excludes.
 *
 * [MockWebServer] answers Ollama's two management endpoints Koog's `OllamaClient.getModelOrNull`
 * asks (verified against koog-agents 1.1.1 bytecode: `listModels` GETs `api/tags`, a matched name
 * POSTs `api/show`), so every `evaluate` outcome and the outbound requests are asserted without a
 * live Ollama. The engine pin test holds in its own test because it does not need traffic;
 * MockWebServer serves any engine, so the pin could not be asserted through it.
 */
class OllamaConnectivityCheckerHttpTest {
	private companion object {
		private const val TAGS_EMPTY = """{"models":[]}"""

		private val SHOW_WITH_TOOLS =
			"""{"modelfile":"# Modelfile for $MODEL_NAME","parameters":"stop <|im_end|>",""" +
				""""template":"{{ .Prompt }}","details":{"parent_model":"","format":"gguf",""" +
				""""family":"qwen2","families":["qwen2"],"parameter_size":"7B",""" +
				""""quantization_level":"Q4_K_M"},""" +
				""""model_info":{"general.architecture":"qwen2","general.parameter_count":7248023808,""" +
				""""qwen2.embedding_length":3584,"qwen2.context_length":32768},""" +
				""""capabilities":["completion","tools"]}"""
		private val SHOW_WITHOUT_TOOLS =
			SHOW_WITH_TOOLS.replace(
				""""capabilities":["completion","tools"]""",
				""""capabilities":["completion"]"""
			)
	}

	private lateinit var server: MockWebServer

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	/** No trailing slash: Koog joins the endpoint and its relative paths itself. */
	private fun endpoint(): String = server.url("/").toString().trimEnd('/')

	private fun jsonResponse(body: String): MockResponse =
		MockResponse()
			.setResponseCode(200)
			.setBody(body)
			.setHeader("Content-Type", "application/json")

	@Test
	fun `check reports a pulled tool-capable model as Available`() {
		server.enqueue(jsonResponse(TAGS_WITH_MODEL))
		server.enqueue(jsonResponse(SHOW_WITH_TOOLS))
		val config = OllamaExecutorConfig(ollamaEndpoint = endpoint(), modelName = MODEL_NAME)

		val result = runBlocking { OllamaConnectivityChecker.check(config) }

		assertThat(result).isEqualTo(OllamaConnectivityResult.Available)
		val tagsRequest = server.takeRequest()
		assertThat(tagsRequest.method).isEqualTo("GET")
		assertThat(tagsRequest.path).isEqualTo("/api/tags")
		val showRequest = server.takeRequest()
		assertThat(showRequest.method).isEqualTo("POST")
		assertThat(showRequest.path).isEqualTo("/api/show")
		assertThat(showRequest.body.readUtf8()).contains(MODEL_NAME)
	}

	@Test
	fun `check reports a model missing from the instance as ModelNotPulled`() {
		server.enqueue(jsonResponse(TAGS_EMPTY))
		val config = OllamaExecutorConfig(ollamaEndpoint = endpoint(), modelName = MODEL_NAME)

		val result = runBlocking { OllamaConnectivityChecker.check(config) }

		assertThat(result).isEqualTo(OllamaConnectivityResult.ModelNotPulled)
		assertThat(server.takeRequest().path).isEqualTo("/api/tags")
	}

	@Test
	fun `check reports a model without tool support as ModelLacksToolSupport`() {
		server.enqueue(jsonResponse(TAGS_WITH_MODEL))
		server.enqueue(jsonResponse(SHOW_WITHOUT_TOOLS))
		val config = OllamaExecutorConfig(ollamaEndpoint = endpoint(), modelName = MODEL_NAME)

		val result = runBlocking { OllamaConnectivityChecker.check(config) }

		assertThat(result).isEqualTo(OllamaConnectivityResult.ModelLacksToolSupport)
		assertThat(server.takeRequest().path).isEqualTo("/api/tags")
		assertThat(server.takeRequest().path).isEqualTo("/api/show")
	}

	@Test
	fun `the factory behind check pins the Ktor CIO engine`() {
		// KtorKoogHttpClient.Factory.create derives its served client from the factory's base
		// client (same engine instance), so the engine reachable through create() is exactly the
		// engine the Ollama client of check() runs on. This red-ifies when the pin is removed:
		// Ktor then resolves the first ServiceLoader engine — Apache5 comes first in
		// koog-agents' loader order.
		val (factory, baseClient) = cancellableOllamaHttpClientFactory()
		val client =
			factory.create(
				"connectivity-check-pin-test",
				"http://127.0.0.1:1",
				emptyMap(),
				emptyMap(),
				1_000L,
				1_000L,
				1_000L,
				Json
			)
		try {
			// Both `getKtorClient` and Ktor's CIOEngine type are internal to their modules from
			// Kotlin's point of view, so the pin is observed over JVM reflection: the getter is
			// JVM-public and `HttpClient.engine` is Ktor's public API.
			val ktorClient =
				client.javaClass
					.getMethod("getKtorClient")
					.invoke(client) as io.ktor.client.HttpClient
			assertThat(ktorClient.engine.javaClass.name)
				.isEqualTo("io.ktor.client.engine.cio.CIOEngine")
		} finally {
			client.close()
			// The base client derives the served copy; closing it here (idempotent, after the
			// served close) is the same release the funnel does for its own clients — see
			// CancellableOllamaClientLifecycleTest.
			baseClient.close()
		}
	}
}

/**
 * The same cancellation contract the dispatcher's cycle timeout [OllamaTimeoutAbortTest] enforces
 * for the chat client, pinned here for the client the [OllamaConnectivityChecker.check] path builds.
 *
 * The engine wiring on this path is red-first proven: before the check built its client on the
 * pinned factory (via [cancellableOllamaClient]), Ktor's default engine (Apache5) served the
 * answering `/api/tags` from a pooled keep-alive connection the held one reused, so the abandoned
 * held request stayed open past the ceiling and this test was red. It also goes red when the pin
 * itself is removed from the factory (mutation-proven). The cancellation itself is the test's,
 * not a deadline's: the production check imposes no timeout of its own, it only relies on the
 * caller's Job cancelling it mid-request.
 */
class OllamaConnectivityCheckerCancellationTest {
	private companion object {
		/**
		 * Time the check may take to reach the held request. It must fit the whole client cold start
		 * plus the answered `/api/tags` round trip, so it is generous for a loaded CI runner — and far
		 * under the fixture's 30 s hold, so the close measure below still watches the cancel close the
		 * connection rather than the hold timing out.
		 */
		const val HOLD_BUDGET_MILLIS = 10_000L
		const val CLOSE_CEILING_MILLIS = 5_000L
	}

	@Test
	fun `an abandoned connectivity check closes its held Ollama connection within the ceiling`() {
		HoldingOllamaServer(answerBody = TAGS_WITH_MODEL).use { server ->
			val config = OllamaExecutorConfig(ollamaEndpoint = server.baseUrl)

			// A caller abandons the check mid-flight by cancelling its Job: Koog answers `/api/tags`
			// from the pooled connection and then blocks on the held `/api/show`. The first pool
			// connection is gone from CIO's side (one connection per request), so the held one
			// is fresh and the cancel must tear it down. Dispatchers.IO, not the default child
			// dispatcher, carries the check: the runBlocking thread blocks on the fixture's latch
			// below, and a child left to the runBlocking event loop is merely queued until the
			// parent suspends (the same reasoning KoogAgentFactory.kt documents for its warm-up),
			// so it would never reach the hold.
			runBlocking {
				val checkJob = launch(Dispatchers.IO) { OllamaConnectivityChecker.check(config) }

				// Observe the hold with a real budget first; only then take the abandonment reference,
				// so the close latency below is measured from the cancel, not from a guessed deadline.
				val holdReached = server.awaitHeldRequest(HOLD_BUDGET_MILLIS)
				val abandonedAt = System.nanoTime()
				checkJob.cancelAndJoin()

				assertThat(holdReached, "the check reached the held request").isTrue()

				val latencyMillis = server.awaitCloseLatencyAfter(abandonedAt, CLOSE_CEILING_MILLIS)
				println(
					"Issue #1341: held connectivity-check connection close latency after the cancel = " +
						(latencyMillis?.let { "$it ms" } ?: "not closed within $CLOSE_CEILING_MILLIS ms")
				)
				assertThat(latencyMillis, "the held connection was closed").isNotNull()
				// The fixture stamps the earliest close a connection ever made, so a future extra
				// request before the reference would measure negative; bound it from below too.
				val closedLatency = latencyMillis!!
				assertThat(closedLatency, "close latency after the cancel (ms) lower bound")
					.isGreaterThanOrEqualTo(0)
				assertThat(closedLatency, "close latency after the cancel (ms)")
					.isLessThanOrEqualTo(CLOSE_CEILING_MILLIS)
			}
		}
	}
}
