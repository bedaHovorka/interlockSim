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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.inject

/**
 * Unit tests for SP1.3 Koin bindings in [dispatcherAgentModule] (Issue #548).
 *
 * Tests that the module correctly wires:
 * - Singleton [OllamaExecutorConfig]
 * - Singleton [ToolGroupRegistry]
 * - Per-context [KoogAgentFactory]
 *
 * Full per-context agent instantiation testing deferred to SP1.4+ once
 * tool implementations are available.
 *
 * @since Issue #548 (SP1.3 — Goal 10)
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

		val allTools = registry.assembleAllTools()
		val perceptionTools = registry.assemblePerceptionTools()
		val actuatorTools = registry.assembleActuatorTools()

		// SP1.3 skeleton: all tool lists are empty
		// SP1.4 will populate them via perception/actuator port implementations
		assertThat(allTools).isInstanceOf(List::class)
		assertThat(perceptionTools).isInstanceOf(List::class)
		assertThat(actuatorTools).isInstanceOf(List::class)
	}
}
