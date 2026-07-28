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
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * HTTP-mock tests for [OllamaModelPrewarmer] (Issue #815, SP2b.9 warm-up follow-up).
 *
 * Complements [OllamaModelPrewarmerTest] (which inspects [OllamaModelPrewarmer.buildRequestBody]
 * in isolation) by driving the full [OllamaModelPrewarmer.warmUp] path against a
 * [MockWebServer] and asserting the **outbound** request — method, URL path, and body — that
 * the body-builder tests cannot see. Network-free: the mock server listens on localhost only.
 *
 * @since Issue #815 (SP2b.9 warm-up follow-up — Goal 10)
 */
class OllamaModelPrewarmerHttpTest {
	private val server = MockWebServer()

	@BeforeEach
	fun setUp() {
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	/**
	 * Base endpoint pointed at the mock server, with no trailing slash: [OllamaModelPrewarmer.generateUrl]
	 * appends [OllamaModelPrewarmer.GENERATE_PATH], so a trailing slash would yield `//api/generate`.
	 */
	private fun endpoint(): String = server.url("/").toString().trimEnd('/')

	@Test
	fun `warmUp posts to api generate with the configured model and context window`() {
		server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
		val config =
			OllamaExecutorConfig(
				ollamaEndpoint = endpoint(),
				modelName = "mymodel:13b-instruct",
				contextWindowTokens = 16_384L
			)

		// HTTP 200 is the success path — must return normally (no throw).
		runBlocking { OllamaModelPrewarmer.warmUp(config) }

		val recorded = server.takeRequest()
		assertThat(recorded.method).isEqualTo("POST")
		assertThat(recorded.path).isEqualTo(OllamaModelPrewarmer.GENERATE_PATH)

		val body = recorded.body.readUtf8()
		assertThat(body).contains("\"model\":\"mymodel:13b-instruct\"")
		assertThat(body).contains("\"num_ctx\":16384")
		assertThat(body).contains("\"num_predict\":1")
		assertThat(body).contains("\"stream\":false")
		// The body must be a clean single-line JSON — no embedded whitespace from the old trimMargin.
		assertThat(body).doesNotContain("\n")
		assertThat(body).doesNotContain("\t")
	}

	@Test
	fun `warmUp is non-fatal when the server returns a non-200 status`() {
		server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
		val config = OllamaExecutorConfig(ollamaEndpoint = endpoint())

		// A 5xx must not throw — warmUp is documented non-fatal; the first cycle may just be slower.
		runBlocking { OllamaModelPrewarmer.warmUp(config) }

		assertThat(server.takeRequest().path).isEqualTo(OllamaModelPrewarmer.GENERATE_PATH)
	}
}
