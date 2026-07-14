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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Factory for creating per-context Koog dispatch agents (SP1.3 skeleton, Issue #548).
 *
 * ## Responsibility
 *
 * Creates one [KoogDispatchAgent] per [DefaultSimulationContext], wiring:
 * - Tool assembly via [ToolGroupRegistry] (perception + actuator tools for this context)
 * - Model configuration from [OllamaExecutorConfig] (global singleton, copied to each agent)
 * - System prompt for agent personality
 *
 * This factory is a **per-context builder**, not a singleton. It exists to:
 * 1. Encapsulate agent creation logic
 * 2. Inject context-scoped dependencies (tools, ports)
 * 3. Separate concerns: factory logic vs Koin module configuration
 *
 * ## SP1 phasing
 *
 * - SP1.3 (this class): Factory skeleton, receives tools/config, creates agent
 * - SP1.4 (#549): Tool implementations fed into factory
 * - SP1.5 (#550): Ollama executor backend wired into agent
 * - SP1.6 (#551): Full Koog tool definitions and LLM decision-making
 *
 * ## Koin scope usage
 *
 * This factory is called **per context** during Koin scope creation:
 * ```kotlin
 * scope<DefaultSimulationContext> {
 *     scoped<KoogDispatchAgent> {
 *         val factory = KoogAgentFactory(
 *             toolRegistry = get(),           // ToolGroupRegistry (singleton)
 *             ollamaConfig = get(),           // OllamaExecutorConfig (singleton)
 *             agentService = get()            // AgentService (singleton)
 *         )
 *         factory.createAgent(get())          // get() = DefaultSimulationContext (scoped)
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
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
class KoogAgentFactory(
	private val toolRegistry: ToolGroupRegistry,
	private val ollamaConfig: OllamaExecutorConfig,
	private val agentService: AgentService
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
	 * 1. Tools from [ToolGroupRegistry] (scoped to this context)
	 * 2. Model config from [OllamaExecutorConfig] (global, copied to agent)
	 * 3. System prompt (default railway dispatcher personality)
	 *
	 * ### Current state (SP1.3)
	 *
	 * Returns a [KoogDispatchAgentImpl] with empty tool list (skeleton).
	 * SP1.4 populates tools, SP1.6 wires them into Koog LLM.
	 *
	 * ### Future (SP1.6)
	 *
	 * Will construct full Koog agent with:
	 * - Koog tool definitions (JSON schemas)
	 * - LLM model executor
	 * - Tool execution hooks
	 *
	 * @param context Current simulation context (for context-scoped tool/port access)
	 * @return A configured Koog agent, ready for dispatch decisions
	 *
	 * @since Issue #548 (SP1.3 — skeleton); full wiring in Issue #551 (SP1.6)
	 */
	suspend fun createAgent(context: DefaultSimulationContext): KoogDispatchAgent {
		logger.debug {
			"KoogAgentFactory.createAgent: context=${context.javaClass.simpleName}, " +
				"model=${ollamaConfig.modelName} (SP1.3 skeleton)"
		}

		// Assemble tools for this context
		val tools = toolRegistry.assembleAllTools()

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
