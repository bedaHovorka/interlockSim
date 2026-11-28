/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmittedActionSink
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaCycleReplies
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher as MockDispatcher

/**
 * Issue #1342, choice (i) of the agent-architect ruling: a cycle whose generation was cut by the
 * model profile's `num_predict` (Ollama `done_reason == "length"`) acts on nothing.
 *
 * Without emission it is a [TickOutcome.TIMEOUT_NOOP] tick with cause
 * [TimeoutNoOpCause.THINKING_TRUNCATED]: no retry, no rule-based fallback, one circuit-breaker
 * failure. The twin with `done_reason == "stop"` keeps today's silent-cycle path.
 *
 * The CI tests run the real [KoogAgentPlanAdapter] → [DefaultAgentService] → [OllamaSimpleExecutor]
 * stack against [MockWebServer]; no live Ollama is called. This is also probe P4 of the ruling on
 * the full Koog agent path: the cycle's [OllamaCycleReplies] reaches the plugin hook through Koog.
 */
@DisplayName("A cycle cut by num_predict acts on nothing (Issue #1342)")
class ThinkingTruncatedCycleTest {
	private companion object {
		const val MODEL_NAME = "qwen2.5:7b-instruct"
		const val DONE_REASON_LENGTH = "length"
		const val DONE_REASON_STOP = "stop"

		/** The endpoints of the scripted actuator call. */
		const val ENTRY = "zB"
		const val TARGET = "doA1"
	}

	private lateinit var server: MockWebServer
	private val chatRequests = AtomicInteger()

	@Volatile
	private var doneReason = DONE_REASON_LENGTH

	/** Scripted `/api/chat` bodies served in order before falling back to [chatReply]. */
	private val scriptedReplies = ConcurrentLinkedQueue<String>()

	/** A queued train: the station is not idle, so today's silent cycle consults the fallback. */
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY.copy(simTime = 10.0),
			unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.dispatcher =
			object : MockDispatcher() {
				override fun dispatch(request: RecordedRequest): MockResponse {
					val body =
						if (request.path == "/api/chat") {
							chatRequests.incrementAndGet()
							scriptedReplies.poll() ?: chatReply(doneReason)
						} else {
							"{}"
						}
					return MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json")
				}
			}
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	/**
	 * A cut reply is a thinking model whose budget went into thinking: empty content, no tool call
	 * (Koog 1.1.1 fails on that reply, so without the cut ending the adapter would retry it and
	 * then fall back). The finished twin is an ordinary silent answer: plain text, no tool call.
	 */
	private fun chatReply(doneReason: String): String =
		if (doneReason == DONE_REASON_LENGTH) {
			"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"",""" +
				""""thinking":"Let me think about the queued train"},"done":true,""" +
				""""done_reason":"$doneReason","prompt_eval_count":900,"eval_count":2048}"""
		} else {
			"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"Nothing to do."},""" +
				""""done":true,"done_reason":"$doneReason","prompt_eval_count":900,"eval_count":5}"""
		}

	private fun recorder(): DefaultDispatcherRunRecorder =
		DefaultDispatcherRunRecorder(
			runId = "thinking-truncated",
			arm = DispatcherArm.LLM_TOOL_CALLING,
			params =
				RunParameters(
					tickPeriodMs = 0L,
					historyN = 0,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = MODEL_NAME,
					seed = null
				)
		)

	private class Stack(
		val adapter: KoogAgentPlanAdapter,
		val fallback: Dispatcher,
		val queue: ActuatorCommandQueue,
		val recorder: DefaultDispatcherRunRecorder,
		val ticks: MutableList<TickRecord>,
		val executor: OllamaSimpleExecutor,
		val sinkHolder: SinkHolder
	)

