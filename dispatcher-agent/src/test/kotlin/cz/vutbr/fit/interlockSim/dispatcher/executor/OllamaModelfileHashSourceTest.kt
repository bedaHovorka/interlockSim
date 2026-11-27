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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * [OllamaModelfileHashSource] (Issue #1342): the run record names the derived local tag, the
 * SHA-256 of its Modelfile as `/api/show` returns it, and the `num_predict` value of its parameters.
 * A failed call records the tag with both figures absent and logs one warning; the run continues.
 */
@DisplayName("Modelfile hash from Ollama /api/show (Issue #1342)")
class OllamaModelfileHashSourceTest {
	private companion object {
		const val TAG = "qwen2.5:7b-capped"
		const val MODELFILE = "FROM qwen2.5:7b-instruct\nPARAMETER num_predict 2048\n"
	}

	private lateinit var server: MockWebServer
	private lateinit var appender: ListAppender<ILoggingEvent>
	private val sourceLogger = LoggerFactory.getLogger(OllamaModelfileHashSource::class.java) as LogbackLogger

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.start()
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		sourceLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		sourceLogger.detachAppender(appender)
		server.shutdown()
	}

	private fun endpoint(): String = server.url("/").toString().trimEnd('/')

	private fun source(timeout: Duration = Duration.ofSeconds(5)) = OllamaModelfileHashSource(endpoint(), timeout)

	private fun showReply(parameters: String): MockResponse =
		MockResponse()
			.setResponseCode(200)
			.setHeader("Content-Type", "application/json")
			.setBody(
				"""{"modelfile":${Json.encodeToString(String.serializer(), MODELFILE)},""" +
					""""parameters":${Json.encodeToString(String.serializer(), parameters)},"template":"{{ .Prompt }}"}"""
			)

	private fun sha256Hex(text: String): String =
		MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

	private fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

	@Test
	fun `records the tag, the SHA-256 of the Modelfile and num_predict from the parameters`() {
		server.enqueue(showReply("num_predict                    2048\nstop                           \"<|im_end|>\""))

		val profile = source().fetch(TAG)

		assertThat(profile).isEqualTo(ModelProfileRecord(TAG, sha256Hex(MODELFILE), 2048))
		val request = server.takeRequest(1, TimeUnit.SECONDS)!!
		assertThat(request.method).isEqualTo("POST")
		assertThat(request.path).isEqualTo("/api/show")
		val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
		assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo(TAG)
		assertThat(warnings()).hasSize(0)
	}

	@Test
	fun `a Modelfile without num_predict records the hash and an absent num_predict`() {
		server.enqueue(showReply("stop                           \"<|im_end|>\""))

		val profile = source().fetch(TAG)

		assertThat(profile.modelfileSha256).isEqualTo(sha256Hex(MODELFILE))
		assertThat(profile.numPredict).isNull()
	}

	/**
	 * Ollama reads `num_predict -1` (and 0) as "no cap", but the record's KDoc defines `null` as "no
	 * cap is configured" — a negative figure in the run JSON would contradict it (review finding,
	 * 2026-11-27). A non-positive value therefore records nothing.
	 */
	@Test
	fun `a non-positive num_predict records no cap, never a negative figure`() {
		server.enqueue(showReply("num_predict                    -1\nstop                           x"))

		assertThat(source().fetch(TAG).numPredict).isNull()

		server.enqueue(showReply("num_predict                    0"))

		assertThat(source().fetch(TAG).numPredict).isNull()
	}

	/**
	 * A field of the wrong JSON shape must not turn into a raw exception in the warning (review
	 * finding, 2026-11-27) — the run keeps its unmeasured outcome either way, but the reason line
	 * stays the clean English one, mirroring `OllamaReplyObserver.observe`'s `as?` read.
	 */
	@Test
	fun `a modelfile of the wrong JSON shape records both figures absent with the clean reason`() {
		server.enqueue(
			MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody("""{"modelfile":{"unexpectedly":"an object"},"parameters":"num_predict 2048"}""")
		)

		assertThat(source().fetch(TAG)).isEqualTo(ModelProfileRecord(TAG, null, null))
		assertThat(warnings().single().formattedMessage).contains("the reply has no modelfile")
	}

	@Test
	fun `an error reply records both figures absent and logs one English warning`() {
		server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model not found"}"""))

		val profile = source().fetch(TAG)

		assertThat(profile).isEqualTo(ModelProfileRecord(TAG, null, null))
		assertThat(warnings()).hasSize(1)
		assertThat(warnings().single().formattedMessage).contains("Modelfile hash")
	}

	@Test
	fun `an unparseable reply records both figures absent`() {
		server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

		assertThat(source().fetch(TAG)).isEqualTo(ModelProfileRecord(TAG, null, null))
		assertThat(warnings()).hasSize(1)
	}

	@Test
	fun `an unreachable endpoint records both figures absent`() {
		val unreachable = OllamaModelfileHashSource("http://127.0.0.1:1", Duration.ofSeconds(2))

		assertThat(unreachable.fetch(TAG)).isEqualTo(ModelProfileRecord(TAG, null, null))
		assertThat(warnings()).hasSize(1)
	}

	@Test
	fun `a server that never answers is cut off by the bounded timeout`() {
		server.enqueue(showReply("num_predict 2048").setHeadersDelay(10, TimeUnit.SECONDS))
		val started = System.nanoTime()

		val profile = source(timeout = Duration.ofMillis(300)).fetch(TAG)

		val elapsedMillis = (System.nanoTime() - started) / 1_000_000
		assertThat(profile).isEqualTo(ModelProfileRecord(TAG, null, null))
		assertThat(elapsedMillis, "the call returned within the bound, not after the server's delay").isLessThan(5_000L)
	}
}
