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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaCycleReplies
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
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
		val executor: OllamaSimpleExecutor
	)

	private fun <T> withStack(
		tools: List<DomainTool> = emptyList(),
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
			val adapter =
				KoogAgentPlanAdapter(
					agentFactory,
					mockk<DefaultSimulationContext>(),
					fallback,
					Duration.ofSeconds(10),
					queue,
					SinkHolder()
				)
			val recorder = recorder()
			val ticks = mutableListOf<TickRecord>()
			adapter.addTickListener { record ->
				ticks.add(record)
				recorder.onTick(record)
			}
			return block(Stack(adapter, fallback, queue, recorder, ticks, executor))
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
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY.copy(simTime = 5.0),
			unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

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
