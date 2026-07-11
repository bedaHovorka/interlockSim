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
import assertk.assertions.isEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KoogDispatchAgentImpl] skeleton (SP1.2, Issue #547).
 *
 * Tests the basic structure and behavior of the skeleton agent implementation.
 * Real LLM integration tests will be added in SP1.6 (#551).
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
class KoogDispatchAgentImplTest {
	private val mockTool = MockDomainTool("test_tool", "A test tool")
	private val agent = KoogDispatchAgentImpl(
		tools = listOf(mockTool),
		modelName = "mistral",
		systemPrompt = "You are a railway dispatcher"
	)

	@Test
	fun agentInitializesWithToolsAndModelConfiguration() {
		// SP1.2 skeleton test: verify agent can be constructed
		assertThat(agent).isNotNull()
	}

	@Test
	fun decideAsyncReturnsEmptyListInSkeleton() {
		runBlocking {
			val observation = DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

			val decisions = agent.decideAsync(observation)

			// SP1.2 skeleton: no-op (returns empty list)
			// SP1.6: Will return actual dispatch decisions
			assertThat(decisions).isEmpty()
		}
	}

	/**
	 * Mock [DomainTool] for testing (SP1.2 skeleton).
	 */
	private class MockDomainTool(
		override val name: String,
		override val description: String
	) : DomainTool {
		override suspend fun execute(args: Map<String, Any?>): Any? {
			return "mock result"
		}
	}
}
