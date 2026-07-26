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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Unit tests for [OllamaExecutorConfig] (SP1.3 skeleton, Issue #548).
 *
 * Tests configuration validation and factory methods.
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
class OllamaExecutorConfigTest {
	@Test
	fun `default config uses standard defaults`() {
		val config = OllamaExecutorConfig.default()

		assertThat(config.ollamaEndpoint).isEqualTo("http://localhost:11434")
		assertThat(config.modelName).isEqualTo("qwen2.5:7b-instruct")
		assertThat(config.temperature).isEqualTo(0.28f)
		assertThat(config.topP).isEqualTo(0.9f)
		assertThat(config.maxTokens).isEqualTo(1024)
		assertThat(config.inferenceTimeout).isEqualTo(Duration.ofSeconds(30))
		assertThat(config.retryAttempts).isEqualTo(3)
		assertThat(config.maxAgentIterations).isEqualTo(20)
		assertThat(config.contextWindowTokens).isEqualTo(32_768L)
	}

	@Test
	fun `default config falls back to standard endpoint when OLLAMA_BASE_URL is absent`() {
		val config = OllamaExecutorConfig.default(env = emptyMap())

		assertThat(config.ollamaEndpoint).isEqualTo("http://localhost:11434")
	}

	@Test
	fun `default config falls back to standard endpoint when OLLAMA_BASE_URL is blank`() {
		val config = OllamaExecutorConfig.default(env = mapOf("OLLAMA_BASE_URL" to "   "))

		assertThat(config.ollamaEndpoint).isEqualTo("http://localhost:11434")
	}

	@Test
	fun `default config uses OLLAMA_BASE_URL override when present`() {
		val config = OllamaExecutorConfig.default(env = mapOf("OLLAMA_BASE_URL" to "http://host.docker.internal:11434"))

		assertThat(config.ollamaEndpoint).isEqualTo("http://host.docker.internal:11434")
	}

	@Test
	fun `local testing config reduces inference timeout`() {
		val config = OllamaExecutorConfig.forLocalTesting()

		assertThat(config.inferenceTimeout).isEqualTo(Duration.ofSeconds(10))
		// Other settings should still match defaults
		assertThat(config.modelName).isEqualTo("qwen2.5:7b-instruct")
	}

	@Test
	fun `custom config validates temperature range`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(temperature = -0.1f)
		}

		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(temperature = 1.1f)
		}

		// Edge cases should be accepted
		val config1 = OllamaExecutorConfig(temperature = 0.0f)
		val config2 = OllamaExecutorConfig(temperature = 1.0f)
		assertThat(config1.temperature).isEqualTo(0.0f)
		assertThat(config2.temperature).isEqualTo(1.0f)
	}

	@Test
	fun `custom config validates topP range`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(topP = 1.5f)
		}

		val config = OllamaExecutorConfig(topP = 0.95f)
		assertThat(config.topP).isEqualTo(0.95f)
	}

	@Test
	fun `custom config validates maxTokens positive`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(maxTokens = 0)
		}

		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(maxTokens = -1)
		}

		val config = OllamaExecutorConfig(maxTokens = 2048)
		assertThat(config.maxTokens).isGreaterThan(0)
	}

	@Test
	fun `custom config validates maxAgentIterations positive`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(maxAgentIterations = 0)
		}

		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(maxAgentIterations = -1)
		}

		val config = OllamaExecutorConfig(maxAgentIterations = 3)
		assertThat(config.maxAgentIterations).isEqualTo(3)
	}

	/**
	 * Regression test for the context-window truncation fix (SP2b.9, Issue #566): Koog's default
	 * `ContextWindowStrategy.None` never sends `num_ctx`, which makes Ollama fall back to a hard
	 * 2048-token window — too small for the SP2b.8 static-topology system prompt.
	 * [OllamaSimpleExecutor] wires [contextWindowTokens] into
	 * `ContextWindowStrategy.Companion.Fixed`; this test locks in the validated positive value and
	 * default this project relies on for that fix.
	 */
	@Test
	fun `custom config validates contextWindowTokens positive`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(contextWindowTokens = 0)
		}

		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(contextWindowTokens = -1)
		}

		val config = OllamaExecutorConfig(contextWindowTokens = 16_384)
		assertThat(config.contextWindowTokens).isEqualTo(16_384L)
	}

	@Test
	fun `validateToolCapableModel rejects old model tags`() {
		// "mistral" and "llama2" without version are not tool-capable
		val badConfig1 = OllamaExecutorConfig(modelName = "mistral")
		assertThrows<IllegalArgumentException> {
			badConfig1.validateToolCapableModel()
		}

		val badConfig2 = OllamaExecutorConfig(modelName = "llama2")
		assertThrows<IllegalArgumentException> {
			badConfig2.validateToolCapableModel()
		}
	}

	@Test
	fun `validateToolCapableModel accepts version-pinned models`() {
		// Version-pinned tags should be accepted
		val goodConfig1 = OllamaExecutorConfig(modelName = "mistral:7b-instruct-v0.3")
		goodConfig1.validateToolCapableModel() // Should not throw

		val goodConfig2 = OllamaExecutorConfig(modelName = "llama3.1:8b")
		goodConfig2.validateToolCapableModel() // Should not throw

		val goodConfig3 = OllamaExecutorConfig(modelName = "qwen2.5:7b-instruct")
		goodConfig3.validateToolCapableModel() // Should not throw

		assertThat(true).isTrue() // Dummy assertion; main check is no exceptions thrown
	}

	/**
	 * Proof-of-connection check against a real local Ollama instance (SP1.3, Issue #548).
	 *
	 * Tagged `@Tag("ollama-test")`: `dispatcher-agent`'s `integrationTest` Gradle task
	 * probes Ollama reachability at `localhost:11434` when it runs and decides whether
	 * this tag is even selected — see `dispatcher-agent/build.gradle.kts`. Locally, an
	 * unreachable Ollama fails the build outright (start it via `ollama serve` or
	 * `docker compose up -d ollama`); in CI it logs a `WARN` and this tag is excluded.
	 * Because Gradle already gates inclusion on reachability, this test body asserts
	 * success directly rather than skipping again at the JUnit level.
	 *
	 * @since Issue #548 (SP1.3 — Goal 10)
	 */
	@Test
	@Tag("ollama-test")
	fun `local Ollama has the configured tool-capable model pulled`() =
		runBlocking {
			val result = OllamaConnectivityChecker.check(OllamaExecutorConfig.forLocalTesting())

			assertThat(result).isEqualTo(OllamaConnectivityResult.Available)
		}
}
