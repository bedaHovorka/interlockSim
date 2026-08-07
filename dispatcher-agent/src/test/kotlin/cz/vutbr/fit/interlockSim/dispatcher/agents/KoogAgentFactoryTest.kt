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
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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

	/**
	 * Issue #847 cleanup pass: [cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool]
	 * now calls `perceptionPort.snapshot()` for its in-turn trainId pre-check, so a bare
	 * `mockk<NetworkPerceptionPort>()` with no stubbed answer throws `MockKException` as soon as
	 * `cancel_route` is exercised. Stub [NetworkPerceptionPort.snapshot] to report [activeTrainIds]
	 * as active — defaults to `"T1"`, the id every `SinkHolderWrapperMapping` test in this file uses.
	 */
	private fun fakePerceptionPort(activeTrainIds: List<String> = listOf("T1")): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort> {
			every { snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					trainPositions =
						activeTrainIds.map {
							TrainPositionReading(
								trainId = it,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						}
				)
		}

	/**
	 * Issue #847 round 2: `approve_train` and `request_route` now consult
	 * [DispatchLoopSensorPort.getQueuedTrains] for their in-turn train-id pre-check, so a bare
	 * `mockk<DispatchLoopSensorPort>()` throws `MockKException` as soon as either is exercised.
	 * Reports [queuedTrainIds] as queued — defaults to `"T1"`, the id every
	 * `SinkHolderWrapperMapping` test in this file uses.
	 */
	private fun fakeSensorPort(queuedTrainIds: List<String> = listOf("T1")): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort> {
			every { getQueuedTrains() } returns
				queuedTrainIds.map { QueuedTrainObservation(trainId = it, destinationInOutName = "B") }
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
		var capturedCycleHistory: CycleHistory? = null
			private set

		override suspend fun createDispatchAgent(
			modelName: String,
			tools: List<DomainTool>,
			systemPrompt: String?,
			cycleHistory: CycleHistory,
			outcomeFeed: AppliedOutcomeFeed?
		): KoogDispatchAgent {
			capturedTools = tools
			capturedSystemPrompt = systemPrompt
			capturedCycleHistory = cycleHistory
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
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = SinkHolder()
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
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = SinkHolder()
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
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = SinkHolder()
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
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = SinkHolder()
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
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = SinkHolder()
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains(
				"The only actuator tools available are approve_train, request_route, cancel_route, and no_op"
			)
			assertThat(systemPrompt).contains("there is no tool to set a signal aspect or switch position directly")
		}
	}

	// ── SinkHolder queue-posting wrapper (SP2c.6, Issue #829) ──────────────────

	/**
	 * Drives the real [KoogAgentFactory.createAgent] so the SP2c.6 queue-posting wrapper is installed
	 * on [sinkHolder], then returns the captured tool surface so each actuator tool's end-to-end
	 * `execute → sinkHolder.emit → wrapper → commandQueue.postAll` path can be exercised.
	 *
	 * The wrapper converts each [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction] to a
	 * [DispatchDecision] and posts it to [commandQueue]; the drained decisions are the assertions.
	 * `request_route` is already covered by the marshalling integration test, so this nested class
	 * covers the other three mappings (`approve_train`, `cancel_route`, `no_op`).
	 */
	private fun driveFactoryAndCaptureTools(
		agentService: CapturingAgentService,
		commandQueue: ActuatorCommandQueue,
		sinkHolder: SinkHolder
	): List<DomainTool> {
		val factory =
			KoogAgentFactory(
				toolRegistry = ToolGroupRegistry(),
				ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
				agentService = agentService,
				perceptionPort = fakePerceptionPort(),
				commandQueue = commandQueue,
				dispatchLoopSensorPort = fakeSensorPort(),
				sinkHolder = sinkHolder
			)
		loadShuntingLoopContext().use { context -> runBlocking { factory.createAgent(context) } }
		return requireNotNull(agentService.capturedTools)
	}

	@Nested
	@DisplayName("SinkHolder wrapper maps each actuator tool's DispatchAction to a DispatchDecision")
	inner class SinkHolderWrapperMapping {
		/**
		 * `approve_train` → [DispatchDecision.ApproveTrain]. Would fail if the wrapper's `when`
		 * branch for [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.ApproveTrain] were dropped
		 * or mapped to the wrong decision subtype.
		 */
		@Test
		@DisplayName("approve_train posts DispatchDecision.ApproveTrain")
		fun approveTrainMapsToApproveTrainDecision() {
			val agentService = CapturingAgentService()
			val commandQueue = ActuatorCommandQueue()
			val tools = driveFactoryAndCaptureTools(agentService, commandQueue, SinkHolder())
			val approveTrain = tools.single { it.name == "approve_train" }

			runBlocking { approveTrain.execute(mapOf("trainId" to "T1")) }

			assertThat(commandQueue.drain()).containsExactly(DispatchDecision.ApproveTrain("T1"))
			assertThat(commandQueue.drain()).isEmpty()
		}

		/**
		 * `cancel_route` → [DispatchDecision.ReleaseRoute] (note the rename: the LLM speaks
		 * `cancel_route`; the rule engine speaks `ReleaseRoute`). Would fail if the wrapper mapped
		 * CancelRoute to a same-named decision or dropped the rename.
		 */
		@Test
		@DisplayName("cancel_route posts DispatchDecision.ReleaseRoute")
		fun cancelRouteMapsToReleaseRouteDecision() {
			val agentService = CapturingAgentService()
			val commandQueue = ActuatorCommandQueue()
			val tools = driveFactoryAndCaptureTools(agentService, commandQueue, SinkHolder())
			val cancelRoute = tools.single { it.name == "cancel_route" }

			runBlocking { cancelRoute.execute(mapOf("trainId" to "T1")) }

			assertThat(commandQueue.drain()).containsExactly(DispatchDecision.ReleaseRoute(trainName = "T1"))
			assertThat(commandQueue.drain()).isEmpty()
		}

		/**
		 * `no_op` posts nothing — the wrapper maps [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
		 * to an empty decision list, so [ActuatorCommandQueue.postAll] is a no-op. Would fail if NoOp
		 * were mapped to a placeholder decision (e.g. NoAction) that actually enqueued.
		 */
		@Test
		@DisplayName("no_op posts nothing to the command queue")
		fun noOpPostsNothing() {
			val agentService = CapturingAgentService()
			val commandQueue = ActuatorCommandQueue()
			val tools = driveFactoryAndCaptureTools(agentService, commandQueue, SinkHolder())
			val noOp = tools.single { it.name == "no_op" }

			runBlocking { noOp.execute(mapOf("reason" to "idle")) }

			assertThat(commandQueue.drain()).isEmpty()
		}
	}
}
