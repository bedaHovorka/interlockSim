/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [DefaultAgentService] skeleton (SP1.2, Issue #547).
 *
 * Tests the basic agent service factory and Koin integration.
 * Tool binding and Ollama integration tests will be added in later phases.
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
class DefaultAgentServiceTest {
	private val service = DefaultAgentService()

	@Test
	fun `createDispatchAgent returns a valid agent instance`() {
		runBlocking {
			val agent =
				service.createDispatchAgent(
					modelName = "qwen2.5:7b-instruct",
					tools = emptyList(),
					systemPrompt = "You are a railway dispatcher"
				)

			assertThat(agent).isNotNull()
		}
	}

	@Test
	fun `createDispatchAgent works with empty tool list`() {
		runBlocking {
			val agent =
				service.createDispatchAgent(
					modelName = "qwen2.5:7b-instruct",
					tools = emptyList(),
					systemPrompt = null
				)

			assertThat(agent).isNotNull()
		}
	}

	/**
	 * Parameterized test for tool-capable models (per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md).
	 *
	 * Tests that createDispatchAgent works with models that support native tool calling:
	 * - D1: qwen2.5:7b-instruct (top pick)
	 * - D2: llama3.1:8b (close second)
	 * - D5: gemma3:4b (fast fallback)
	 */
	@ParameterizedTest
	@ValueSource(strings = ["qwen2.5:7b-instruct", "llama3.1:8b", "gemma3:4b"])
	fun `createDispatchAgent works with tool-capable models and tool list`(modelName: String) {
		runBlocking {
			val tool = MockDomainTool("test_tool", "A test tool")
			val agent =
				service.createDispatchAgent(
					modelName = modelName,
					tools = listOf(tool),
					systemPrompt = "System prompt"
				)

			assertThat(agent).isNotNull()
		}
	}

	/**
	 * Mock [DomainTool] for testing.
	 */
	private class MockDomainTool(
		override val name: String,
		override val description: String
	) : DomainTool {
		override suspend fun execute(args: Map<String, Any?>): Any? = "mock result"
	}
}
