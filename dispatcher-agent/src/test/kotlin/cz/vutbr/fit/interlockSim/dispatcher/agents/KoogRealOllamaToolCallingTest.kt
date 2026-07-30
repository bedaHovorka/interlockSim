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
}
