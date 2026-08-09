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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelPrewarmer
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.dispatcher.planner.AuthoredAction
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Factory for creating per-context Koog dispatch agents.
 *
 * ## Tool surface
 *
 * [createAgent] builds a [SinkHolder] initialized with a queue-posting wrapper that converts
 * every [DispatchAction] emitted by the four actuator tools into a [cz.vutbr.fit.interlockSim.sim.DispatchDecision]
 * and posts it to the [commandQueue]. The agent receives exactly four tools from
 * [ToolGroupRegistry.assembleAllTools]: `approve_train`, `request_route`, `cancel_route`, `no_op`.
 * No perception tools and no dispatch-loop sensor tools are bundled in for the LLM; they are
 * assembled separately for other uses if needed.
 *
 * @property toolRegistry Tool group registry (singleton, injected into scope)
 * @property ollamaConfig Ollama executor config (singleton, global model/endpoint)
 * @property agentService Agent creation service (singleton, handles Koog wiring)
 * @property perceptionPort Live sensor port for network perception (scoped per context).
 *   Wrapped in a snapshot projection before topology is read.
 * @property commandQueue Command queue for fire-and-forget actuator commands (scoped per
 *   context). Receives converted [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s from
 *   the [sinkHolder] queue-posting wrapper.
 * @property dispatchLoopSensorPort Dispatch-loop sensor port for this context. Retained for
 *   topology reads and future use; dispatch-loop sensor tools (`queued_trains`/`block_inputs`)
 *   are NOT added to the LLM's tool surface.
 * @property sinkHolder Per-context shared [SinkHolder] for the four actuator tools. Holds the
 *   queue-posting wrapper installed here so every actuator tool's `emit` posts its converted
 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to [commandQueue]; the same instance is
 *   read by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] to detect via
 *   the per-cycle emission counter whether the LLM acted via tools (and therefore the
 *   rule-based fallback must not double-dispatch).
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService,
	private val perceptionPort: NetworkPerceptionPort,
	private val commandQueue: ActuatorCommandQueue,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort,
	private val sinkHolder: SinkHolder,
	/**
	 * Optional per-run recorder receiving every coded in-turn tool rejection.
	 *
	 * The live path rejects arguments at the tool boundary and nowhere else — `ActionValidator`
	 * is reached only from the test-only `DispatchTickLoop` — so without this the per-run JSON's
	 * `rejectionsByCode` would be structurally always empty.
	 *
	 * Resolved **lazily, per rejection** rather than captured at construction. `ExampleRegistry`
	 * overrides the scoped recorder with the correct arm *after* it has already resolved this
	 * factory (it needs the factory to build the planner first), so a field captured in the
	 * constructor would hold the module's default rule-based recorder — a different instance from
	 * the one the run actually persists, and rejections would be counted into an object nobody ever
	 * writes. A provider makes the wiring order irrelevant.
	 *
	 * Returns `null` for agents built outside a run (tests, tooling); the tool surface is unaffected
	 * either way.
	 */
	private val runRecorderProvider: () -> DispatcherRunRecorder? = { null },
	/**
	 * Bounded per-cycle history handed to the built agent and written by
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] after each cycle.
	 * Shared per context exactly like [sinkHolder], and for the same reason: the writer and the
	 * reader are two different objects on the same driver thread.
	 *
	 * Defaults to a disabled history so agents built outside a run behave as before this history
	 * existed.
	 */
	private val cycleHistory: CycleHistory = CycleHistory(capacity = 0),
	/**
	 * Optional per-context feed of previously-applied outcomes, threaded straight through to
	 * [AgentService.createDispatchAgent] exactly like [cycleHistory] above (same per-context
	 * scoped-sharing rationale). `null` by default so agents built outside a run (tests, tooling)
	 * behave as before this feed existed.
	 */
	private val outcomeFeed: AppliedOutcomeFeed? = null
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Builds the DISPATCHER system prompt. Wording is subject to agent-architect review;
		 * only the stable phrases [KoogAgentFactoryTest] asserts on are load-bearing.
		 *
		 * Not a precomputed constant: [maxActions] is read per call from the [SinkHolder] instance
		 * [createAgent] was constructed with, so the stated budget can never drift from what
		 * [SinkHolder.tryEmit] actually enforces — [SinkHolder] itself is built from
		 * [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.maxActionsPerTick] by
		 * [cz.vutbr.fit.interlockSim.dispatcher.di.DispatcherAgentModule]'s existing scoped
		 * wiring, so this threads the real per-run value with no new DI path. `cap` (the
		 * concurrent-train admission ceiling) is a distinct constant,
		 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS] — the non-LLM dispatcher's own
		 * policy — kept exactly as before so the LLM and rule-based arms never disagree about
		 * station capacity.
		 *
		 * ## No-menu compliance
		 *
		 * The procedure and rules below are dash-bulleted, never numbered (`^\s*\d+[.)]\s` is
		 * forbidden), and the text avoids "option"/"optional"/"choose one"/"select" everywhere —
		 * [LivePromptNoMenuTest] locks this against regression on the real, assembled prompt.
		 *
		 * ## Queued trains carry no destination clause here
		 *
		 * [KoogDispatchAgentImpl.renderQueuedTrainLine] deliberately renders a queued train as
		 * approve-only, naming no topology endpoint at all — the admission step below must not
		 * imply otherwise, so it never mentions a queued train's exit.
		 */
		private fun buildSystemPrompt(maxActions: Int): String {
			val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
			return buildString {
				appendLine(
					"You are a railway dispatcher. Every call you receive is one tick: read the " +
						"cycle message, decide, act through your tools, and stop — nothing carries " +
						"forward except what you actually called a tool for."
				)
				appendLine(
					"The only actuator tools available are approve_train, request_route, " +
						"cancel_route, and no_op — there is no tool to set a signal aspect or " +
						"switch position directly; signals and switches change only as a side " +
						"effect of request_route/cancel_route. Always prioritize safety."
				)
				appendLine(
					"All entry/exit points, signals, switches, and blocks you may reference are " +
						"listed by exact name in the STATION TOPOLOGY section below. Never invent, " +
						"abbreviate, or guess a name — if you need a name you don't see there, do " +
						"not call the tool. request_route's fromEndpointName/toEndpointName " +
						"arguments accept InOut and Signal names — never a Block ID from the Blocks " +
						"list."
				)
				appendLine(
					"You have no tool for querying state: every train id, count, and position you " +
						"may act on is already written in the cycle message you are given. Only " +
						"ever pass a train id that appears there verbatim — never one taken from an " +
						"example, and never one you inferred."
				)
				appendLine(
					"At most $maxActions actions besides no_op are accepted this tick; no_op never " +
						"counts against that budget."
				)
				appendLine("On every tick, follow these steps in order:")
				appendLine(
					"- If the cycle message has an OUTCOMES OF YOUR PREVIOUS ACTIONS section, read " +
						"it first: it names what your last calls actually did."
				)
				appendLine(
					"- admission comes first: while there are queued (unapproved) trains and " +
						"fewer than $cap trains are currently active, call approve_train for queued " +
						"trains in the order listed, up to $cap total active. Queued trains take " +
						"approve_train only, nothing else."
				)
				appendLine(
					"- For each active train whose line names a NEXT SECTION to reserve, call " +
						"request_route once for it, copying the from/to names from that NEXT " +
						"SECTION clause exactly."
				)
				appendLine(
					"- a train with no NEXT SECTION line gets no route request this tick — its " +
						"line already says why (route already set, or nothing reservable yet); " +
						"leave it be."
				)
				appendLine(
					"- Call cancel_route only for a train whose reservation is no longer needed " +
						"and that is not currently standing on it. A REFUSED request in OUTCOMES " +
						"reserved nothing and needs no cancel_route."
				)
				appendLine("- Otherwise, call no_op with a brief reason.")
				appendNonNegotiableRules(maxActions)
			}.trimEnd('\n')
		}

		/**
		 * Appends the "Rules that never bend:" section to [buildSystemPrompt]'s [StringBuilder];
		 * factored out to keep [buildSystemPrompt] within detekt's `LongMethod` budget, wording
		 * unchanged from when it lived inline.
		 */
		private fun StringBuilder.appendNonNegotiableRules(maxActions: Int) {
			appendLine("Rules that never bend:")
			appendLine(
				"- Reservation is not movement: request_route only reserves interlocking " +
					"resources for a train that is already active — it never admits a queued " +
					"train; only approve_train does that."
			)
			appendLine(
				"- Copy every name character-for-character from STATION TOPOLOGY or a NEXT " +
					"SECTION line — never a Block ID."
			)
			appendLine(
				"- Reserving a section that is not directly in front of a train does not " +
					"release anything that train already holds; it only locks that track " +
					"against other trains."
			)
			appendLine(
				"- This cycle's message always supersedes anything recorded earlier: where " +
					"they disagree, trust this cycle."
			)
			appendLine(
				"- Never call approve_train for a train already listed as active — " +
					"approve_train applies only to a queued train."
			)
			appendLine(
				"- Once the per-tick action budget is spent, end your turn: further tool " +
					"calls this tick are refused."
			)
			appendLine(
				"- When you have taken the actions this tick needs — never more than $maxActions " +
					"— end the tick: reply with one short plain-text sentence and make no further " +
					"tool calls. When no action is needed this tick, call no_op once and then reply " +
					"the same way. Only a plain-text reply ends the tick."
			)
			appendLine(
				"- If a tool call for a train is rejected twice in one tick, stop acting on that " +
					"train and move on or reply."
			)
			appendLine(
				"no_op is a correct and frequent answer, not a failure to act — most ticks " +
					"have nothing new to do. Repeating an action already in force is refused, " +
					"wastes the tick, and tells the next tick nothing new."
			)
		}
	}

	/**
	 * Create a Koog dispatch agent for the given simulation context.
	 *
	 * Assembles the four-tool actuator surface using a [SinkHolder] backed by a queue-posting
	 * wrapper. The wrapper converts each emitted [DispatchAction] to a
	 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision] via [DispatchTickLoop.toDispatchDecisions]
	 * and posts it to [commandQueue] (fire-and-forget; applied on the kDisco thread by
	 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]).
	 *
	 * Model warm-up ([OllamaModelPrewarmer.warmUp]) runs concurrently with the topology/prompt/tool
	 * assembly below rather than before it: the two are independent (warm-up is network I/O against
	 * Ollama, assembly is local CPU work), and only the final [AgentService.createDispatchAgent] call
	 * needs both finished, so overlapping them shortens the time this suspend function takes overall
	 * without changing what it waits for before returning.
	 *
	 * @param context Current simulation context (for static topology extraction).
	 * @return A configured Koog dispatch agent ready for dispatch decisions.
	 */
	suspend fun createAgent(context: DefaultSimulationContext): KoogDispatchAgent =
		coroutineScope {
			// Preload the model before the dispatch timeout window; started here, awaited below.
			val warmUp = async { OllamaModelPrewarmer.warmUp(ollamaConfig) }

			logger.debug {
				"KoogAgentFactory.createAgent: context=${context.javaClass.simpleName}, " +
					"model=${ollamaConfig.modelName} (SP2c.6 SinkHolder 4-tool surface)"
			}

			// Wrap the live perception port in an off-thread-safe snapshot projection so the static
			// topology can be read here at construction time.
			val projection = SnapshotProjectionNetworkPerceptionPort { perceptionPort.snapshot() }

			// Static topology never changes during a run — read once at agent construction.
			// The InOut/Signal names double as the valid-endpoint set request_route validates against.
			val topology = StationTopologySerializer.describe(context)
			val validEndpointNames: Set<String> = (topology.inOuts + topology.signals.map { it.name }).toSet()

			// Install the queue-posting wrapper on the per-context SinkHolder. Every DispatchAction
			// emitted by an actuator tool is converted to a DispatchDecision and posted to
			// commandQueue (fire-and-forget). The SinkHolder is shared by all four tools and with
			// KoogAgentPlanAdapter, which reads its per-cycle emission counter.
			sinkHolder.current =
				EmittedActionSink { action ->
					val decisions: List<DispatchDecision> =
						when (action) {
							is DispatchAction.ApproveTrain ->
								listOf(DispatchDecision.ApproveTrain(trainId = action.trainId))
							is DispatchAction.RequestRoute ->
								listOf(
									DispatchDecision.RequestRoute(
										trainName = action.trainId,
										fromEndpointName = action.fromEndpointName,
										toEndpointName = action.toEndpointName
									)
								)
							is DispatchAction.CancelRoute ->
								listOf(DispatchDecision.ReleaseRoute(trainName = action.trainId))
							is DispatchAction.NoOp -> emptyList()
						}
					commandQueue.postAll(decisions)
				}

			// Assemble the four-tool actuator surface for this context. Both ports are passed through so
			// the actuator tools can pre-validate train ids in-turn: perceptionPort supplies the active
			// trains, dispatchLoopSensorPort the queued ones. Both are existing internal ports —
			// reusing them adds no LLM-facing query tool, and ActuatorToolSurface.assertExactly below
			// still holds the surface at four.
			val tools =
				toolRegistry.assembleAllTools(
					validEndpointNames,
					sinkHolder,
					perceptionPort,
					dispatchLoopSensorPort,
					topology.blocks.map { it.name }.toSet(),
					topology.inOuts.toSet()
				)
			// Assert the surface on the REAL tools, before decoration — the decorator is transparent
			// (it forwards name/description/parameters) but asserting first keeps the four-tool contract
			// checked against what the registry actually built.
			ActuatorToolSurface.assertExactly(tools)
			val instrumentedTools = tools.map { tool -> RejectionRecordingTool(tool, ::recordRejection) }

			// Serialize static topology into the system prompt once.
			val topologyPrompt = StationTopologySerializer.toPromptText(topology)
			// Budget is read from this factory's own sinkHolder, not a hardcoded literal — see
			// buildSystemPrompt's KDoc for why that is safe.
			val systemPrompt = "${buildSystemPrompt(sinkHolder.maxActionsPerTick)}\n\n$topologyPrompt"

			val agent =
				agentService.createDispatchAgent(
					modelName = ollamaConfig.modelName,
					tools = instrumentedTools,
					systemPrompt = systemPrompt,
					cycleHistory = cycleHistory,
					outcomeFeed = outcomeFeed
				)

			// Join the warm-up before returning: the caller's first real dispatch cycle must see a
			// warmed model, exactly as before this method overlapped the two phases.
			warmUp.await()

			logger.debug { "KoogAgentFactory: created agent with ${instrumentedTools.size} tools (SP2c.6 4-tool surface)" }
			agent
		}

	/**
	 * Records one coded in-turn tool rejection on the per-run recorder.
	 *
	 * Phase is [ActionPhase.REJECTED_BY_VALIDATOR] — the action never reached the applier, so it is
	 * a validator-stage rejection in every sense the snapshot distinguishes.
	 *
	 * `tickIndex` is `0`, deliberately **not** `-1`: the recorder treats `-1` as "the correlation
	 * map had no entry" and counts it in `unattributedApplies`, which is a statement about applied
	 * actions. A rejected call was never applied and never correlated, so borrowing that sentinel
	 * would corrupt an unrelated metric.
	 */
	private fun recordRejection(
		toolName: String,
		code: RejectionCode
	) {
		runRecorderProvider()?.onActionOutcome(
			ActionOutcome(
				phase = ActionPhase.REJECTED_BY_VALIDATOR,
				rejection = code,
				applyFailure = null,
				authored =
					AuthoredAction(
						author = ActionAuthor.LLM,
						reason = "tool_rejected",
						decisionKind = toolName,
						tickIndex = 0L
					)
			)
		)
	}
}
