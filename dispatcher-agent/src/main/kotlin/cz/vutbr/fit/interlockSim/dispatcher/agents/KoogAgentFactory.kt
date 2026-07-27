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
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Factory for creating per-context Koog dispatch agents (SP1.3 skeleton, SP1.4-updated, Issue #548/#549).
 *
 * ## Responsibility
 *
 * Creates one [KoogDispatchAgent] per [DefaultSimulationContext], wiring:
 * - Tool assembly via [ToolGroupRegistry] (perception + actuator tools for this context, SP1.4)
 * - Sensor/actuator port implementations (SP1.4 — [NetworkPerceptionPort] / [NetworkActuatorPort])
 * - Model configuration from [OllamaExecutorConfig] (global singleton, copied to each agent)
 * - System prompt for agent personality
 *
 * This factory is a **per-context builder**, not a singleton. It exists to:
 * 1. Encapsulate agent creation logic
 * 2. Inject context-scoped dependencies (ports from SP1.4, tools from SP1.4+)
 * 3. Separate concerns: factory logic vs Koin module configuration
 *
 * ## SP1 phasing
 *
 * - SP1.3 (skeleton): Factory skeleton, receives tools/config, creates agent
 * - SP1.4 (#549): Port implementations injected; tool assembly signatures updated
 * - SP1.5 (#550): Ollama executor backend wired into agent
 * - SP1.6 (#551): Full Koog tool definitions and LLM decision-making
 *
 * ## Koin scope usage
 *
 * This factory is called **per context** during Koin scope creation:
 * ```kotlin
 * scope<DefaultSimulationContext> {
 *     scoped<KoogAgentFactory> {
 *         KoogAgentFactory(
 *             toolRegistry = get(),           // ToolGroupRegistry (singleton)
 *             ollamaConfig = get(),           // OllamaExecutorConfig (singleton)
 *             agentService = get(),           // AgentService (singleton)
 *             perceptionPort = get(),         // NetworkPerceptionPort (scoped, SP1.4 — live port)
 *             commandQueue = get()            // ActuatorCommandQueue (scoped, SP1.7)
 *         )
 *     }
 * }
 * ```
 *
 * ## Design rationale
 *
 * **Per-context scoping** is required because:
 * 1. Agents are instantiated per simulation run (one per context)
 * 2. Tools must access context-specific [NetworkPerceptionPort] / [ActuatorCommandQueue]
 * 3. Agent state (conversation history in SP1.6+) is context-specific
 * 4. Multiple simultaneous simulations may run (e.g. in tests), each with its own agent
 *
 * **Koin `scoped<DefaultSimulationContext>`** implements this pattern: objects created
 * in this scope are keyed by context instance and garbage-collected when the context dies.
 *
 * ## SP1.7 threading contract (Issue #774)
 *
 * The [perceptionPort] injected here is the **live**
 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] (kDisco-thread-only for its
 * single-query / `allXxx()` methods). [createAgent] wraps it in a
 * [SnapshotProjectionNetworkPerceptionPort] before handing it to the perception tools, so the
 * tools read off-thread from the published [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot].
 * The live port is also retained by the wiring layer for the sim-thread `captureSnapshot` hook.
 * Actuator tools receive the [commandQueue] and post [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s
 * (fire-and-forget) instead of touching an actuator port directly.
 *
 * @property toolRegistry Tool group registry (singleton, injected into scope)
 * @property ollamaConfig Ollama executor config (singleton, global model/endpoint)
 * @property agentService Agent creation service (singleton, handles Koog wiring in SP1.6)
 * @property perceptionPort Live sensor port for network perception (scoped per context, SP1.4).
 *   Wrapped in a snapshot projection before being passed to tools (SP1.7).
 * @property commandQueue Command queue for fire-and-forget actuator commands (scoped per
 *   context, SP1.7).
 * @property dispatchLoopSensorPort Dispatch-loop sensor port for this context, backing the
 *   `queued_trains`/`block_inputs` tools — without these (and `approve_train`) the LLM has no
 *   way to admit a queued train (Goal 10 dispatcher-cannot-approve-trains fix).
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549) adds port injection; SP1.7 (#774) switches
 *   actuators to the command queue and perception to the snapshot projection
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService,
	private val perceptionPort: NetworkPerceptionPort,
	private val commandQueue: ActuatorCommandQueue,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		// Not `const val`: interpolates RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS so the
		// concrete cap the LLM reasons over never drifts from the non-LLM dispatcher's own policy.
		private val DEFAULT_SYSTEM_PROMPT =
			"You are a railway dispatcher coordinating train movements. " +
				"You can use perception tools to query network state and actuator tools " +
				"to control signals and routes. Always prioritize safety. " +
				"All entry/exit points, signals, switches, and blocks you may reference are listed " +
				"by exact name in the STATION TOPOLOGY section below. Never invent, abbreviate, or " +
				"guess a name — if you need a name you don't see there, query a perception tool " +
				"instead of guessing. " +
				"The only actuator tools available are request_route, release_route, and approve_train " +
				"— there is no tool to set a signal aspect or switch position directly; signals and " +
				"switches change only as a side effect of request_route/release_route. " +
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
				"requesting its route."
	}

	/**
	 * Create a Koog dispatch agent for the given simulation context.
	 *
	 * Wires:
	 * 1. Tools from [ToolGroupRegistry] with context-scoped ports (SP1.4)
	 * 2. Model config from [OllamaExecutorConfig] (global, copied to agent)
	 * 3. System prompt (default railway dispatcher personality + static station topology, SP2b.8)
	 *
	 * ### Current state (SP1.4)
	 *
	 * Returns a [KoogDispatchAgentImpl] with context-scoped perception/actuator ports.
	 * Tool implementations are deferred to SP1.6, but port infrastructure is ready.
	 *
	 * ### Future (SP1.6)
	 *
	 * Will construct full Koog agent with:
	 * - Koog tool definitions (JSON schemas from ports)
	 * - LLM model executor
	 * - Tool execution hooks
	 *
	 * @param context Current simulation context (for context-scoped tool/port access)
	 * @return A configured Koog agent, ready for dispatch decisions
	 *
	 * @since Issue #548 (SP1.3 — skeleton); SP1.4 (#549) adds port infrastructure
	 */
	suspend fun createAgent(context: DefaultSimulationContext): KoogDispatchAgent {
		logger.debug {
			"KoogAgentFactory.createAgent: context=${context.javaClass.simpleName}, " +
				"model=${ollamaConfig.modelName} (SP1.7 projection + queue)"
		}

		// SP1.7 (Issue #774): wrap the live perception port in an off-thread-safe snapshot
		// projection so perception tools can be invoked from the agent driver thread without
		// touching mutable simulation state. snapshot() is @Volatile-backed and off-thread-safe.
		val projection = SnapshotProjectionNetworkPerceptionPort { perceptionPort.snapshot() }

		// Static topology never changes during a run, so it is safe to read off the sim thread
		// here at agent construction. The InOut/Signal names double as the valid-endpoint set
		// request_route validates against (agent-architect review finding, Goal 10): the LLM can
		// hallucinate a plausible-looking endpoint name instead of one from this topology, so
		// RequestRouteTool rejects unknown names synchronously (ToolResult.Error) rather than
		// queuing a decision that fails later, invisibly, on the kDisco thread.
		val topology = StationTopologySerializer.describe(context)
		val validEndpointNames: Set<String> = (topology.inOuts + topology.signals.map { it.name }).toSet()

		// Assemble tools for this context: perception reads via the projection, actuators post
		// fire-and-forget decisions to the queue (applied on the kDisco thread by the applier).
		// Dispatch-loop tools (queued_trains/block_inputs/approve_train) are added alongside the
		// general-purpose tools — without them the LLM has no way to admit a queued train onto
		// the network (Goal 10 dispatcher-cannot-approve-trains fix).
		val tools =
			toolRegistry.assembleAllTools(projection, commandQueue, validEndpointNames) +
				toolRegistry.assembleDispatchLoopTools(dispatchLoopSensorPort, commandQueue) {
					projection.allTrainPositions().size
				}

		// SP2b.8 (Issue #695): serialize the controlled area's *static* topology (blocks, switches,
		// signals, valid routes — SP3.2 compact IDs) and load it into the system prompt ONCE, here
		// at agent construction. Per-turn perception tools then report only dynamic state and
		// reference topology purely by ID, instead of re-transmitting the structure every turn.
		val topologyPrompt = StationTopologySerializer.toPromptText(topology)
		val systemPrompt = "$DEFAULT_SYSTEM_PROMPT\n\n$topologyPrompt"

		// Create agent via service (SP1.6 will add Koog wiring)
		val agent =
			agentService.createDispatchAgent(
				modelName = ollamaConfig.modelName,
				tools = tools,
				systemPrompt = systemPrompt
			)

		logger.debug { "KoogAgentFactory: created agent with ${tools.size} tools" }
		return agent
	}
}
