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
 * Base interface for domain tools exposed to Koog agents (SP1 skeleton, Issue #547).
 *
 * A domain tool represents a single capability that an LLM-driven or algorithmic agent
 * can invoke to perceive or actuate the railway network. Tools are bridged to Koog's
 * tool framework via tool definitions (SP1.6, Issue #551).
 *
 * ## Tool categories
 *
 * - **Perception tools** - Read-only queries on [NetworkPerceptionPort] (e.g. signal
 *   state, block occupancy, train positions). Mapped to Koog tools in SP1.6.
 * - **Actuator tools** - Commands posted to [NetworkActuatorPort] (e.g. set signal,
 *   reserve path). Mapped to Koog tools in SP1.6.
 *
 * ## SP1 phasing
 *
 * - SP1.2 (this file): Tool interface skeleton, tool adapter pattern
 * - SP1.3 (#548): Wire perception/actuator port tools via Koin
 * - SP1.6 (#551): Implement full Koog tool definitions with JSON schemas
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
interface DomainTool {
	/**
	 * Human-readable name of this tool (e.g. "signal_aspect").
	 *
	 * Used in Koog tool definitions and logging.
	 */
	val name: String

	/**
	 * Human-readable description of what this tool does.
	 *
	 * Used in Koog tool definitions to help LLMs understand when to invoke the tool.
	 */
	val description: String

	/**
	 * Execute this tool with the given arguments.
	 *
	 * Must be a pure function or deterministic operation that never:
	 * - Mutates the simulation state on the kDisco thread
	 * - Blocks for extended periods (called from the agent driver thread)
	 *
	 * @param args Tool arguments as a map of parameter name → value
	 * @return Tool result (typically a JSON-serializable object or string)
	 *
	 * @throws IllegalArgumentException if arguments are invalid
	 * @throws RuntimeException for other tool-specific errors
	 */
	suspend fun execute(args: Map<String, Any?>): Any?
}
