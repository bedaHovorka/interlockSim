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

	private companion object {
		/**
		 * Point name for `a rejected tool argument is corrected on a later call`. Deliberately an
		 * arbitrary token that appears in no prompt anywhere in this file, so the only way the
		 * model can emit it is by having read the tool error that names it.
		 */
		const val REQUIRED_POINT = "QX7"
	}

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
	 * Rejects the **first** call whatever it carries, naming [requiredPoint] as the only legal
	 * value, and accepts [requiredPoint] thereafter. Records every value it was asked for, in order.
	 *
	 * Rejecting unconditionally is what makes the experiment airtight: [requiredPoint] is chosen by
	 * the caller to be a token that appears nowhere in the prompt, so the model has no way to
	 * produce it except by reading the error text this tool returned.
	 */
	private class FirstCallRejectingTool(
		private val requiredPoint: String
	) : DomainTool {
		val requestedPoints: MutableList<String> = mutableListOf()

		override val name: String = "reserve_point"
		override val description: String = "Reserve a named track point for a train."
		override val parameters: List<DomainToolParameter> =
			listOf(DomainToolParameter("point", "Name of the point to reserve", DomainToolParameterType.String))

		override suspend fun execute(args: Map<String, Any?>): ToolResult {
			val point = args["point"]?.toString().orEmpty()
			val isFirstCall = requestedPoints.isEmpty()
			requestedPoints.add(point)
			return if (!isFirstCall && point == requiredPoint) {
				ToolResult.Success("reserved $point")
			} else {
				ToolResult.Error("Unknown point '$point' — the only valid point name is: $requiredPoint")
			}
		}
	}

	/**
	 * Settles, empirically, whether a tool's error message reaches the model (Issue #847 round 3).
	 *
	 * PR #891's round-2 comment and [cz.vutbr.fit.interlockSim.dispatcher.agents.tools.resolveTrainId]'s
	 * KDoc both assert that Koog 1.1.1 "drops `MessagePart.Tool.Result` entirely
	 * (`OllamaConverters.kt:115`)", so a tool error can never teach the model and strict rejection
	 * can never converge. Reading the 1.1.1 sources contradicts that: `Prompt.toOllamaChatMessages`
	 * emits every tool-result part as its own `OllamaChatMessageDTO(role = "tool", …)` at lines
	 * 37-44, and line 115 is in a helper that assembles only the *text* half of a user turn and
	 * therefore skips tool parts by design.
	 *
	 * Source reading alone cannot settle it, because what matters is whether the model actually
	 * acts on what arrives. The experiment: the required point name [REQUIRED_POINT] appears
	 * **nowhere in the prompt** — the tool rejects the first call whatever it carries and names
	 * that token in its error text. So a later call carrying it is proof the error text reached
	 * the model; the model cannot have guessed it. If tool results were dropped, the model would
	 * keep re-sending its original guess until the iteration budget ran out — exactly the failure
	 * mode the round-2 comment describes.
	 *
	 * Deliberately tolerant: it does not require the correction to be immediate, nor that the model
	 * stops retrying, only that the token is reached at all.
	 *
	 * ## Issue #893 iterations 2/3 broke this test's setup, not its premise
	 *
	 * Commits 8a289d66 (iteration 2) and 6f258fb5 (iteration 3) taught the dispatcher a new,
	 * deliberate policy: do not retry a call rejected **twice** in the same tick; end the tick with
	 * a short plain-text reply instead. [KoogDispatchAgentImpl.buildUserPrompt] enforces the "end
	 * the tick" half unconditionally on every cycle — its last line changed from "...then use the
	 * tools available to you to reserve routes, release routes, or approve queued trains as needed
	 * ... Respond with plain text when finished" (an invitation to act) to "When your actions for
	 * this tick are done, finish with one short plain-text sentence and no further tool calls." (a
	 * termination instruction with no mention of acting at all) — and that line is unconditionally
	 * the closest-to-generation text the model reads, regardless of what a caller's own
	 * `systemPrompt` says. This test drives [KoogDispatchAgentImpl] straight from
	 * [DefaultAgentService] and therefore cannot avoid that per-cycle renderer.
	 *
	 * Combined with the "Active (approved) trains right now: 0 / N" / "Queued (unapproved) trains:
	 * 0" framing this test's empty [DispatchObservation] produces (there is genuinely nothing to
	 * report), the model stopped calling `reserve_point` at all — reproduced deterministically
	 * across two consecutive live runs before this fix (zero `KoogToolAdapter` invocations logged
	 * either time; `tool.requestedPoints` was empty). That is a setup problem, not evidence against
	 * the transport claim: the experiment cannot observe transport if the tool is never called in
	 * the first place. The fix is the `systemPrompt` below telling the model explicitly that a
	 * plain-text reply with no call at all is not acceptable this turn — restoring the original
	 * single mandatory `reserve_point` call the experiment depends on.
	 *
	 * Once that first call happens, the in-turn correction this test asserts on is exactly what it
	 * was before #893: stable across 6 consecutive live runs during this fix. That is consistent
	 * with, not contradicted by, the new "stop after two rejections" rule — this tool rejects only
	 * the *first* call and accepts the second, so the two-strikes threshold the new policy targets
	 * is never reached here. A tolerant alternative (accept the token in the model's final
	 * plain-text reply as proof of transport, in case a future model chooses not to retry after a
	 * single rejection) was considered but is not implementable without touching production code:
	 * [KoogDispatchAgent.decideAsync] returns only `List<DispatchDecision>` (always empty by
	 * design, see its KDoc) — the final reply text is logged at DEBUG by [KoogDispatchAgentImpl]
	 * but never returned to a caller.
	 */
	@Test
	@Tag("ollama-test")
	fun `a rejected tool argument is corrected on a later call`() {
		val tool = FirstCallRejectingTool(requiredPoint = REQUIRED_POINT)
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		// Names no point at all: the model must obtain one from the tool's error message.
		val systemPrompt =
			"You are a railway dispatcher test harness. You must call the reserve_point tool at " +
				"least once this turn before replying, even if nothing else is going on — a plain-text " +
				"reply with no tool call at all is not acceptable. If a call comes back with an error, " +
				"read the error text, take the point name it says is valid, and call reserve_point " +
				"again with that name. Stop once a call succeeds."

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
							unapprovedTrains = emptyList(),
							innerBlockInputs = emptyList(),
							outerBlockInputs = emptyList()
						)
					)
			}
		}

		assertThat(tool.requestedPoints, "points the model asked for").isNotEmpty()
		assertThat(
			tool.requestedPoints.drop(1).any { it == REQUIRED_POINT },
			"model used the point name that appeared only in the rejected call's error text; " +
				"actual call sequence: ${tool.requestedPoints}"
		).isTrue()
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
	 * ## Issue #893 iterations 2/3: the follow-up call is dropped, not the error-surfacing claim
	 *
	 * The original `systemPrompt` demanded two chained calls: "First call all_block_occupancies
	 * ..., then, regardless of its result, call request_route ...". Commits 8a289d66/6f258fb5 (see
	 * the KDoc on `a rejected tool argument is corrected on a later call` above for the mechanism)
	 * made [KoogDispatchAgentImpl.buildUserPrompt]'s last line an unconditional "finish with one
	 * short plain-text sentence and no further tool calls" instead of the old "...then use the
	 * tools available to you ... as needed" reminder. Against that, and against this test's queued
	 * train whose built-in line says it "needs approve_train only" — a tool this test's registry
	 * does not even contain — the model stopped calling `all_block_occupancies` at all and jumped
	 * straight to `request_route`: reproduced deterministically across two consecutive live runs
	 * before this fix (`KoogToolAdapter` logged exactly one call, to `request_route`; `failureCount`
	 * stayed 0, failing the `isGreaterThanOrEqualTo(1)` assertion below both times).
	 *
	 * The fix relaxes exactly the follow-up demand — this test's claim was never that a *second*
	 * tool call survives the first one's error, only that the first one's error does not propagate
	 * out of `decideAsync` — and makes the remaining, single call mandatory instead, the same
	 * pattern used for the sibling test above. Stable across 3 consecutive live runs during this
	 * fix (revert-probe: restoring the original two-call `systemPrompt` reproduces `failureCount ==
	 * 0` again, as it did in the pre-fix baseline runs referenced above).
	 *
	 * ## Koog itself, not just `KoogToolAdapter`, already guards against an aborted cycle
	 *
	 * This test's original "Would fail if ..." claim was checked directly with a temporary
	 * revert-probe (not part of the committed test): making [AlwaysFailingTool.execute] `throw
	 * RuntimeException(...)` instead of returning [ToolResult.Error] — the scenario the claim says
	 * should abort the cycle. It did not: `ai.koog.agents.core.tools.Tool.execute` (`Tool.kt:65`,
	 * inside the `ai.koog` dependency, not this codebase) already wraps every tool invocation and
	 * turns *any* thrown exception into a tool-error result, logging
	 * `"Tool with name '...' failed to execute with arguments: ..."` via `GraphAIAgent` and
	 * continuing the cycle regardless of what `KoogToolAdapter` does. So `KoogToolAdapter`'s own
	 * `ToolException.ValidationFailure` marshalling (see its KDoc) is defense-in-depth, not the last
	 * line of defense the original claim described — Koog's own tool-execution node already never
	 * lets a tool exception reach `decideAsync`. What this test can still, and does, discriminate on
	 * is (a) the historical regression above — the failing tool never being invoked at all, so no
	 * error ever reaches the model to surface in the first place — and (b) `decideAsync` throwing
	 * for a reason Koog's tool-execution wrapper does not cover (e.g. a genuine agent-level failure,
	 * or [testTimeoutMillis] firing), which is left uncaught here and would fail this test.
	 */
	@Test
	@Tag("ollama-test")
	fun `a failing tool does not abort the dispatch cycle`() {
		val failureCount = AtomicInteger(0)
		val failingTool = AlwaysFailingTool("all_block_occupancies", failureCount)
		// Present but, after the #893 fix above, no longer required to be called — see this test's
		// KDoc for why the follow-up demand was dropped. Kept so the tool registry still mixes a
		// failing tool with a healthy one, matching the original test shape.
		val requestRouteTool = FakeRequestRouteTool()
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val systemPrompt =
			"You are a railway dispatcher test harness. You must call the all_block_occupancies " +
				"tool at least once this turn before replying, regardless of what it returns — a " +
				"plain-text reply with no tool call at all is not acceptable. Once you have its " +
				"result (or its error), reply with one short sentence."

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
