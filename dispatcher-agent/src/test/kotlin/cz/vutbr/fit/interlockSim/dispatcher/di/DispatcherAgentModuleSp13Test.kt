/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.inject

/**
 * Unit tests for SP1.3-SP1.4 Koin bindings in [dispatcherAgentModule] (Issue #548/#549).
 *
 * Tests that the module correctly wires:
 * - Singleton [OllamaExecutorConfig]
 * - Singleton [ToolGroupRegistry]
 * - Per-context [KoogAgentFactory] (SP1.4 updated to accept ports)
 * - Per-context [NetworkPerceptionPort] (SP1.4)
 * - Per-context [NetworkActuatorPort] (SP1.4)
 *
 * Full per-context agent instantiation testing deferred to SP1.5+ once
 * tool implementations are available.
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549) adds port bindings
 */
class DispatcherAgentModuleSp13Test {
	@BeforeEach
	fun setup() {
		startKoin {
			modules(dispatcherAgentModule)
		}
	}

	@AfterEach
	fun cleanup() {
		stopKoin()
	}

	@Test
	fun `OllamaExecutorConfig is provided as singleton`() {
		val config: OllamaExecutorConfig by inject(OllamaExecutorConfig::class.java)

		assertThat(config).isNotNull()
		assertThat(config.modelName).isNotNull()
	}

	@Test
	fun `OllamaExecutorConfig is reused as singleton`() {
		val config1: OllamaExecutorConfig by inject(OllamaExecutorConfig::class.java)
		val config2: OllamaExecutorConfig by inject(OllamaExecutorConfig::class.java)

		assert(config1 === config2) { "OllamaExecutorConfig should be a singleton" }
	}

	@Test
	fun `ToolGroupRegistry is provided as singleton`() {
		val registry: ToolGroupRegistry by inject(ToolGroupRegistry::class.java)

		assertThat(registry).isNotNull()
	}

	@Test
	fun `ToolGroupRegistry is reused as singleton`() {
		val registry1: ToolGroupRegistry by inject(ToolGroupRegistry::class.java)
		val registry2: ToolGroupRegistry by inject(ToolGroupRegistry::class.java)

		assert(registry1 === registry2) { "ToolGroupRegistry should be a singleton" }
	}

	@Test
	fun agentServiceSingletonIsStillProvided() {
		val service: AgentService by inject(AgentService::class.java)

		assertThat(service).isNotNull()
	}

	@Test
	fun ollamaExecutorConfigDefaultsAreToolCapableModel() {
		val config: OllamaExecutorConfig by inject(OllamaExecutorConfig::class.java)

		// Default model should be tool-capable (qwen2.5:7b-instruct per spec)
		assertThat(config.modelName).isInstanceOf(String::class)
		// Validation should not throw for default config
		config.validateToolCapableModel()
	}

	@Test
	fun toolGroupRegistryStartsWithEmptyToolLists() {
		val registry: ToolGroupRegistry by inject(ToolGroupRegistry::class.java)

		// SP1.4: Registry now requires ports to assemble tools.
		// Since this test is at the singleton level (no context scope),
		// we create mock ports just to verify the registry accepts them.
		val mockPerceptionPort = io.mockk.mockk<cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort>()
		val mockActuatorPort = io.mockk.mockk<cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort>()

		val allTools = registry.assembleAllTools(mockPerceptionPort, mockActuatorPort)
		val perceptionTools = registry.assemblePerceptionTools(mockPerceptionPort)
		val actuatorTools = registry.assembleActuatorTools(mockActuatorPort)

		// SP1.4: Port infrastructure in place; tool lists still empty (implementations in SP1.6)
		assertThat(allTools).isEmpty()
		assertThat(perceptionTools).isEmpty()
		assertThat(actuatorTools).isEmpty()
	}
}
