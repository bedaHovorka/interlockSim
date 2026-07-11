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

/**
 * Service for creating and managing Koog-based dispatch agents (SP1 skeleton, Issue #547).
 *
 * ## Responsibility
 *
 * [AgentService] bootstraps a Koog agent instance with:
 * 1. Domain tools (bridged from [DomainTool] to Koog's tool framework)
 * 2. LLM model configuration (Ollama endpoint, model name, temperature, etc.)
 * 3. System prompt and agent personality
 *
 * The service intentionally does NOT:
 * - Run the agent loop (that's [AgentLoopDriver]'s responsibility, SP0.10)
 * - Persist agent state or conversation history
 * - Handle multi-agent orchestration (future SP2+ feature)
 *
 * ## SP1 phasing
 *
 * - SP1.2 (this file): Service interface skeleton, tool registration pattern
 * - SP1.3 (#548): Koin DI integration, per-context agent bootstrapping
 * - SP1.4 (#549): Perception/actuator port wiring to tool implementations
 * - SP1.5 (#550): Ollama executor and model selection
 * - SP1.6 (#551): Full Koog tool definitions with JSON schemas and agent personality tuning
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
interface AgentService {
	/**
	 * Create and configure a new Koog agent instance for railway dispatch.
	 *
	 * The agent is ready to execute decisions once created; the caller must wire it
	 * into [AgentLoopDriver] for the sense→decide→act loop.
	 *
	 * @param modelName Ollama model identifier (e.g. "mistral", "llama2")
	 * @param tools Domain tools this agent can invoke (e.g. perception/actuator port adapters)
	 * @param systemPrompt Optional system message to seed the agent's personality and
	 *   domain knowledge. Defaults to a reasonable railway dispatch prompt if not provided.
	 *
	 * @return A configured Koog agent, ready for dispatch decisions
	 *
	 * @since Issue #551 (SP1.6 — full implementation); SP1.2 is skeleton only
	 */
	suspend fun createDispatchAgent(
		modelName: String,
		tools: List<DomainTool>,
		systemPrompt: String? = null
	): KoogDispatchAgent
}

/**
 * A Koog-based railway dispatch agent (SP1 skeleton, Issue #547).
 *
 * ## Responsibilities
 *
 * Represents a single agent instance capable of deciding dispatch actions:
 * - Analyzes a [DispatchObservation] (network state, train positions, signals)
 * - Invokes domain tools to query the network or propose actuations
 * - Returns a list of [DispatchDecision]s for the simulation applier
 *
 * ## Lifecycle
 *
 * 1. Created via [AgentService.createDispatchAgent]
 * 2. Passed to [AgentLoopDriver] via constructor injection
 * 3. [AgentLoopDriver] calls [decideAsync] in its DECIDE phase
 * 4. Results are posted to [ActuatorCommandQueue] for sim-thread application
 *
 * ## SP1 phasing
 *
 * - SP1.2 (this file): Agent interface skeleton
 * - SP1.6 (#551): Full Koog integration, LLM decision-making, tool invocation
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
interface KoogDispatchAgent {
	/**
	 * Asynchronously decide on dispatch actions given the current network observation.
	 *
	 * Called from [AgentLoopDriver.runCycle] during the DECIDE phase.
	 * Must be non-blocking and return quickly (agent driver thread, not kDisco).
	 *
	 * @param observation Current network state snapshot and train queue
	 * @return List of dispatch decisions (empty list = no action this cycle)
	 *
	 * @since Issue #551 (SP1.6 — full implementation)
	 */
	suspend fun decideAsync(observation: DispatchObservation): List<DispatchDecision>
}

// Re-export domain types for convenience (defined in :core)
typealias DispatchObservation = cz.vutbr.fit.interlockSim.sim.DispatchObservation
typealias DispatchDecision = cz.vutbr.fit.interlockSim.sim.DispatchDecision
