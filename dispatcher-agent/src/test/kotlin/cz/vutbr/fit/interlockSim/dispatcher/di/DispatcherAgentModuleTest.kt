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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import cz.vutbr.fit.interlockSim.dispatcher.agents.AgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationCapacity
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.java.KoinJavaComponent.inject

/**
 * Unit tests for the SP1.3 / SP1.4 / SP3.6 Koin bindings in [dispatcherAgentModule]
 * (Issue #548/#549/#574).
 *
 * Tests that the module correctly wires:
 * - Singleton [OllamaExecutorConfig]
 * - Singleton [ToolGroupRegistry]
 * - [DispatcherPlanner] factory (SP3.6, default [RuleBasedPlanAdapter]) and [Dispatcher] factory, both
 *   built with the [StationCapacity] the caller passes (Issue #1303)
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

	private fun plannerFor(capacity: Int): DispatcherPlanner =
		GlobalContext.get().get<DispatcherPlanner> { parametersOf(StationCapacity(capacity)) }

	private fun capOf(planner: DispatcherPlanner): Int {
		assertThat(planner).isInstanceOf(RuleBasedPlanAdapter::class)
		val dispatcher = (planner as RuleBasedPlanAdapter).dispatcher
		assertThat(dispatcher).isInstanceOf(RuleBasedDispatcher::class)
		return (dispatcher as RuleBasedDispatcher).maxConcurrentTrains
	}

	@Test
	fun `DispatcherPlanner default is RuleBasedPlanAdapter with the station capacity it is given (SP3_6, #1303)`() {
		assertThat(capOf(plannerFor(FOUR_TRACKS))).isEqualTo(FOUR_TRACKS)
	}

	@Test
	fun `each DispatcherPlanner resolution keeps its own station capacity (#1303)`() {
		val fourTracks = plannerFor(FOUR_TRACKS)
		val twoTracks = plannerFor(TWO_TRACKS)

		assertThat(fourTracks).isNotSameInstanceAs(twoTracks)
		assertThat(capOf(fourTracks), "four-track planner").isEqualTo(FOUR_TRACKS)
		assertThat(capOf(twoTracks), "two-track planner").isEqualTo(TWO_TRACKS)
	}

	@Test
	fun `Dispatcher is a RuleBasedDispatcher with the station capacity it is given (#1303)`() {
		val dispatcher = GlobalContext.get().get<Dispatcher> { parametersOf(StationCapacity(FOUR_TRACKS)) }

		assertThat(dispatcher).isInstanceOf(RuleBasedDispatcher::class)
		assertThat((dispatcher as RuleBasedDispatcher).maxConcurrentTrains).isEqualTo(FOUR_TRACKS)
	}

	/**
	 * No silent vyhybna default: a caller that forgets the station capacity gets an error, not a
	 * dispatcher capped at [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS].
	 */
	@Test
	fun `DispatcherPlanner and Dispatcher cannot be resolved without a station capacity (#1303)`() {
		assertFailure { GlobalContext.get().get<DispatcherPlanner>() }
		assertFailure { GlobalContext.get().get<Dispatcher>() }
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

	private companion object {
		const val TWO_TRACKS = 2
		const val FOUR_TRACKS = 4
	}
}