	private fun <T> withStack(
		tools: List<DomainTool> = emptyList(),
		sinkHolder: SinkHolder = SinkHolder(),
		toolEmissionsReachQueue: Boolean = false,
		block: (Stack) -> T
	): T {
		val config = OllamaExecutorConfig(ollamaEndpoint = server.url("/").toString().trimEnd('/'))
		val executor = OllamaSimpleExecutor(config)
		try {
			val agent =
				runBlocking {
					DefaultAgentService(executor, config).createDispatchAgent(
						modelName = config.modelName,
						tools = tools,
						systemPrompt = "You are a test dispatcher."
					)
				}
			val agentFactory = mockk<KoogAgentFactory>()
			coEvery { agentFactory.createAgent(any()) } returns agent
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.ApproveTrain("Train #1"))
			val queue = ActuatorCommandQueue()
			// The queue-posting wrapper KoogAgentFactory installs in production, so a test that
			// drives a real actuator tool asserts exactly what reaches the command queue.
			if (toolEmissionsReachQueue) {
				sinkHolder.current =
					EmittedActionSink { action ->
						val decisions: List<DispatchDecision> =
							when (action) {
								is DispatchAction.ApproveTrain ->
									listOf(DispatchDecision.ApproveTrain(trainId = action.trainId))
								is DispatchAction.RequestRoute ->
									listOf(
										DispatchDecision.RequestRoute(
											action.trainId,
											action.fromEndpointName,
											action.toEndpointName
										)
									)
								is DispatchAction.CancelRoute ->
									listOf(DispatchDecision.ReleaseRoute(trainName = action.trainId))
								is DispatchAction.NoOp -> emptyList()
							}
						queue.postAll(decisions, ActionAuthor.LLM)
					}
			}
			val adapter =
				KoogAgentPlanAdapter(
					agentFactory,
					mockk<DefaultSimulationContext>(),
					fallback,
					Duration.ofSeconds(10),
					queue,
					sinkHolder
				)
			val recorder = recorder()
			val ticks = mutableListOf<TickRecord>()
			adapter.addTickListener { record ->
				ticks.add(record)
				recorder.onTick(record)
			}
			return block(Stack(adapter, fallback, queue, recorder, ticks, executor, sinkHolder))
		} finally {
			executor.close()
		}
	}

	@Test
	fun `a cut generation is a TIMEOUT_NOOP tick with cause THINKING_TRUNCATED and acts on nothing`() {
		doneReason = DONE_REASON_LENGTH
		withStack { stack ->
			val decisions = runBlocking { stack.adapter.plan(observation) }

			assertThat(decisions, "no decision is returned").isEmpty()
			assertThat(stack.ticks.map { it.outcome to it.timeoutNoOpCause })
				.containsExactly(TickOutcome.TIMEOUT_NOOP to TimeoutNoOpCause.THINKING_TRUNCATED)
			verify(exactly = 0) { stack.fallback.decide(any()) }
			assertThat(stack.queue.approximateSize(), "the command queue stays empty").isEqualTo(0)
			assertThat(chatRequests.get(), "exactly one /api/chat request: a cut is never retried").isEqualTo(1)
			assertThat(stack.adapter.circuitBreaker.consecutiveFailures, "the breaker counts the cut").isEqualTo(1)
			val snapshot = stack.recorder.snapshot()
			assertThat(snapshot.timeoutNoOpByCause[TimeoutNoOpCause.THINKING_TRUNCATED.name]).isEqualTo(1L)
			assertThat(snapshot.ticksByOutcome[TickOutcome.RULE_FALLBACK.name] ?: 0L).isEqualTo(0L)
		}
	}

	/**
	 * P3 on the agent path (review finding 1): Koog's own tool loop sends the second `/api/chat`
	 * after a tool ran, and the cut of that second reply still reaches the cycle's
	 * [OllamaCycleReplies]. Reply 1 is a finished tool call, reply 2 a cut thinking-only reply.
	 */
	@Test
	fun `a tool call followed by a cut final reply inside the Koog tool loop is a cut cycle`() {
		val toolRuns = AtomicInteger()
		val inspect =
			object : DomainTool {
				override val name = "inspect_station"
				override val description = "Reads the station state (test double)."
				override val parameters = emptyList<DomainToolParameter>()

				override suspend fun execute(args: Map<String, Any?>): ToolResult {
					toolRuns.incrementAndGet()
					return ToolResult.Success("all quiet")
				}
			}
		scriptedReplies.add(
			"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"",""" +
				""""tool_calls":[{"function":{"name":"inspect_station","arguments":{}}}]},""" +
				""""done":true,"done_reason":"$DONE_REASON_STOP","prompt_eval_count":900,"eval_count":12}"""
		)
		scriptedReplies.add(chatReply(DONE_REASON_LENGTH))
		withStack(tools = listOf(inspect)) { stack ->
			val decisions = runBlocking { stack.adapter.plan(observation) }

			assertThat(toolRuns.get(), "Koog ran the tool between the two replies").isEqualTo(1)
			assertThat(chatRequests.get(), "Koog's tool loop sent the second /api/chat itself").isEqualTo(2)
			assertThat(decisions).isEmpty()
			assertThat(stack.ticks.map { it.outcome to it.timeoutNoOpCause })
				.containsExactly(TickOutcome.TIMEOUT_NOOP to TimeoutNoOpCause.THINKING_TRUNCATED)
			verify(exactly = 0) { stack.fallback.decide(any()) }
			assertThat(stack.adapter.circuitBreaker.consecutiveFailures).isEqualTo(1)
		}
	}

	/**
	 * The dangerous order the cap produces most often (Copilot review finding, 2026-11-27): the
	 * FIRST reply of the loop is already cut and still carries a complete, valid actuator call.
	 * Before the emission gate, Koog executed that call inside its loop and posted the command to
	 * the queue, so the cycle acted on a payload the model never finished generating and recorded
	 * as a normal `LLM_ACTIONS` tick with no cut anywhere in the run JSON.
	 */
	@Test
	fun `a cut reply that carries a complete actuator call emits nothing`() {
		scriptedReplies.add(
			"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"",""" +
				""""tool_calls":[{"function":{"name":"request_route","arguments":{"trainName":"Train #1",""" +
				""""fromEndpointName":"$ENTRY","toEndpointName":"$TARGET"}}}]},""" +
				""""done":true,"done_reason":"$DONE_REASON_LENGTH","prompt_eval_count":900,"eval_count":2047}"""
		)
		val sinkHolder = SinkHolder()
		val routeTool = RequestRouteTool(sinkHolder, validEndpointNames = setOf(ENTRY, TARGET))
		// Every later reply is a cut thinking-only answer, so Koog's tool loop ends after it.
		withStack(tools = listOf(routeTool), sinkHolder = sinkHolder, toolEmissionsReachQueue = true) { stack ->
			val decisions = runBlocking { stack.adapter.plan(observation) }

			assertThat(decisions, "no decision is returned").isEmpty()
			assertThat(stack.queue.approximateSize(), "the cut reply's call posted no command").isEqualTo(0)
			assertThat(stack.sinkHolder.actedThisCycle(), "the cut reply acted on nothing").isFalse()
			assertThat(stack.ticks.map { it.outcome to it.timeoutNoOpCause })
				.containsExactly(TickOutcome.TIMEOUT_NOOP to TimeoutNoOpCause.THINKING_TRUNCATED)
			assertThat(stack.ticks.single().generationCut, "the cycle is named as cut").isTrue()
			verify(exactly = 0) { stack.fallback.decide(any()) }
			assertThat(stack.adapter.circuitBreaker.consecutiveFailures, "the breaker counts the cut")
				.isEqualTo(1)
			// The run-record trace (owner ruling of 2026-11-27): the cut cycle is counted even
			// though nothing acts.
			assertThat(stack.recorder.snapshot().generationCutCycles).isEqualTo(1L)
		}
	}

	/** The honest companion of the gate above: emissions from complete replies of the same cycle stand. */
	@Test
	fun `genuinely earlier emissions of the same cycle still stand beside a later cut`() {
		// Reply 1 is complete (done_reason=stop) and carries a valid actuator call: the route it
		// requests is the model's deliberate, fully generated decision. Reply 2 is cut afterwards.
		scriptedReplies.add(
			"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"",""" +
				""""tool_calls":[{"function":{"name":"request_route","arguments":{"trainName":"Train #1",""" +
				""""fromEndpointName":"$ENTRY","toEndpointName":"$TARGET"}}}]},""" +
				""""done":true,"done_reason":"$DONE_REASON_STOP","prompt_eval_count":900,"eval_count":12}"""
		)
		scriptedReplies.add(chatReply(DONE_REASON_LENGTH))
		val sinkHolder = SinkHolder()
		val routeTool = RequestRouteTool(sinkHolder, validEndpointNames = setOf(ENTRY, TARGET))
		withStack(tools = listOf(routeTool), sinkHolder = sinkHolder, toolEmissionsReachQueue = true) { stack ->
			runBlocking { stack.adapter.plan(observation) }

			assertThat(stack.queue.approximateSize(), "the complete reply's command stands").isEqualTo(1)
			assertThat(stack.sinkHolder.actedThisCycle()).isTrue()
			assertThat(stack.ticks.map { it.outcome }).containsExactly(TickOutcome.LLM_ACTIONS)
			// The mixed cycle's trace: the outcome came from the emissions, the cut still shows.
			assertThat(stack.ticks.single().generationCut, "the later cut is named on the tick").isTrue()
			assertThat(stack.recorder.snapshot().generationCutCycles).isEqualTo(1L)
			verify(exactly = 0) { stack.fallback.decide(any()) }
		}
	}

	@Test
	fun `twin - a finished generation keeps the silent-cycle fallback path`() {
		doneReason = DONE_REASON_STOP
		withStack { stack ->
			val decisions = runBlocking { stack.adapter.plan(observation) }

			assertThat(decisions).containsExactly(DispatchDecision.ApproveTrain("Train #1"))
			assertThat(stack.ticks.map { it.outcome to it.timeoutNoOpCause })
				.containsExactly(TickOutcome.RULE_FALLBACK to null)
			verify(exactly = 1) { stack.fallback.decide(any()) }
			assertThat(chatRequests.get()).isEqualTo(1)
			assertThat(stack.adapter.circuitBreaker.consecutiveFailures).isEqualTo(0)
			assertThat(stack.recorder.snapshot().timeoutNoOpByCause[TimeoutNoOpCause.THINKING_TRUNCATED.name])
				.isEqualTo(0L)
		}
	}

	@Test
	fun `three cut cycles open the circuit breaker and the breaker skip falls back`() {
		doneReason = DONE_REASON_LENGTH
		withStack { stack ->
			repeat(LlmCircuitBreaker.DEFAULT_FAILURE_THRESHOLD) { runBlocking { stack.adapter.plan(observation) } }

			assertThat(stack.adapter.circuitBreaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
			assertThat(stack.adapter.circuitBreaker.openCount).isEqualTo(1L)

			val decisions = runBlocking { stack.adapter.plan(observation) }

			assertThat(decisions, "the breaker skip runs today's fallback")
				.containsExactly(DispatchDecision.ApproveTrain("Train #1"))
			assertThat(chatRequests.get(), "the skipped cycle sends no request").isEqualTo(3)
			assertThat(stack.ticks.map { it.outcome }).containsExactly(
				TickOutcome.TIMEOUT_NOOP,
				TickOutcome.TIMEOUT_NOOP,
				TickOutcome.TIMEOUT_NOOP,
				TickOutcome.RULE_FALLBACK
			)
		}
	}
}

