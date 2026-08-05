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
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaPrewarmExtension
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Live proof that round 2's prompt fixes hold against a real model on the real network
 * (Issue #847 round 3).
 *
 * ## The gap this closes
 *
 * [KoogRealOllamaToolCallingTest] drives real Ollama, but with **hand-written prompts and fake
 * tools**. Every defect PR #891 round 2 fixed lives in the production system prompt
 * ([KoogAgentFactory.DEFAULT_SYSTEM_PROMPT] plus [StationTopologySerializer.toPromptText]) and in
 * the four real actuator tools, so none of it was covered end to end. The offline tests assert the
 * prompt *text*; only a live model can show whether the text works.
 *
 * This builds the genuine article — real topology from `vyhybna.xml`, real system prompt, real
 * four-tool surface behind [ToolGroupRegistry.assembleAllTools] — and asserts on what the model
 * does with it.
 *
 * ## What is asserted, and what deliberately is not
 *
 * The assertions are on **rejection counts being zero**, never on a specific call sequence. A
 * 7B-class model's exact choice of route on any given cycle is an LLM-quality question; whether it
 * can name an endpoint or a train that exists is a prompt-correctness question, and only the second
 * is what round 2 changed. Rejections are counted by wrapping each real tool and classifying the
 * [ToolResult.Error] messages the tools themselves produce.
 *
 * ## Honest limits: this is a smoke test, not a regression test
 *
 * These tests were checked against deliberately reverted prompts and **did not fail**. Three
 * separate reverts were tried on round 3: dropping the Signals list from
 * [StationTopologySerializer.toPromptText]; presenting the Blocks list unlabelled, without the
 * "never valid as a request_route endpoint" disqualifier; and restoring round 1's literal
 * `request_route(trainName="T1", …)` worked example to [KoogAgentFactory]'s system prompt — the
 * exact defect that poisoned the reservation registry. All three stayed green over six cycles.
 *
 * The reason is that this harness's scenario is too easy: one queued train bound for "B", no active
 * trains, no occupied blocks, no competing reservations. Round 2's 86 endpoint and 90 train-id
 * rejections accumulated over hundreds of cycles of a live 600 s run, where trains hold blocks,
 * routes conflict, and the per-cycle observation is far richer. That state cannot be faked here.
 *
 * So: treat these as a live smoke test that the production prompt and the real four-tool surface
 * work end to end against a real model. The authoritative regression evidence for round 2's prompt
 * fixes is elsewhere — the offline prompt-text assertions in [KoogAgentFactoryTest] and
 * [StationTopologySerializerTest], and the rejected-call counts measured over full 600 s runs.
 * Do not add assertions here that claim to guard a prompt property without first checking they can
 * be made to fail by removing it.
 *
 * @since Issue #847 (round 3)
 */
@ExtendWith(OllamaPrewarmExtension::class)
@DisplayName("The production prompt keeps a real model inside the real network's vocabulary")
class KoogRealPromptOllamaTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	/** The one queued train the model is asked to dispatch. Matches `Train`'s real naming. */
	private val queuedTrainId = "Train #1"

	private val testTimeoutMillis = 300_000L

	private companion object {
		/** Dispatch cycles per test — see [runCycles] for why one is not enough. */
		const val DEFAULT_CYCLES = 6
	}

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	/** Records every real-tool invocation and classifies the rejections the tools returned. */
	private class RejectionRecorder {
		val calls: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()
		val unknownEndpointRejections: MutableList<String> = mutableListOf()
		val unknownTrainRejections: MutableList<String> = mutableListOf()
		val otherRejections: MutableList<String> = mutableListOf()

		fun record(
			toolName: String,
			args: Map<String, Any?>,
			result: ToolResult
		) {
			calls.add(toolName to args)
			if (result !is ToolResult.Error) return
			val message = result.message
			when {
				// RequestRouteTool's endpoint rejections.
				message.startsWith("Unknown fromEndpointName") || message.startsWith("Unknown toEndpointName") ->
					unknownEndpointRejections.add(message)
				// ApproveTrainTool / RequestRouteTool / CancelRouteTool train-id rejections.
				message.startsWith("Unknown trainId") || message.startsWith("Unknown trainName") ->
					unknownTrainRejections.add(message)
				else -> otherRejections.add(message)
			}
		}
	}

	/** Passes calls through to the real tool, recording arguments and results on the way back. */
	private class RecordingTool(
		private val delegate: DomainTool,
		private val recorder: RejectionRecorder
	) : DomainTool {
		override val name: String get() = delegate.name
		override val description: String get() = delegate.description
		override val parameters: List<DomainToolParameter> get() = delegate.parameters

		override suspend fun execute(args: Map<String, Any?>): ToolResult =
			delegate.execute(args).also { recorder.record(delegate.name, args, it) }
	}

	/** Wraps every tool in a [RecordingTool] before handing them to the real agent service. */
	private class RecordingAgentService(
		private val delegate: AgentService,
		private val recorder: RejectionRecorder
	) : AgentService {
		override suspend fun createDispatchAgent(
			modelName: String,
			tools: List<DomainTool>,
			systemPrompt: String?
		): KoogDispatchAgent =
			delegate.createDispatchAgent(
				modelName = modelName,
				tools = tools.map { RecordingTool(it, recorder) },
				systemPrompt = systemPrompt
			)
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/** No trains running yet; the model's job this cycle is to admit and route the queued one. */
	private fun perceptionPort(): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort> {
			every { snapshot() } returns
				SimulationSnapshot.EMPTY.copy(trainPositions = emptyList<TrainPositionReading>())
		}

	private fun sensorPort(): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort> {
			every { getQueuedTrains() } returns
				listOf(QueuedTrainObservation(trainId = queuedTrainId, destinationInOutName = "B"))
		}

	/**
	 * Runs [cycles] real dispatch cycles through the production agent and returns what the tools
	 * saw across all of them.
	 *
	 * More than one cycle on purpose. Round 2's rejected-call counts (86 endpoint, 90 train-id)
	 * accumulated over a 600 s run of hundreds of cycles; a single cycle with one queued train
	 * bound for "B" is easy enough that the model gets it right even from a deliberately broken
	 * prompt, so a one-cycle assertion cannot discriminate. Several cycles against one agent — the
	 * same agent instance a run reuses — gives hallucination a realistic chance to appear.
	 */
	private fun runCycles(cycles: Int = DEFAULT_CYCLES): RejectionRecorder {
		val recorder = RejectionRecorder()
		loadShuntingLoopContext().use { context ->
			val config = OllamaExecutorConfig.forLocalTesting()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = config,
					agentService = RecordingAgentService(DefaultAgentService(OllamaSimpleExecutor(config), config), recorder),
					perceptionPort = perceptionPort(),
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = sensorPort(),
					sinkHolder = SinkHolder()
				)
			val observation =
				DispatchObservation(
					snapshot = SimulationSnapshot.EMPTY,
					unapprovedTrains = listOf(QueuedTrainObservation(trainId = queuedTrainId, destinationInOutName = "B")),
					innerBlockInputs = emptyList(),
					outerBlockInputs = emptyList()
				)
			runBlocking {
				withTimeout(testTimeoutMillis) {
					val agent = factory.createAgent(context)
					repeat(cycles) { agent.decideAsync(observation) }
				}
			}
		}
		return recorder
	}

	/**
	 * Round-2 fix #2 territory: `KoogAgentFactory` accepts InOuts ∪ Signals — `A, B, doA1, doA2,
	 * doB1, doB2, zA, zB` for `vyhybna.xml` — while round 1's topology block advertised only the
	 * InOuts, leaving the model reaching for block names (86 rejected endpoint calls in one run, 48
	 * naming the block `k1`).
	 *
	 * **Verified non-discriminating**: still green with the Signals list removed, and with the
	 * Blocks list stripped of its disqualifying label. See the class KDoc — this is a smoke test.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("no route call is rejected for naming an endpoint that does not exist")
	fun noEndpointIsHallucinated() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownEndpointRejections, "endpoint names the network does not have").isEmpty()
	}

	/**
	 * Round-2 fixes #1, #4 and #5 territory: round 1's system prompt carried a worked example
	 * printing `request_route(trainName="T1", …)`, and because it sat in the *system* prompt it was
	 * present every cycle; the model copied `"T1"` verbatim, reserving real blocks for a train that
	 * does not exist and that nothing can ever release.
	 *
	 * **Verified non-discriminating**: still green with that literal `"T1"` example restored to the
	 * system prompt. See the class KDoc — this is a smoke test.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("no tool call names a train absent from this cycle's message")
	fun noTrainIdIsHallucinated() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownTrainRejections, "train ids no train in this cycle bears").isEmpty()
		assertThat(
			recorder.calls.filter { (_, args) -> args.values.any { it?.toString()?.contains("T1") == true } },
			"calls carrying the literal T1 from round 1's worked example"
		).isEmpty()
	}

	/**
	 * Round-2 fix #3 and the admission-first prompt clause. Round 1's prompts instructed the model,
	 * on every turn, to "check `queued_trains` and `all_train_positions` … before doing anything
	 * else" — tools deleted in SP2c.6 (#869). The live surface is exactly four actuators, and a
	 * queued train departs only when `approve_train` is called for it.
	 *
	 * Asserts only that the model acted at all and that nothing it did was rejected; which of the
	 * four actuators it reaches for first is an LLM-quality question, not a prompt-correctness one.
	 * The broadest of the three, and the one most likely to catch a genuine surface break — an
	 * argument the tools cannot parse at all shows up here as an `otherRejections` entry.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("the model acts through the four-actuator surface without a single rejected call")
	fun everyCallLandsOnTheRealSurface() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownEndpointRejections, "unknown-endpoint rejections").isEmpty()
		assertThat(recorder.unknownTrainRejections, "unknown-train rejections").isEmpty()
		assertThat(recorder.otherRejections, "any other tool rejection").isEmpty()
	}
}
