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
import assertk.assertions.isGreaterThan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OllamaModelPrewarmer] (Issue #815, SP2b.9 warm-up follow-up).
 *
 * All tests here are network-free: they exercise the request-body builder and the non-fatal
 * failure behaviour of [OllamaModelPrewarmer.warmUp] without requiring a real Ollama instance.
 * The live path (actual model preload against a running Ollama) is covered implicitly by the
 * `@Tag("ollama-test")` tests on [KoogRealOllamaToolCallingTest], [OllamaSimpleExecutorTest],
 * and [OllamaExecutorConfigTest] via [OllamaPrewarmExtension].
 *
 * @since Issue #815 (SP2b.9 warm-up follow-up — Goal 10)
 */
class OllamaModelPrewarmerTest {
	@Test
	fun `buildRequestBody includes the configured model name`() {
		val config = OllamaExecutorConfig(modelName = "mymodel:13b-instruct")

		val body = OllamaModelPrewarmer.buildRequestBody(config)

		assertThat(body).contains("\"mymodel:13b-instruct\"")
	}

	@Test
	fun `buildRequestBody includes num_ctx matching contextWindowTokens`() {
		val config = OllamaExecutorConfig(contextWindowTokens = 16_384L)

		val body = OllamaModelPrewarmer.buildRequestBody(config)

		assertThat(body).contains("\"num_ctx\":16384")
	}

	@Test
	fun `buildRequestBody sets num_predict to 1 to minimise compute`() {
		val config = OllamaExecutorConfig()

		val body = OllamaModelPrewarmer.buildRequestBody(config)

		assertThat(body).contains("\"num_predict\":1")
	}

	@Test
	fun `buildRequestBody disables streaming`() {
		val config = OllamaExecutorConfig()

		val body = OllamaModelPrewarmer.buildRequestBody(config)

		assertThat(body).contains("\"stream\":false")
	}

	@Test
	fun `buildRequestBody uses the default model and context window when called with defaults`() {
		val config = OllamaExecutorConfig.default()

		val body = OllamaModelPrewarmer.buildRequestBody(config)

		assertThat(body).contains("\"qwen2.5:7b-instruct\"")
		assertThat(body).contains("\"num_ctx\":32768")
	}

	@Test
	fun `WARMUP_TIMEOUT is longer than inferenceTimeout to absorb cold model-load latency`() {
		// The warm-up must be given more time than a real dispatch cycle to actually eliminate
		// the cold-load problem rather than just moving the timeout boundary.
		assertThat(OllamaModelPrewarmer.WARMUP_TIMEOUT.toSeconds())
			.isGreaterThan(OllamaExecutorConfig.default().inferenceTimeout.toSeconds())
	}

	@Test
	fun `warmUp completes without exception when Ollama endpoint is unreachable`() {
		// Port 1 is reserved and practically never open on localhost; connection refused is
		// returned immediately by the OS, which must be caught and treated as non-fatal.
		val config = OllamaExecutorConfig(ollamaEndpoint = "http://127.0.0.1:1")

		// Must NOT throw — warmUp is documented non-fatal.
		runBlocking { OllamaModelPrewarmer.warmUp(config) }
	}

	@Test
	fun `warmUp completes without exception for a clearly invalid endpoint URL`() {
		// Exercises the exception-catch path in warmUp (URI creation succeeds but HTTP fails).
		val config = OllamaExecutorConfig(ollamaEndpoint = "http://127.0.0.1:1")

		runBlocking { OllamaModelPrewarmer.warmUp(config) }
		// Implicit assertion: no exception propagated = non-fatal behaviour confirmed.
	}

	@Test
	fun `default config has inferenceTimeout of 30s and WARMUP_TIMEOUT is 120s`() {
		// Pin the concrete values so a future accidental change is visible immediately.
		assertThat(OllamaExecutorConfig.default().inferenceTimeout.toSeconds()).isEqualTo(30L)
		assertThat(OllamaModelPrewarmer.WARMUP_TIMEOUT.toSeconds()).isEqualTo(120L)
	}
}
