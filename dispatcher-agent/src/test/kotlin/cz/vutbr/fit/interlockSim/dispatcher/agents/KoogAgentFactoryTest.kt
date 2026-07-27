/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Unit tests for [KoogAgentFactory] (Goal 10 dispatcher-cannot-approve-trains fix).
 *
 * Regression coverage for the root cause where [KoogAgentFactory.createAgent] only assembled
 * [ToolGroupRegistry.assembleAllTools] (perception + generic actuator tools) and never included
 * [ToolGroupRegistry.assembleDispatchLoopTools] (`approve_train`/`queued_trains`/`block_inputs`)
 * — the LLM dispatcher therefore had no tool capable of admitting a queued train.
 *
 * @since Goal 10 dispatcher tool-registration fix (2026-07-26)
 */
@DisplayName("KoogAgentFactory assembles dispatch-loop tools alongside the general-purpose tools")
class KoogAgentFactoryTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/** Captures the tool list handed to [AgentService.createDispatchAgent] without touching Ollama. */
	private class CapturingAgentService : AgentService {
		var capturedTools: List<DomainTool>? = null
			private set
		var capturedSystemPrompt: String? = null
			private set

		override suspend fun createDispatchAgent(
			modelName: String,
			tools: List<DomainTool>,
			systemPrompt: String?
		): KoogDispatchAgent {
			capturedTools = tools
			capturedSystemPrompt = systemPrompt
			return object : KoogDispatchAgent {
				override suspend fun decideAsync(observation: DispatchObservation): List<DispatchDecision> = emptyList()
			}
		}
	}

	@Test
	@DisplayName("createAgent assembles 14 distinct tools including approve_train, queued_trains, block_inputs")
	fun createAgentIncludesDispatchLoopTools() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = mockk<NetworkPerceptionPort>(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = mockk<DispatchLoopSensorPort>()
				)

			runBlocking { factory.createAgent(context) }

			val tools = requireNotNull(agentService.capturedTools)
			val toolNames = tools.map { it.name }

			assertThat(toolNames).hasSize(14)
			assertThat(toolNames.toSet()).hasSize(14) // no duplicate names
			assertThat(toolNames).contains("approve_train")
			assertThat(toolNames).contains("queued_trains")
			assertThat(toolNames).contains("block_inputs")
		}
	}

	@Test
	@DisplayName("createAgent's system prompt tells the LLM that approve_train is required to depart a queued train")
	fun createAgentSystemPromptMentionsApproveTrain() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = mockk<NetworkPerceptionPort>(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = mockk<DispatchLoopSensorPort>()
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains("approve_train")
		}
	}

	@Test
	@DisplayName("createAgent's system prompt warns that Block IDs are not valid request_route arguments")
	fun createAgentSystemPromptWarnsAgainstBlockIdsForRequestRoute() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = mockk<NetworkPerceptionPort>(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = mockk<DispatchLoopSensorPort>()
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains("never a Block ID")
		}
	}

	@Test
	@DisplayName(
		"createAgent's system prompt states admission comes first, with the concrete concurrent-train cap"
	)
	fun createAgentSystemPromptStatesAdmissionFirstWithConcreteCap() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = mockk<NetworkPerceptionPort>(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = mockk<DispatchLoopSensorPort>()
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
			assertThat(systemPrompt).contains("admission comes first")
			assertThat(systemPrompt).contains("fewer than $cap trains are currently active")
			assertThat(systemPrompt).contains("up to $cap total active")
		}
	}

	@Test
	@DisplayName(
		"createAgent's system prompt states the only actuator tools are request_route/release_route/approve_train"
	)
	fun createAgentSystemPromptListsActuatorToolInventory() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = mockk<NetworkPerceptionPort>(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = mockk<DispatchLoopSensorPort>()
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains(
				"The only actuator tools available are request_route, release_route, and approve_train"
			)
			assertThat(systemPrompt).contains("there is no tool to set a signal aspect or switch position directly")
		}
	}
}
