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

import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode

/**
 * Base interface for domain tools exposed to Koog agents.
 *
 * A domain tool represents a single capability that an LLM-driven or algorithmic agent
 * can invoke to perceive or actuate the railway network. Tools are bridged to Koog's
 * tool framework via tool definitions.
 *
 * ## Tool categories
 *
 * - **Perception tools** - Read-only queries on [NetworkPerceptionPort] (e.g. signal
 *   state, block occupancy, train positions). Tools receive the live [NetworkPerceptionPort]
 *   directly and read only its [NetworkPerceptionPort.snapshot] member — the sole
 *   `@Volatile`-backed, off-thread-safe accessor — never its single-query / `allXxx()` methods,
 *   which read mutable state and are kDisco-thread-only.
 * - **Actuator tools** - Commands posted (fire-and-forget) to
 *   [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] as
 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s (e.g. set signal, request route), applied
 *   on the kDisco thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
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
	 * Koog builds a `ToolDescriptor` from this list, splitting it into
	 * `requiredParameters` / `optionalParameters` by [DomainToolParameter.required].
	 * Koog derives the JSON schema the LLM sees from that descriptor, so the
	 * parameter list must be complete and accurate for tool-calling to work.
	 *
	 * Empty for tools that take no arguments.
	 */
	val parameters: List<DomainToolParameter>

	/**
	 * Execute this tool with the given arguments.
	 *
	 * ## Threading contract
	 *
	 * `execute()` is invoked by the Koog agent driver on **its own thread**, off the kDisco
	 * simulation thread. The two tool categories satisfy the kDisco threading contract by
	 * different mechanisms, neither of which marshals onto the kDisco thread directly:
	 *
	 * - **Perception tools** receive the live [NetworkPerceptionPort] directly and read only
	 *   [NetworkPerceptionPort.snapshot] — the sole `@Volatile`-backed reference to the most
	 *   recently published [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot], safe to read from
	 *   any thread. The port's single-query / `allXxx()` methods (e.g. on
	 *   [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]) walk live mutable
	 *   simulation state and are kDisco-thread-only; perception tools must never call them. The
	 *   snapshot they read may be one kDisco tick stale.
	 *
	 * - **Actuator tools** do **not** touch a [NetworkActuatorPort] directly. They build a
	 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision] subtype and post it (fire-and-forget) to
	 *   the [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] via
	 *   [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue.postAll].
	 *   [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains the queue on the
	 *   kDisco thread (registered as a
	 *   [cz.vutbr.fit.interlockSim.sim.ControlStepListener]) and applies the decision through the
	 *   actuator port there. The tool returns a `Success` describing the queued request and does
	 *   **not** wait for synchronous confirmation; the agent observes the outcome via the
	 *   perception tools on the following tick's snapshot.
	 *
	 * `execute()` therefore never reads or mutates live simulation state itself, regardless of
	 * which thread invokes it. Argument validation still happens on the driver thread before any
	 * decision is posted; invalid arguments return [ToolResult.Error] without posting.
	 *
	 * ## Result contract
	 *
	 * Returns a [ToolResult]; `execute()` does **not** throw for expected failures. Invalid
	 * arguments and port [IllegalArgumentException]s are translated into [ToolResult.Error];
	 * successful calls return [ToolResult.Success] wrapping the port's immutable snapshot / result
	 * object (perception) or a short "queued …" descriptor (actuator). [ToolResult.Success]`data`
	 * is serialized to the LLM and [ToolResult.Error]`message` is surfaced as a tool-error result;
	 * [ToolResult.Error]`cause` is for logging only and must not be sent to the LLM. This interface
	 * is intentionally serialization-agnostic about `data` (a `SemaphoreReading`, `Boolean`,
	 * `List<...>`, a queued-request string, …).
	 *
	 * @param args Tool arguments as a map of parameter name → value
	 * @return [ToolResult] — never `null`; expected failures are [ToolResult.Error], not exceptions
	 */
	suspend fun execute(args: Map<String, Any?>): ToolResult
}

/**
 * Outcome of a [DomainTool.execute] call.
 *
 * Gives the Koog executor a uniform, exception-free result for every tool invocation:
 * successes carry the port's immutable return value, expected failures (bad arguments, port
 * input errors) carry a human-readable message. Unexpected non-`Exception` throwables still
 * propagate.
 */
sealed interface ToolResult {
	/**
	 * The tool call succeeded. [data] is the wrapped port return value (an immutable
	 * `Reading` / `RouteRequestResult` / `Boolean` / `List<...>`), serialized into the
	 * LLM-facing tool result.
	 */
	data class Success(
		val data: Any?
	) : ToolResult

	/**
	 * The tool call failed in an expected way (invalid argument, unknown entity, port input
	 * error). [message] is safe to surface to the LLM as a tool-error result; [cause] is
	 * retained for logging/debugging and must not be sent to the LLM.
	 */
	data class Error(
		val message: String,
		val cause: Throwable? = null,
		/**
		 * Machine-readable classification of an in-turn argument rejection, or `null` for a
		 * failure that is not an argument rejection (a port error, an unavailable sensor).
		 *
		 * ## Why
		 *
		 * In production this is often `null`: the only `ActionOutcome` constructed in production
		 * passes `rejection = null`, since `ActionValidator` — the component that produces these
		 * codes — is reached only from the test-only `DispatchTickLoop`. Carrying a machine-readable
		 * code alongside the prose message (rather than relying solely on prompt text) makes
		 * rejections countable without parsing English, and keeps the guarantee in a validation
		 * layer the model cannot talk around.
		 *
		 * [message] remains what the LLM sees; this field never reaches it.
		 */
		val rejection: RejectionCode? = null
	) : ToolResult
}

/**
 * Koog-agnostic description of one [DomainTool] parameter.
 *
 * Each [DomainToolParameter] maps to Koog's
 * `ai.koog.agents.core.tools.ToolParameterDescriptor` and [DomainToolParameterType]
 * to Koog's `ToolParameterType`, building the `ToolDescriptor` the LLM sees. This
 * type intentionally has no `ai.koog.*` dependency so the domain seam stays
 * decoupled from the agent framework.
 *
 * @property name Parameter name as the LLM must emit it in a tool call.
 * @property description Human-readable meaning, shown to the LLM in the tool schema.
 * @property type Value type (see [DomainToolParameterType]).
 * @property required Whether the parameter must be present (required vs optional in Koog).
 */
data class DomainToolParameter(
	val name: String,
	val description: String,
	val type: DomainToolParameterType,
	val required: Boolean = true
)

/**
 * Koog-agnostic value types for [DomainToolParameter].
 *
 * A subset of Koog's `ToolParameterType` sealed hierarchy sufficient for the current
 * perception/actuator tools (signal-aspect enum, block-occupancy string/boolean,
 * train-position float, …). `Object` / `AnyOf` are omitted for now and can be added
 * later if a real tool needs structured or union parameters.
 *
 * The subtype names mirror Koog's `ToolParameterType` (`String`, `Integer`, …,
 * `List`, `Enum`); they shadow the stdlib `kotlin.String` / `kotlin.collections.List`
 * names *inside this sealed interface body*, so the internal `Enum.entries` reference
 * fully qualifies those stdlib types. Outside this body the nested names are not in
 * scope, so callers use the stdlib types normally.
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
