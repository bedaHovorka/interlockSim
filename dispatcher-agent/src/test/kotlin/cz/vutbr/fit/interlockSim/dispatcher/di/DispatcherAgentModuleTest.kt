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
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
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
class DispatcherAgentModuleTest : DispatcherKoinTestBase() {
	override fun getTestModules(): List<Module> = listOf(dispatcherAgentModule)

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

		// SP2c.6 (#829): the registry now exposes only the four-tool actuator surface
		// (approve_train/request_route/cancel_route/no_op) via assembleAllTools. The perception
		// and dispatch-loop sensor assembly methods were removed in SP2c.6 — perception flows
		// through the sim-thread-captured DispatcherObservationProjector, not LLM-queried tools.
		// This test is at the singleton level (no context scope), so a real SinkHolder is created
		// to verify the Koin-provided registry instance assembles the real actuator tool set.
		val sinkHolder =
			cz.vutbr.fit.interlockSim.dispatcher.agents
				.SinkHolder()

		val allTools = registry.assembleAllTools(emptySet(), sinkHolder)

		assertThat(allTools).hasSize(4)
	}
}
