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
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [OllamaSimpleExecutor] (SP1.5, Issue #550).
 *
 * Tests executor initialization, lazy loading, and resource cleanup.
 *
 * **Note on @Tag("ollama-test"):** Tests that actually call [OllamaSimpleExecutor.getExecutor]
 * require a running local Ollama instance. See [OllamaConnectivityCheckerTest] for network-free
 * unit tests. These are tagged `@Tag("ollama-test")` and conditionally run based on Ollama
 * reachability (see `dispatcher-agent/build.gradle.kts`).
 *
 * @since Issue #550 (SP1.5 — Goal 10)
 */
class OllamaSimpleExecutorTest {
	@Test
	fun `constructor accepts valid config`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val executor = OllamaSimpleExecutor(config)

		assertThat(executor).isNotNull()
	}

	@Test
	fun `constructor rejects config with invalid temperature`() {
		assertThrows<IllegalArgumentException> {
			OllamaExecutorConfig(temperature = 1.5f)
		}
	}

	@Test
	fun `getExecutor returns non-null executor`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val executor = OllamaSimpleExecutor(config)

		// First call triggers lazy initialization
		val koogExecutor = executor.getExecutor()
		assertThat(koogExecutor).isNotNull()

		// Second call returns cached executor (same instance)
		val koogExecutor2 = executor.getExecutor()
		assertThat(koogExecutor2).isNotNull()
	}

	@Test
	fun `close() is idempotent`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val executor = OllamaSimpleExecutor(config)

		// Close multiple times should not throw
		executor.close()
		executor.close()
	}

	/**
	 * Proof-of-connection test against a real local Ollama instance (SP1.5, Issue #550).
	 *
	 * Tagged `@Tag("ollama-test")`: `dispatcher-agent`'s `integrationTest` Gradle task
	 * probes Ollama reachability at `localhost:11434` when it runs and decides whether
	 * this tag is even selected — see `dispatcher-agent/build.gradle.kts`. Locally, an
	 * unreachable Ollama fails the build outright (start it via `ollama serve` or
	 * `docker compose up -d ollama`); in CI it logs a `WARN` and this tag is excluded.
	 * Because Gradle already gates inclusion on reachability, this test body asserts
	 * success directly rather than skipping again at the JUnit level.
	 *
	 * @since Issue #550 (SP1.5 — Goal 10)
	 */
	@Test
	@Tag("ollama-test")
	fun `getExecutor initializes and returns executor with real Ollama`() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val executor = OllamaSimpleExecutor(config)

		// First call triggers lazy initialization; should succeed with running Ollama
		val koogExecutor = executor.getExecutor()
		assertThat(koogExecutor).isNotNull()

		// Cleanup
		executor.close()
	}
}