/**
 * The adapter-level rules of the cut ending, with a scripted agent that marks the cycle's
 * [OllamaCycleReplies] itself — what [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaReplyObserver]
 * does on the wire.
 */
@DisplayName("Cut-cycle rules of KoogAgentPlanAdapter (Issue #1342)")
class ThinkingTruncatedAdapterRulesTest {
	private companion object {
		/** The offered-hop train of the [offeringObservation] shape. */
		const val OFFERED_TRAIN = "Train #4"
		const val OFFERED_ORIGIN = "zB"
		const val OFFERED_TARGET = "doA1"
	}

	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY.copy(simTime = 5.0),
			unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	/**
	 * One active train whose cycle message offers one NEXT SECTION hop (`zB -> doA1`), in the same
	 * shape the offer-count test builds — the hop the skipped-offer accounting exists for.
	 */
	private fun offeringObservation(simTime: Double): DispatchObservation =
		DispatchObservation(
			snapshot =
				SimulationSnapshot.EMPTY.copy(
					simTime = simTime,
					trainPositions = listOf(trainPosition(OFFERED_TRAIN, velocity = 5.0))
				),
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs =
				listOf(
					BlockInputObservation(
						blockId = "kB",
						towardSemaphoreName = OFFERED_ORIGIN,
						state = TrackFacility.State.OCCUPIED,
						ownerTrainId = OFFERED_TRAIN,
						isApproachingThisInput = true,
						pathSetUpTowardThisInput = false,
						pathAlreadyExtendedBeyond = false,
						candidateTargets = semaphoreCandidates(OFFERED_TARGET)
					)
				)
		)

