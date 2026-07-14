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
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OllamaConnectivityChecker.evaluate] (SP1.3, Issue #548).
 *
 * These exercise the network-free decision logic only, against plain
 * `List<LLMCapability>?` fixtures (the model-name-to-capabilities lookup a real
 * `OllamaModelCard` provides), so coverage of this logic does not depend on a locally
 * running Ollama instance. `OllamaModelCard` itself has an `internal` constructor in
 * Koog's ollama-client artifact and cannot be built from outside it, which is exactly
 * why [OllamaConnectivityChecker.evaluate] takes the capability list rather than the
 * whole card. The live [OllamaConnectivityChecker.check] path is exercised separately
 * by the `@Tag("ollama-test")` test in [OllamaExecutorConfigTest].
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
