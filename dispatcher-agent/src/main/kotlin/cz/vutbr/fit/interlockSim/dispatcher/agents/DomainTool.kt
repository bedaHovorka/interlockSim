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
 * Base interface for domain tools exposed to Koog agents (SP1 skeleton, Issue #547).
 *
 * A domain tool represents a single capability that an LLM-driven or algorithmic agent
 * can invoke to perceive or actuate the railway network. Tools are bridged to Koog's
 * tool framework via tool definitions (SP1.6, Issue #551).
 *
 * ## Tool categories
 *
 * - **Perception tools** - Read-only queries on [NetworkPerceptionPort] (e.g. signal
 *   state, block occupancy, train positions). In SP1.7 the port is the off-thread-safe
 *   [cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort] projection.
 *   Mapped to Koog tools in SP1.6.
 * - **Actuator tools** - Commands posted (fire-and-forget) to
 *   [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] as
 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision]s (e.g. set signal, request route), applied
 *   on the kDisco thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 *   (SP1.7). Mapped to Koog tools in SP1.6.
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
	 * ## Threading contract (SP1.7, Issue #774)
	 *
	 * `execute()` is invoked by the Koog agent driver on **its own thread**, off the kDisco
	 * simulation thread. The two tool categories satisfy the kDisco threading contract by
	 * different mechanisms, neither of which marshals onto the kDisco thread directly:
	 *
	 * - **Perception tools** read from a
	 *   [cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort] — an
	 *   off-thread-safe [NetworkPerceptionPort] that projects every query from the most recently
	 *   published [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot] (the live
	 *   [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]'s single-query / `allXxx()`
	 *   methods read mutable state and are kDisco-thread-only; only
	 *   [NetworkPerceptionPort.snapshot] is `@Volatile`-backed and off-thread-safe). The projection
	 *   is built once in [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory.createAgent]
	 *   and passed to every perception tool. The projected snapshot may be one kDisco tick stale.
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
	 * object (perception) or a short "queued …" descriptor (actuator). SP1.7 serializes
	 * [ToolResult.Success]`data` to the LLM and surfaces [ToolResult.Error]`message` as a
	 * tool-error result; [ToolResult.Error]`cause` is for logging only and must not be sent to the
	 * LLM. Serialization of `data` (a `SemaphoreReading`, `Boolean`, `List<...>`, a queued-request
	 * string, …) is SP1.7's job — this interface is intentionally serialization-agnostic.
	 *
	 * @param args Tool arguments as a map of parameter name → value
	 * @return [ToolResult] — never `null`; expected failures are [ToolResult.Error], not exceptions
	 */
	suspend fun execute(args: Map<String, Any?>): ToolResult
}

/**
 * Outcome of a [DomainTool.execute] call (SP1.6, Issue #551).
 *
 * Replaces the previous raw `Any?` / thrown-exception contract so the SP1.7 Koog executor gets
 * a uniform, exception-free result for every tool invocation: successes carry the port's
 * immutable return value, expected failures (bad arguments, port input errors) carry a
 * human-readable message. Unexpected non-`Exception` throwables still propagate.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
sealed interface ToolResult {
	/**
	 * The tool call succeeded. [data] is the wrapped port return value (an immutable
	 * `Reading` / `RouteRequestResult` / `Boolean` / `List<...>`). SP1.7 serializes [data]
	 * into the LLM-facing tool result.
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
		 * ## Why (Issue #847 round 4)
		 *
		 * Rounds 2 and 3 reported their headline "86 rejected endpoint calls" / "0 rejected"
		 * figures by grepping the run log. #847's sweep is unattended and writes one JSON per run,
		 * so it cannot grep — and `DispatcherRunSnapshot.rejectionsByCode`, the field #846's
		 * aggregator renders its "Failure Modes" table from, was structurally always empty: the
		 * only `ActionOutcome` constructed in production passes `rejection = null`, and
		 * `ActionValidator` — the component that produces these codes — is reached only from the
		 * test-only `DispatchTickLoop`.
		 *
		 * The tools already reject these calls and already explain why in prose. Carrying the code
		 * alongside the prose makes the rate countable without parsing English, and keeps the
		 * guarantee in a validation layer the model cannot talk around rather than in prompt text
		 * it can ignore.
		 *
		 * [message] remains what the LLM sees; this field never reaches it.
		 *
		 * @since Issue #847 round 4 (PR #891)
		 */
		val rejection: RejectionCode? = null
	) : ToolResult
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
