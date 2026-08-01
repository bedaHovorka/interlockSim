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
import assertk.assertions.isEqualTo
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
 * Unit tests for [KoogAgentFactory] (Goal 10 dispatcher-cannot-approve-trains fix; tool surface
 * updated for SP2c.6, Issue #829).
 *
 * Regression coverage for the root cause where [KoogAgentFactory.createAgent] once assembled
 * perception + generic actuator tools but never included `approve_train` — the LLM dispatcher
 * therefore had no tool capable of admitting a queued train. SP2c.6 (#829) reduces the surface to
 * exactly the four-tool [ToolGroupRegistry.assembleAllTools] actuator surface (`approve_train`,
 * `request_route`, `cancel_route`, `no_op`) behind a [SinkHolder]; perception tools and the
 * dispatch-loop sensor tools (`queued_trains`/`block_inputs`) are no longer bundled into the
 * agent's tool surface.
 *
 * @since Goal 10 dispatcher tool-registration fix (2026-07-26); SP2c.6 (#829) narrows to 4 tools
 */
@DisplayName("KoogAgentFactory assembles the SP2c.6 four-tool actuator surface")
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
	@DisplayName("createAgent assembles exactly the 4-tool actuator surface including approve_train (SP2c6)")
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

			assertThat(toolNames).hasSize(4)
			assertThat(toolNames.toSet()).isEqualTo(setOf("approve_train", "request_route", "cancel_route", "no_op"))
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
		"createAgent's system prompt states the only actuator tools are approve_train/request_route/cancel_route/no_op (SP2c6)"
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
				"The only actuator tools available are approve_train, request_route, cancel_route, and no_op"
			)
			assertThat(systemPrompt).contains("there is no tool to set a signal aspect or switch position directly")
		}
	}
}
