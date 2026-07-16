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
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
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
 *             perceptionPort = get(),         // NetworkPerceptionPort (scoped, SP1.4)
 *             actuatorPort = get()            // NetworkActuatorPort (scoped, SP1.4)
 *         )
 *     }
 * }
 * ```
 *
 * ## Design rationale
 *
 * **Per-context scoping** is required because:
 * 1. Agents are instantiated per simulation run (one per context)
 * 2. Tools must access context-specific [NetworkPerceptionPort] / [NetworkActuatorPort]
 * 3. Agent state (conversation history in SP1.6+) is context-specific
 * 4. Multiple simultaneous simulations may run (e.g. in tests), each with its own agent
 *
 * **Koin `scoped<DefaultSimulationContext>`** implements this pattern: objects created
 * in this scope are keyed by context instance and garbage-collected when the context dies.
 *
 * @property toolRegistry Tool group registry (singleton, injected into scope)
 * @property ollamaConfig Ollama executor config (singleton, global model/endpoint)
 * @property agentService Agent creation service (singleton, handles Koog wiring in SP1.6)
 * @property perceptionPort Sensor port for network perception (scoped per context, SP1.4)
 * @property actuatorPort Actuator port for network commands (scoped per context, SP1.4)
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549) adds port injection
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService,
	private val perceptionPort: NetworkPerceptionPort,
	private val actuatorPort: NetworkActuatorPort
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		private const val DEFAULT_SYSTEM_PROMPT =
			"You are a railway dispatcher coordinating train movements. " +
				"You can use perception tools to query network state and actuator tools " +
				"to control signals and routes. Always prioritize safety."
	}

	/**
	 * Create a Koog dispatch agent for the given simulation context.
	 *
	 * Wires:
	 * 1. Tools from [ToolGroupRegistry] with context-scoped ports (SP1.4)
	 * 2. Model config from [OllamaExecutorConfig] (global, copied to agent)
	 * 3. System prompt (default railway dispatcher personality)
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
				"model=${ollamaConfig.modelName} (SP1.4 with ports)"
		}

		// Assemble tools for this context using injected ports (SP1.4)
		val tools = toolRegistry.assembleAllTools(perceptionPort, actuatorPort)

		// Create agent via service (SP1.6 will add Koog wiring)
		val agent =
			agentService.createDispatchAgent(
				modelName = ollamaConfig.modelName,
				tools = tools,
				systemPrompt = DEFAULT_SYSTEM_PROMPT
			)

		logger.debug { "KoogAgentFactory: created agent with ${tools.size} tools" }
		return agent
	}
}