	private fun offeredCount(): NextSectionOfferCount =
		NextSectionOfferCount(offered = 1, skippedTrainIds = setOf(OFFERED_TRAIN), skippedStandingAtSignal = 0)

	/** Marks the cycle's replies cut, as the observer would; returns whether the adapter installed them. */
	private suspend fun markCut(): Boolean {
		val replies = currentCoroutineContext()[OllamaCycleReplies] ?: return false
		replies.record(doneReason = "length", evalCount = 2048)
		return true
	}

	@Test
	fun `a cut cycle that already emitted keeps its emissions and their classification`() {
		val sinkHolder = SinkHolder()
		val koogAgent = mockk<KoogDispatchAgent>()
		var sinkInstalled = false
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			sinkHolder.emit(DispatchAction.ApproveTrain("Train #1"))
			sinkInstalled = markCut()
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val breaker = LlmCircuitBreaker()
		val ticks = mutableListOf<TickRecord>()
		val adapter = adapter(koogAgent, fallback, sinkHolder = sinkHolder, circuitBreaker = breaker)
		adapter.addTickListener { ticks.add(it) }

		runBlocking { adapter.plan(observation) }

		assertThat(sinkInstalled, "the adapter installed the cycle's replies").isTrue()
		assertThat(ticks.map { it.outcome to it.timeoutNoOpCause }).containsExactly(TickOutcome.LLM_ACTIONS to null)
		verify(exactly = 0) { fallback.decide(any()) }
		assertThat(breaker.consecutiveFailures).isEqualTo(1)
	}

