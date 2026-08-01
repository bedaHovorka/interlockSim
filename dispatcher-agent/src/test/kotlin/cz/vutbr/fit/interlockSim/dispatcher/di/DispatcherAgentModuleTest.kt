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
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.inject

/**
 * Unit tests for the SP1.3 / SP1.4 / SP3.6 Koin bindings in [dispatcherAgentModule]
 * (Issue #548/#549/#574).
 *
 * Tests that the module correctly wires:
 * - Singleton [OllamaExecutorConfig]
 * - Singleton [ToolGroupRegistry]
 * - Singleton [DispatcherPlanner] (SP3.6, default [RuleBasedPlanAdapter])
 * - Per-context [KoogAgentFactory] (SP1.4 updated to accept ports)
 * - Per-context [NetworkPerceptionPort] (SP1.4)
 * - Per-context [NetworkActuatorPort] (SP1.4)
 *
 * Full per-context agent instantiation testing deferred to SP1.5+ once
 * tool implementations are available.
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549) adds port bindings; SP3.6 (#574) adds the planner binding
 */
class DispatcherAgentModuleTest {
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
	fun `DispatcherPlanner is provided as singleton (SP3_6)`() {
		val planner: DispatcherPlanner by inject(DispatcherPlanner::class.java)

		assertThat(planner).isNotNull()
	}

	@Test
	fun `DispatcherPlanner default is RuleBasedPlanAdapter (SP3_6)`() {
		val planner: DispatcherPlanner by inject(DispatcherPlanner::class.java)

		assertThat(planner).isInstanceOf(RuleBasedPlanAdapter::class)
	}

	@Test
	fun `DispatcherPlanner is reused as singleton (SP3_6)`() {
		val planner1: DispatcherPlanner by inject(DispatcherPlanner::class.java)
		val planner2: DispatcherPlanner by inject(DispatcherPlanner::class.java)

		assert(planner1 === planner2) { "DispatcherPlanner should be a singleton" }
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
	fun toolGroupRegistryAssemblesFullToolSetViaKoinSingleton() {
		val registry: ToolGroupRegistry by inject(ToolGroupRegistry::class.java)

		// Registry requires a perception port to assemble perception tools, and a SinkHolder to
		// assemble the four-tool actuator surface (SP2c.6, Issue #829). Since this test is at the
		// singleton level (no context scope), we create a mock perception port and a real
		// SinkHolder just to verify the Koin-provided registry instance assembles the real tool
		// sets through them.
		val mockPerceptionPort = io.mockk.mockk<cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort>()
		val mockSensorPort = io.mockk.mockk<cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort>()
		val sinkHolder =
			cz.vutbr.fit.interlockSim.dispatcher.agents
				.SinkHolder()

		val allTools = registry.assembleAllTools(emptySet(), sinkHolder)
		val perceptionTools = registry.assemblePerceptionTools(mockPerceptionPort)
		val dispatchLoopTools = registry.assembleDispatchLoopTools(mockSensorPort)

		// SP2c.6 (#829): the four-tool actuator surface is exactly approve_train/request_route/
		// cancel_route/no_op — no longer bundled with perception tools.
		assertThat(allTools).hasSize(4)
		assertThat(perceptionTools).hasSize(9)
		assertThat(dispatchLoopTools).hasSize(2)
	}
}
