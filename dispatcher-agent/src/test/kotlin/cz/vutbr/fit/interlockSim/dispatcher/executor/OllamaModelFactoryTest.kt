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
import ai.koog.prompt.llm.LLMProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OllamaModelFactory] (SP2b.9, Issue #566).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class OllamaModelFactoryTest {
	@Test
	fun `toolCapableModel builds an Ollama-provider LLModel with the given id and context length`() {
		val model = OllamaModelFactory.toolCapableModel("qwen2.5:7b-instruct", 32_768L)

		assertThat(model.id).isEqualTo("qwen2.5:7b-instruct")
		assertThat(model.provider).isEqualTo(LLMProvider.Ollama)
		assertThat(model.contextLength).isEqualTo(32_768L)
	}

	@Test
	fun `toolCapableModel supports Tools, Temperature, and basic JSON schema`() {
		val model = OllamaModelFactory.toolCapableModel("qwen2.5:7b-instruct", 32_768L)

		assertThat(model.supports(LLMCapability.Tools)).isTrue()
		assertThat(model.supports(LLMCapability.Temperature)).isTrue()
		assertThat(model.supports(LLMCapability.Schema.JSON.Basic)).isTrue()
	}

	@Test
	fun `toolCapableModel reflects the given model name for a different tag`() {
		val model = OllamaModelFactory.toolCapableModel("llama3.1:8b", 8_192L)

		assertThat(model.id).isEqualTo("llama3.1:8b")
		assertThat(model.contextLength).isEqualTo(8_192L)
	}
}
