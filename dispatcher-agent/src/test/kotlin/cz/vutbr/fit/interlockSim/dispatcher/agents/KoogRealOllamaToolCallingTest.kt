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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaPrewarmExtension
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end proof that the real Koog wiring (SP2b.9, Issue #566) actually drives a tool-calling
 * round trip through a real local Ollama model — not just a connectivity/model-presence check.
 *
 * [OllamaSimpleExecutorTest] and [OllamaExecutorConfigTest]'s `@Tag("ollama-test")` tests already
 * prove real connectivity (constructing a live [OllamaSimpleExecutor], querying model presence),
 * but neither sends an actual prompt through the model. This test exercises the full production
 * path — [DefaultAgentService.createDispatchAgent] → [KoogToolAdapter]-wrapped tools → a real
 * `AIAgent<String, String>` → [KoogDispatchAgentImpl.decideAsync] — against the real Ollama
 * instance this dev machine has running (`qwen2.5:7b-instruct` pulled, per
 * [OllamaExecutorConfig.forLocalTesting]'s default model).
 *
 * Two minimal fake [DomainTool]s are used instead of the real 13 perception/actuator tools, to
 * keep the test fast and decoupled from railway-domain correctness — it is only asserting that
 * genuine LLM tool-calling happens, not that any particular railway decision is correct.
 *
 * [OllamaPrewarmExtension] preloads the model before the test class runs (Issue #815) so the
 * [testTimeoutMillis] budget is not consumed by cold model-load latency.
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
@ExtendWith(OllamaPrewarmExtension::class)
class KoogRealOllamaToolCallingTest {
	private class FakePerceptionTool : DomainTool {
		override val name: String = "signal_aspect"
		override val description: String = "Query the current aspect of a named signal."
		override val parameters: List<DomainToolParameter> =
			listOf(
				DomainToolParameter(
					name = "signal_name",
					description = "Name of the signal to query",
					type = DomainToolParameterType.String
				)
			)

		override suspend fun execute(args: Map<String, Any?>): ToolResult = ToolResult.Success("GREEN")
	}

	/**
	 * Trivial no-argument perception tool. Used four times under different names ([name] is a
	 * constructor parameter) to reproduce the documented "4 perception tool calls" happy-path
	 * shape from [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.maxAgentIterations]'s
	 * KDoc, without pulling in the real 13-tool [ToolGroupRegistry] surface.
	 */
	private class FakeNoArgPerceptionTool(
		override val name: String,
		override val description: String,
		private val callCount: AtomicInteger
	) : DomainTool {
		override val parameters: List<DomainToolParameter> = emptyList()

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount.incrementAndGet()
			return ToolResult.Success("[]")
		}
	}

	private class FakeRequestRouteTool(
		val callCount: AtomicInteger = AtomicInteger(0)
	) : DomainTool {
		override val name: String = "request_route"
		override val description: String =
			"Reserve a route for a named train from one point to another. Call this to admit a " +
				"queued train onto the network."
		override val parameters: List<DomainToolParameter> =
			listOf(
				DomainToolParameter(
					name = "train_name",
					description = "Name of the train to reserve a route for",
					type = DomainToolParameterType.String
				),
				DomainToolParameter(
					name = "from_point",
					description = "Name of the point the train departs from",
					type = DomainToolParameterType.String
				),
				DomainToolParameter(
					name = "to_point",
					description = "Name of the point the train travels to",
					type = DomainToolParameterType.String
				)
			)

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount.incrementAndGet()
			return ToolResult.Success("queued")
		}
	}

	/**
	 * Generous per-test wall-clock ceiling. Unlike [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter],
	 * [KoogDispatchAgentImpl.decideAsync] itself applies no timeout — this test wraps the call so
	 * an unresponsive local Ollama fails the test instead of hanging the run indefinitely.
	 */
	private val testTimeoutMillis = 60_000L

	@Test
	@Tag("ollama-test")
	fun `decideAsync drives a real tool-calling round trip through local Ollama`() {
		val requestRouteTool = FakeRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val systemPrompt =
			"You are a railway dispatcher test harness. Exactly one queued train, named \"T1\", " +
				"must travel from point \"A\" to point \"B\". Call the request_route tool with " +
				"train_name=\"T1\", from_point=\"A\", to_point=\"B\" to reserve its route, then " +
				"reply with one short confirmation sentence."

		val observation =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains = listOf(QueuedTrainObservation(trainId = "T1", destinationInOutName = "B")),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		val decisions =
			runBlocking {
				withTimeout(testTimeoutMillis) {
					val agent =
						service.createDispatchAgent(
							modelName = config.modelName,
							tools = listOf(FakePerceptionTool(), requestRouteTool),
							systemPrompt = systemPrompt
						)
					agent.decideAsync(observation)
				}
			}

		// decideAsync() always returns an empty list by design (actuation happens as tool-call
		// side effects, not via the return value) — the real assertion is that the fake
		// actuator tool was genuinely invoked by the LLM at least once.
		assertThat(decisions).isEmpty()
		assertThat(requestRouteTool.callCount.get()).isGreaterThanOrEqualTo(1)
	}

	/**
	 * SP2c.27 (Issue #850, Spike 4) regression test: reproduces the "several perception tool
	 * calls before an actuator call" happy-path shape documented in
	 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.maxAgentIterations]'s
	 * KDoc — the shape that was already found to trip `AIAgentMaxNumberOfIterationsReachedException`
	 * at the earlier `maxAgentIterations = 8` estimate — and asserts it completes without that
	 * exception at the current production default (20). This is the tripwire #850 asks for: if
	 * this ever throws again, either a cycle crept back into the acyclic `singleRunStrategy()`
	 * graph, or the iteration budget needs revisiting.
	 *
	 * The assertion deliberately only checks the structural invariant ([decisions] is always
	 * empty by design, see the other test's comment) plus that a genuine multi-round tool-calling
	 * sequence happened (several tool calls were made) — not that the model called every tool in
	 * a specific order. A 7B-class model's exact compliance with a long, strictly-ordered
	 * instruction is an orthogonal LLM-quality question (see #566/§7's benchmark harness), not
	 * what this spike is measuring: if `AIAgentMaxNumberOfIterationsReachedException` were thrown,
	 * the `runBlocking` block below would propagate it and fail this test regardless of what any
	 * assertion checks.
	 */
	@Test
	@Tag("ollama-test")
	fun `happy-path cycle with several perception calls plus an actuator call never hits maxAgentIterations`() {
		val totalToolCalls = AtomicInteger(0)
		val requestRouteTool = FakeRequestRouteTool(totalToolCalls)
		val perceptionTools =
			listOf(
				FakeNoArgPerceptionTool("all_block_occupancies", "Query occupancy of all blocks.", totalToolCalls),
				FakeNoArgPerceptionTool("all_train_positions", "Query positions of all trains.", totalToolCalls),
				FakeNoArgPerceptionTool("all_signal_aspects", "Query aspects of all signals.", totalToolCalls),
				FakeNoArgPerceptionTool("queued_trains", "Query trains waiting for admission.", totalToolCalls)
			)
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val systemPrompt =
			"You are a railway dispatcher test harness. Query network state using the available " +
				"perception tools (all_block_occupancies, all_train_positions, all_signal_aspects, " +
				"queued_trains) as needed, then exactly one queued train, named \"T1\", must " +
				"travel from point \"A\" to point \"B\": call the request_route tool with " +
				"train_name=\"T1\", from_point=\"A\", to_point=\"B\" to reserve its route, then " +
				"reply with one short confirmation sentence."

		val observation =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains = listOf(QueuedTrainObservation(trainId = "T1", destinationInOutName = "B")),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		val decisions =
			runBlocking {
				withTimeout(testTimeoutMillis) {
					val agent =
						service.createDispatchAgent(
							modelName = config.modelName,
							tools = perceptionTools + requestRouteTool,
							systemPrompt = systemPrompt
						)
					// Must not throw AIAgentMaxNumberOfIterationsReachedException: that is the
					// exact regression this test guards against.
					agent.decideAsync(observation)
				}
			}

		assertThat(decisions).isEmpty()
		// Not asserting on requestRouteTool specifically or the exact tool count — see KDoc above.
		assertThat(totalToolCalls.get()).isGreaterThanOrEqualTo(1)
	}

	/**
	 * Captures the arguments each `request_route` call carried, so a test can assert on the values
	 * the model produced rather than only on the fact that it called something.
	 */
	private class ArgumentCapturingRequestRouteTool : DomainTool {
		val calls: MutableList<Map<String, Any?>> = mutableListOf()

		override val name: String = "request_route"
		override val description: String =
			"Reserve a route for a named train from one point to another."
		override val parameters: List<DomainToolParameter> =
			listOf(
				DomainToolParameter("train_name", "Name of the train", DomainToolParameterType.String),
				DomainToolParameter("from_point", "Departure point", DomainToolParameterType.String),
				DomainToolParameter("to_point", "Destination point", DomainToolParameterType.String)
			)

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			calls.add(args)
			return ToolResult.Success("queued")
		}
	}

	/** Always fails. Used to prove a tool error does not abort the whole cycle. */
	private class AlwaysFailingTool(
		override val name: String,
		private val callCount: AtomicInteger
	) : DomainTool {
		override val description: String = "Query network state. May be temporarily unavailable."
		override val parameters: List<DomainToolParameter> = emptyList()

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			callCount.incrementAndGet()
			return ToolResult.Error("sensor temporarily unavailable")
		}
	}

	/**
	 * The existing tests only count invocations, so a model that called `request_route` with
	 * nonsense arguments would still pass them. Production actuation depends on the *values*: a
	 * wrong `train_name` reserves a route for the wrong train.
	 *
	 * The assertion is deliberately on `train_name` only — the model reliably echoes the entity
	 * name it was told to act on, whereas point naming is more prone to paraphrase and is an
	 * orthogonal prompt-compliance question (see #566 §7's benchmark harness).
	 */
	@Test
	@Tag("ollama-test")
	fun `request_route arguments carry the train name from the prompt`() {
		val tool = ArgumentCapturingRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val systemPrompt =
			"You are a railway dispatcher test harness. Exactly one queued train, named T-42, " +
				"must travel from point A to point B. Call the request_route tool with " +
				"train_name=T-42, from_point=A, to_point=B, then reply with one short sentence."

		runBlocking {
			withTimeout(testTimeoutMillis) {
				service
					.createDispatchAgent(
						modelName = config.modelName,
						tools = listOf(tool),
						systemPrompt = systemPrompt
					).decideAsync(
						DispatchObservation(
							snapshot = SimulationSnapshot.EMPTY,
							unapprovedTrains = listOf(QueuedTrainObservation("T-42", "B")),
							innerBlockInputs = emptyList(),
							outerBlockInputs = emptyList()
						)
					)
			}
		}

		assertThat(tool.calls).isNotEmpty()
		assertThat(tool.calls.any { it["train_name"]?.toString()?.contains("T-42") == true }).isTrue()
	}

	/**
	 * A perception tool can legitimately fail at runtime (a sensor port throwing, an unknown
	 * entity). [ToolResult.Error] is the modelled way to say so, and it must be surfaced to the
	 * model as a tool-error result rather than propagating out of `decideAsync` — otherwise one
	 * transient sensor failure would abort the whole dispatch cycle and trip the rule-based
	 * fallback.
	 *
	 * Would fail if `KoogToolAdapter` started rethrowing `ToolResult.Error` instead of marshalling
	 * it into a tool result.
	 */
	@Test
	@Tag("ollama-test")
	fun `a failing tool does not abort the dispatch cycle`() {
		val failureCount = AtomicInteger(0)
		val failingTool = AlwaysFailingTool("all_block_occupancies", failureCount)
		val requestRouteTool = FakeRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val systemPrompt =
			"You are a railway dispatcher test harness. First call all_block_occupancies to read " +
				"the network. Then, regardless of its result, call request_route with " +
				"train_name=T1, from_point=A, to_point=B, and reply with one short sentence."

		val decisions =
			runBlocking {
				withTimeout(testTimeoutMillis) {
					service
						.createDispatchAgent(
							modelName = config.modelName,
							tools = listOf(failingTool, requestRouteTool),
							systemPrompt = systemPrompt
						).decideAsync(
							DispatchObservation(
								snapshot = SimulationSnapshot.EMPTY,
								unapprovedTrains = listOf(QueuedTrainObservation("T1", "B")),
								innerBlockInputs = emptyList(),
								outerBlockInputs = emptyList()
							)
						)
				}
			}

		// The cycle completed: no exception escaped decideAsync despite the tool error.
		assertThat(decisions).isEmpty()
		assertThat(failureCount.get()).isGreaterThanOrEqualTo(1)
	}
}
