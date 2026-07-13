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
 * - SP1.2 (this file): Tool interface skeleton, parameter descriptor, tool adapter pattern
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
	 * Declared parameters of this tool.
	 *
	 * SP1.6 builds Koog's [ToolDescriptor] from this list, splitting it into
	 * `requiredParameters` / `optionalParameters` by [DomainToolParameter.required].
	 * Koog derives the JSON schema the LLM sees from that descriptor, so the
	 * parameter list must be complete and accurate for tool-calling to work.
	 *
	 * Empty for tools that take no arguments.
	 *
	 * @since Issue #547 (SP1.2 — skeleton); full Koog tool definitions in Issue #551 (SP1.6)
	 */
	val parameters: List<DomainToolParameter>

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

/**
 * Koog-agnostic description of one [DomainTool] parameter (SP1 skeleton, Issue #547).
 *
 * SP1.6 maps each [DomainToolParameter] to Koog's
 * `ai.koog.agents.core.tools.ToolParameterDescriptor` and [DomainToolParameterType]
 * to Koog's `ToolParameterType`, building the `ToolDescriptor` the LLM sees. This
 * type intentionally has no `ai.koog.*` dependency so the domain seam stays
 * decoupled from the agent framework.
 *
 * @property name Parameter name as the LLM must emit it in a tool call.
 * @property description Human-readable meaning, shown to the LLM in the tool schema.
 * @property type Value type (see [DomainToolParameterType]).
 * @property required Whether the parameter must be present (required vs optional in Koog).
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
data class DomainToolParameter(
	val name: String,
	val description: String,
	val type: DomainToolParameterType,
	val required: Boolean = true
)

/**
 * Koog-agnostic value types for [DomainToolParameter] (SP1 skeleton, Issue #547).
 *
 * A subset of Koog's `ToolParameterType` sealed hierarchy sufficient for the SP1.4
 * perception/actuator tools (signal-aspect enum, block-occupancy string/boolean,
 * train-position float, …). `Object` / `AnyOf` are omitted for now and can be added
 * in SP1.6 (#551) if a real tool needs structured or union parameters.
 *
 * The subtype names mirror Koog's `ToolParameterType` (`String`, `Integer`, …,
 * `List`, `Enum`); they shadow the stdlib `kotlin.String` / `kotlin.collections.List`
 * names *inside this sealed interface body*, so the internal `Enum.entries` reference
 * fully qualifies those stdlib types. Outside this body the nested names are not in
 * scope, so callers use the stdlib types normally.
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
sealed interface DomainToolParameterType {
	data object String : DomainToolParameterType

	data object Integer : DomainToolParameterType

	data object Float : DomainToolParameterType

	data object Boolean : DomainToolParameterType

	data class Enum(
		val entries: kotlin.collections.List<kotlin.String>
	) : DomainToolParameterType

	data class List(
		val itemsType: DomainToolParameterType
	) : DomainToolParameterType
}
