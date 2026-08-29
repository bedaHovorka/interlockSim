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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

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
 * [OllamaPrewarmExtension] preloads the configured Ollama model once (via `@BeforeAll`) so the
 * `@Tag("ollama-test")` proof-of-connection test does not compete with model-load latency
 * (Issue #815).
 *
 * @since Issue #550 (SP1.5 — Goal 10)
 */
@ExtendWith(OllamaPrewarmExtension::class)
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

		// Second call returns cached executor (same instance — reference identity)
		val koogExecutor2 = executor.getExecutor()
		assertThat(koogExecutor2).isSameInstanceAs(koogExecutor)
	}

	/**
	 * Issue #847 round 3: every Koog agent must see tool results as named, error-flagged text.
	 * Without the [ToolResultInliningPromptExecutor] wrapper here, tool results still reach Ollama
	 * (contrary to PR #891's round-2 comment) but arrive anonymous and unflagged, because
	 * `OllamaChatMessageDTO` has no `tool_name`/`is_error` field — and each one also produces a
	 * spurious empty `role="user"` turn plus a `"Skipping unsupported message part"` WARN.
	 *
	 * Wiring it here rather than at the [cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService]
	 * call site means no agent can be built that bypasses it.
	 */
	@Test
	fun `getExecutor wraps the executor so tool results reach the model named and flagged`() {
		val executor = OllamaSimpleExecutor(OllamaExecutorConfig.forLocalTesting())

		assertThat(executor.getExecutor()).isInstanceOf(ToolResultInliningPromptExecutor::class)
	}

	@Test
	fun `getExecutor rejects non-tool-capable model`() {
		// Validation runs inside the lazy block BEFORE OllamaClient is constructed, so this
		// throws without any network access (no Ollama needed). "mistral" is a bare tag that
		// lacks tool/function-calling support per OllamaExecutorConfig.validateToolCapableModel.
		val executor = OllamaSimpleExecutor(OllamaExecutorConfig(modelName = "mistral"))

		assertThrows<IllegalArgumentException> { executor.getExecutor() }
	}

	@Test
	fun `getExecutor throws IllegalStateException after close`() {
		// close() on an uninitialized executor sets `closed`; a subsequent getExecutor() must
		// fail fast (terminal contract) without touching the network. No Ollama needed.
		val executor = OllamaSimpleExecutor(OllamaExecutorConfig.forLocalTesting())

		executor.close()

		assertThrows<IllegalStateException> { executor.getExecutor() }
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
