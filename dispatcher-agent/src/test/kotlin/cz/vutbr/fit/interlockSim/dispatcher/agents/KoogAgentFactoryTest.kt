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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
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

	/**
	 * Captures the tool list handed to [AgentService.createDispatchAgent] without touching Ollama.
	 *
	 * `internal` (not `private`) so [LivePromptNoMenuTest]'s system-prompt surface (Issue #893,
	 * phase beta, task B3) can drive [KoogAgentFactory.createAgent] through the identical seam
	 * rather than duplicating a second capturing fake.
	 */
	internal class CapturingAgentService : AgentService {
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

	@Test
	@DisplayName("createAgent's system prompt states no tool for querying state and verbatim-only train ids")
	fun createAgentSystemPromptStatesNoStateQueryAndVerbatimTrainIdsOnly() {
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
			assertThat(systemPrompt).contains("no tool for querying state")
			assertThat(systemPrompt).contains("Only ever pass a train id that appears there verbatim")
		}
	}

	// ── B2 prompt rebuild (Issue #893, phase beta, task B2) — RED before the rewrite ─────────

	@Test
	@DisplayName("createAgent's system prompt states the per-tick action budget from the SAME value SinkHolder enforces")
	fun createAgentSystemPromptStatesBudgetFromSinkHolderValue() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			// A non-default cap (2, not DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK's 3) proves
			// the prompt reads the budget from this SinkHolder instance rather than a hardcoded 3.
			val sinkHolder = SinkHolder(maxActionsPerTick = 2)
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = sinkHolder
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains("At most ${sinkHolder.maxActionsPerTick} actions besides no_op")
			// The default-cap case (3), matching the brief's worked example verbatim.
			assertThat(systemPrompt).doesNotContain("At most 3 actions besides no_op")
		}
	}

	@Test
	@DisplayName("createAgent's system prompt states the default per-tick action budget when SinkHolder uses its default")
	fun createAgentSystemPromptStatesDefaultBudget() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val sinkHolder = SinkHolder()

			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = sinkHolder
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains("At most ${sinkHolder.maxActionsPerTick} actions besides no_op")
			assertThat(systemPrompt).contains("At most 3 actions besides no_op")
		}
	}

	@Test
	@DisplayName("createAgent's system prompt states no_op is a correct and frequent answer")
	fun createAgentSystemPromptStatesNoOpIsCorrectAndFrequent() {
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
			assertThat(systemPrompt).contains("no_op is a correct and frequent answer")
			assertThat(systemPrompt).contains(
				"Repeating an action already in force is refused, wastes the tick, and tells the next tick nothing new"
			)
		}
	}

	/**
	 * Issue #893 iteration 2: two new non-negotiable-rules lines mirroring the terminal-rejection
	 * directives added to the tools themselves (D3) — telling the model up front what the tool
	 * errors otherwise have to teach it reactively, one rejected call at a time.
	 */
	@Test
	@DisplayName("createAgent's system prompt tells the model never to approve_train an already-active train")
	fun createAgentSystemPromptWarnsAgainstApprovingAnActiveTrain() {
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
			// A specific, contiguous phrase — not three loose fragments any one of which could
			// already be satisfied by unrelated existing text (e.g. "already in force is refused").
			assertThat(systemPrompt).contains("Never call approve_train for a train already listed as active")
		}
	}

	@Test
	@DisplayName("createAgent's system prompt tells the model to end its turn once the action budget is spent")
	fun createAgentSystemPromptWarnsToEndTurnWhenBudgetSpent() {
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
				"Once the per-tick action budget is spent, end your turn: further tool calls this " +
					"tick are refused."
			)
		}
	}

	@Test
	@DisplayName("createAgent's system prompt's routing step references the NEXT SECTION line")
	fun createAgentSystemPromptRoutingStepReferencesNextSection() {
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
			assertThat(systemPrompt).contains("NEXT SECTION")
		}
	}

	@Test
	@DisplayName(
		"createAgent's system prompt explicitly states a train with no NEXT SECTION line gets no route request " +
			"(gemma4-mandated)"
	)
	fun createAgentSystemPromptStatesNoNextSectionMeansNoRouteRequest() {
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
			assertThat(systemPrompt).contains("a train with no NEXT SECTION line gets no route request this tick")
		}
	}

	// ── Issue #893 iteration 3: turn-termination affordance ────────────────────────────────
	// Measured defect: the model never emits a final plain-text message, so cycles die of
	// Koog iteration exhaustion instead of ending cleanly. These two rules tell the model
	// explicitly how a tick ends (a plain-text reply, nothing else) and to stop churning on a
	// train that keeps getting rejected.

	@Test
	@DisplayName("createAgent's system prompt tells the model how to end a tick with a plain-text reply")
	fun createAgentSystemPromptTellsModelHowToEndTick() {
		loadShuntingLoopContext().use { context ->
			val agentService = CapturingAgentService()
			val sinkHolder = SinkHolder()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
					agentService = agentService,
					perceptionPort = fakePerceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = fakeSensorPort(),
					sinkHolder = sinkHolder
				)

			runBlocking { factory.createAgent(context) }

			val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
			assertThat(systemPrompt).contains(
				"When you have taken the actions this tick needs — never more than " +
					"${sinkHolder.maxActionsPerTick} — end the tick: reply with one short plain-text " +
					"sentence and make no further tool calls. When no action is needed this tick, call " +
					"no_op once and then reply the same way. Only a plain-text reply ends the tick."
			)
		}
	}

	@Test
	@DisplayName("createAgent's system prompt tells the model to stop acting on a train after two rejections")
	fun createAgentSystemPromptTellsModelToStopAfterTwoRejections() {
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
				"If a tool call for a train is rejected twice in one tick, stop acting on that train " +
					"and move on or reply."
			)
		}
	}

	// ── Issue #834 (SP2c.11): the prompt-variant seam ──────────────────────────────────────

	/**
	 * Every pinned-phrase assertion above builds its factory without naming a variant, so all
	 * of them are assertions about [PromptVariant.BASELINE] — that is what makes them the
	 * proof that the baseline still reproduces PR #896's prompt exactly. These two tests close
	 * the remaining gap: that `createAgent` assembles the *selected* variant's instruction half
	 * rather than always the default one. The per-variant text contract itself lives in
	 * [PromptVariantTest]; here we only care that the constructor argument reaches the prompt.
	 */
	@Nested
	@DisplayName("createAgent assembles the selected PromptVariant (Issue #834)")
	inner class PromptVariantSelection {
		private fun assembledPromptFor(variant: PromptVariant?): String {
			val agentService = CapturingAgentService()
			val sinkHolder = SinkHolder()
			val factory =
				if (variant == null) {
					KoogAgentFactory(
						toolRegistry = ToolGroupRegistry(),
						ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
						agentService = agentService,
						perceptionPort = fakePerceptionPort(),
						commandQueue = ActuatorCommandQueue(),
						dispatchLoopSensorPort = fakeSensorPort(),
						sinkHolder = sinkHolder
					)
				} else {
					KoogAgentFactory(
						toolRegistry = ToolGroupRegistry(),
						ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
						agentService = agentService,
						perceptionPort = fakePerceptionPort(),
						commandQueue = ActuatorCommandQueue(),
						dispatchLoopSensorPort = fakeSensorPort(),
						sinkHolder = sinkHolder,
						promptVariant = variant
					)
				}
			loadShuntingLoopContext().use { context -> runBlocking { factory.createAgent(context) } }
			return requireNotNull(agentService.capturedSystemPrompt)
		}

		@Test
		@DisplayName("a factory built without a variant assembles BASELINE, unchanged from before the seam")
		fun defaultsToBaseline() {
			val prompt = assembledPromptFor(null)

			assertThat(
				prompt.startsWith(
					KoogAgentFactory.buildSystemPrompt(
						SinkHolder().maxActionsPerTick,
						PromptVariant.BASELINE
					)
				)
			).isEqualTo(true)
		}

		@Test
		@DisplayName("a factory built with REVISED assembles REVISED, not the default")
		fun selectsTheRequestedVariant() {
			val prompt = assembledPromptFor(PromptVariant.REVISED)

			assertThat(
				prompt.startsWith(
					KoogAgentFactory.buildSystemPrompt(
						SinkHolder().maxActionsPerTick,
						PromptVariant.REVISED
					)
				)
			).isEqualTo(true)
			assertThat(prompt).isNotEqualTo(assembledPromptFor(null))
		}

		/**
		 * The topology half is variant-independent by construction (#834 AC7 keeps
		 * [StationTopologySerializer] out of the prompt rebuild), so switching variants must move
		 * the instruction half and nothing else.
		 */
		@Test
		@DisplayName("both variants append the identical, untouched STATION TOPOLOGY block")
		fun topologyHalfIsVariantIndependent() {
			// Split at the exact seam createAgent joins on, not at the first "STATION TOPOLOGY"
			// occurrence: both variants' instruction halves also mention that section by name.
			val maxActions = SinkHolder().maxActionsPerTick
			val baselineTopology =
				assembledPromptFor(PromptVariant.BASELINE)
					.removePrefix(KoogAgentFactory.buildSystemPrompt(maxActions, PromptVariant.BASELINE))
			val revisedTopology =
				assembledPromptFor(PromptVariant.REVISED)
					.removePrefix(KoogAgentFactory.buildSystemPrompt(maxActions, PromptVariant.REVISED))

			assertThat(baselineTopology).contains("STATION TOPOLOGY")
			assertThat(revisedTopology).isEqualTo(baselineTopology)
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
