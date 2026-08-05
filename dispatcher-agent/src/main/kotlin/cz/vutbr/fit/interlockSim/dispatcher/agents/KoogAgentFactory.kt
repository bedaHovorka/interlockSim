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
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelPrewarmer
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Factory for creating per-context Koog dispatch agents (SP1.3 skeleton, SP1.4-updated, Issue #548/#549;
 * rewired to [SinkHolder] four-tool surface in SP2c.6, Issue #829).
 *
 * ## SP2c.6 tool surface change (Issue #829)
 *
 * [createAgent] now builds a [SinkHolder] initialized with a queue-posting wrapper that converts
 * every [DispatchAction] emitted by the four actuator tools into a [cz.vutbr.fit.interlockSim.sim.DispatchDecision]
 * and posts it to the [commandQueue]. The agent then receives exactly four tools from
 * [ToolGroupRegistry.assembleAllTools]: `approve_train`, `request_route`, `cancel_route`, `no_op`.
 * No perception tools and no dispatch-loop sensor tools are bundled in for the LLM; they are
 * assembled separately for other uses if needed.
 *
 * @property toolRegistry Tool group registry (singleton, injected into scope)
 * @property ollamaConfig Ollama executor config (singleton, global model/endpoint)
 * @property agentService Agent creation service (singleton, handles Koog wiring)
 * @property perceptionPort Live sensor port for network perception (scoped per context, SP1.4).
 *   Wrapped in a snapshot projection before topology is read (SP1.7).
 * @property commandQueue Command queue for fire-and-forget actuator commands (scoped per
 *   context, SP1.7). Receives converted [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s from
 *   the [sinkHolder] queue-posting wrapper in SP2c.6.
 * @property dispatchLoopSensorPort Dispatch-loop sensor port for this context. Retained for
 *   topology reads and future use; dispatch-loop sensor tools (`queued_trains`/`block_inputs`)
 *   are NOT added to the LLM's tool surface in SP2c.6.
 * @property sinkHolder Per-context shared [SinkHolder] for the four actuator tools (SP2c.6, #829).
 *   Holds the queue-posting wrapper installed here so every actuator tool's `emit` posts its
 *   converted [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to [commandQueue]; the same
 *   instance is read by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] to
 *   detect via the per-cycle emission counter whether the LLM acted via tools (and therefore the
 *   rule-based fallback must not double-dispatch).
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549), SP1.7 (#774), SP2c.6 (#829)
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService,
	private val perceptionPort: NetworkPerceptionPort,
	private val commandQueue: ActuatorCommandQueue,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort,
	private val sinkHolder: SinkHolder
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		// Not `const val`: interpolates RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS so the
		// concrete cap the LLM reasons over never drifts from the non-LLM dispatcher's own policy.
		private val DEFAULT_SYSTEM_PROMPT =
			"You are a railway dispatcher coordinating train movements. " +
				"You have four actuator tools: approve_train, request_route, cancel_route, no_op. " +
				"Always prioritize safety. " +
				"All entry/exit points, signals, switches, and blocks you may reference are listed " +
				"by exact name in the STATION TOPOLOGY section below. Never invent, abbreviate, or " +
				"guess a name — if you need a name you don't see there, do not call the tool. " +
				"The only actuator tools available are approve_train, request_route, cancel_route, " +
				"and no_op — there is no tool to set a signal aspect or switch position directly; " +
				"signals and switches change only as a side effect of request_route/cancel_route. " +
				"request_route's fromEndpointName/toEndpointName arguments accept only InOut or Signal " +
				"names — never a Block ID from the Blocks list (Block IDs are for block_occupancy / " +
				"all_block_occupancies only, not for request_route). " +
				"request_route only reserves interlocking resources for a train — it does not let the " +
				"train depart; a queued train stays parked, holding its reservation indefinitely, until " +
				"you separately call approve_train for it. " +
				"On every turn, admission comes first, exactly like a real interlocking's admission " +
				"control: check queued_trains and all_train_positions; if there are queued (unapproved) " +
				"trains and fewer than ${RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS} trains are " +
				"currently active, call approve_train for the oldest queued trains first, up to " +
				"${RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS} total active, before doing anything " +
				"else. Call approve_train for every queued train you intend to dispatch, in addition to " +
				"requesting its route. When there is nothing to do, call no_op with a brief reason."
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
	 * @param context Current simulation context (for static topology extraction).
	 * @return A configured Koog dispatch agent ready for dispatch decisions.
	 *
	 * @since Issue #548 (SP1.3); SP2c.6 (#829) introduces [SinkHolder] 4-tool surface
	 */
	suspend fun createAgent(context: DefaultSimulationContext): KoogDispatchAgent {
		// Issue #815 (SP2b.9 warm-up follow-up): preload the model before the dispatch timeout window.
		OllamaModelPrewarmer.warmUp(ollamaConfig)

		logger.debug {
			"KoogAgentFactory.createAgent: context=${context.javaClass.simpleName}, " +
				"model=${ollamaConfig.modelName} (SP2c.6 SinkHolder 4-tool surface)"
		}

		// SP1.7 (Issue #774): wrap the live perception port in an off-thread-safe snapshot
		// projection so the static topology can be read here at construction time.
		val projection = SnapshotProjectionNetworkPerceptionPort { perceptionPort.snapshot() }

		// Static topology never changes during a run — read once at agent construction.
		// The InOut/Signal names double as the valid-endpoint set request_route validates against.
		val topology = StationTopologySerializer.describe(context)
		val validEndpointNames: Set<String> = (topology.inOuts + topology.signals.map { it.name }).toSet()

		// SP2c.6 (#829): install the queue-posting wrapper on the per-context SinkHolder.
		// Every DispatchAction emitted by an actuator tool is converted to a DispatchDecision
		// and posted to commandQueue (fire-and-forget). The SinkHolder is shared by all four tools
		// and with KoogAgentPlanAdapter, which reads its per-cycle emission counter.
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

		// Assemble the four-tool actuator surface for this context. perceptionPort is passed
		// through so CancelRouteTool can pre-validate trainId in-turn (Issue #847 cleanup pass) —
		// reuses the existing off-thread-safe port, not a new LLM-facing tool.
		val tools = toolRegistry.assembleAllTools(validEndpointNames, sinkHolder, perceptionPort)
		ActuatorToolSurface.assertExactly(tools)

		// SP2b.8 (Issue #695): serialize static topology into the system prompt once.
		val topologyPrompt = StationTopologySerializer.toPromptText(topology)
		val systemPrompt = "$DEFAULT_SYSTEM_PROMPT\n\n$topologyPrompt"

		val agent =
			agentService.createDispatchAgent(
				modelName = ollamaConfig.modelName,
				tools = tools,
				systemPrompt = systemPrompt
			)

		logger.debug { "KoogAgentFactory: created agent with ${tools.size} tools (SP2c.6 4-tool surface)" }
		return agent
	}
}