	@Test
	fun `a cut cycle whose attempt failed is not retried`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			markCut()
			error("Koog could not use the cut reply")
		}
		val fallback = mockk<Dispatcher>()
		val ticks = mutableListOf<TickRecord>()
		val adapter = adapter(koogAgent, fallback)
		adapter.addTickListener { ticks.add(it) }

		runBlocking { adapter.plan(observation) }

		coVerify(exactly = 1) { koogAgent.decideAsync(any()) }
		verify(exactly = 0) { fallback.decide(any()) }
		assertThat(ticks.map { it.outcome to it.timeoutNoOpCause })
			.containsExactly(TickOutcome.TIMEOUT_NOOP to TimeoutNoOpCause.THINKING_TRUNCATED)
	}

	/**
	 * The cut ending skips the rule-based fallback, so it counts like its three sibling endings
	 * (Copilot review finding, 2026-11-27): the cycle's offered NEXT SECTION hop must reach
	 * [TickRecord.nextSectionOffers], and a train skipped across consecutive cut cycles must extend
	 * its streak — not have every open streak cleared on the false premise that the fallback ran.
	 * Before the count was passed, an all-cut run published the whole offer group as not-measured.
	 */
	@Test
	fun `consecutive cut cycles count and extend the skipped-offer streak of an offered hop`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			markCut()
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		val adapter = adapter(koogAgent, fallback)
		val recorder =
			DefaultDispatcherRunRecorder(
				runId = "consecutive-cut-offers",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params =
					RunParameters(
						tickPeriodMs = 0L,
						historyN = 0,
						temperature = 0.0,
						maxActionsPerTick = 3,
						model = "qwen2.5:7b-instruct",
						seed = null
					)
			)
		val ticks = mutableListOf<TickRecord>()
		adapter.addTickListener { record ->
			ticks.add(record)
			recorder.onTick(record)
		}

		runBlocking {
			adapter.plan(offeringObservation(simTime = 10.0))
			adapter.plan(offeringObservation(simTime = 12.0))
		}

		assertThat(ticks.map { it.nextSectionOffers }).containsExactly(offeredCount(), offeredCount())
		val snapshot = recorder.snapshot()
		assertThat(snapshot.skippedNextSectionOffers, "both cut cycles skipped the same offered hop")
			.isEqualTo(2L)
		assertThat(snapshot.offeredNextSectionHops).isEqualTo(2L)
		assertThat(
			snapshot.longestSkippedOfferStreakCycles,
			"the streak extends across the consecutive cut cycles"
		).isEqualTo(2L)
		assertThat(snapshot.longestSkippedOfferStreakSimSeconds).isEqualTo(2.0)
	}

	@Test
	fun `each cycle gets a fresh replies sink - a cut does not leak into the next cycle`() {
		var cycle = 0
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			cycle++
			if (cycle == 1) markCut()
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		val ticks = mutableListOf<TickRecord>()
		val adapter = adapter(koogAgent, fallback)
		adapter.addTickListener { ticks.add(it) }

		runBlocking {
			adapter.plan(observation)
			adapter.plan(observation)
		}

		assertThat(ticks.map { it.outcome }).containsExactly(
			TickOutcome.TIMEOUT_NOOP,
			TickOutcome.LLM_SILENT_NONACTIONABLE
		)
	}
}
