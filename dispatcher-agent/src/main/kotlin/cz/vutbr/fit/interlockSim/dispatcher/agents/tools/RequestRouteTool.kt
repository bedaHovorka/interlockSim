/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing end-to-end route reservation to Koog agents (SP1.6, Issue #551;
 * rewired to [SinkHolder] in SP2c.6, Issue #829).
 *
 * Request a route reservation from one endpoint to another for a named train.
 *
 * ## Threading contract (SP2c.6, Issue #829)
 *
 * `execute()` runs on the agent driver thread. It emits [DispatchAction.RequestRoute] to the
 * active [sinkHolder] (fire-and-forget). The [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]
 * collects the emission, validates it through [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator],
 * and posts the validated [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] for the kDisco thread.
 *
 * ## Endpoint-name validation (Goal 10 agent-architect review finding)
 *
 * A live run showed the LLM occasionally hallucinating a plausible-looking endpoint name
 * (e.g. `"kA"`/`"kB"`) instead of copying one from the topology in its system prompt. [execute]
 * validates [fromEndpointName]/[toEndpointName] against [validEndpointNames] synchronously and
 * returns a [ToolResult.Error] naming the valid options when either is unrecognized, so the model
 * gets in-turn feedback and can retry with a real name instead of silently losing the decision.
 *
 * ## Train-name validation (Issue #847 round 2)
 *
 * Endpoint names were validated from the start; `trainName` was not, and that asymmetry proved
 * expensive. A live run had the model copy the literal placeholder train name out of a worked
 * example in its own system prompt; the request passed straight through to `reservePath`, which
 * reserved real blocks for a train that does not exist. Nothing releases such a reservation — no
 * `Train` bears the name, so the tail-clearing release never fires, `ShuntingLoop` has no orphan
 * sweeper, and [CancelRouteTool] rejects the id for the very reason it is bogus — so every later
 * train conflicted on those blocks for the rest of the run.
 *
 * [execute] therefore also validates [trainName] against the union of the **queued** trains
 * ([sensorPort]) and the **active** trains ([perceptionPort]). Both, deliberately: reserving a
 * route before calling `approve_train` is the intended order, so restricting to active trains
 * would reject legitimate requests. Both ports are internal, already injected into
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory], and are not LLM-facing tools, so
 * the actuator surface stays at four and the SP2c "no new query tool" decision holds.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.RequestRoute]
 *   is emitted to the active sink on successful execution.
 * @param validEndpointNames The exact set of InOut and Signal names this network recognizes
 *   (from [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopology], captured once at agent
 *   construction — topology is static and never changes during a run).
 * @param perceptionPort Optional off-thread-safe perception port supplying currently active
 *   trains for the [trainName] pre-check; `null` skips the pre-check.
 * @param sensorPort Optional dispatch-loop sensor port supplying trains queued for admission for
 *   the [trainName] pre-check; `null` skips the pre-check.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to [SinkHolder] in Issue #829 (SP2c.6);
 *   train-name pre-check added in Issue #847 round 2
 */
class RequestRouteTool(
	private val sinkHolder: SinkHolder,
	private val validEndpointNames: Set<String>,
	private val perceptionPort: NetworkPerceptionPort? = null,
	private val sensorPort: DispatchLoopSensorPort? = null
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "request_route"

	override val description: String =
		"Request the interlocking to find and atomically reserve a free path for a named train, between " +
			"any two endpoints from the InOuts or Signals lists. A Signal-to-Signal route reserves one " +
			"section and is usually preferable to an InOut-to-InOut route, which holds every block in " +
			"between. Fire-and-forget: returns once the request is emitted; the reservation outcome shows " +
			"up in the next cycle's message."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainName",
				description =
					"Identifier of the train that will use the reserved route, copied verbatim from the " +
						"queued or active train list in this cycle's message (non-blank). Never invent " +
						"an id and never copy one out of an example.",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "fromEndpointName",
				description =
					"Exact name of the entry point, copied verbatim from the InOuts or " +
						"Signals list in the STATION TOPOLOGY section of your system prompt. Do not " +
						"abbreviate, translate, or invent a name — if the name you want isn't listed " +
						"there, do not call this tool. Do NOT pass a Block ID (e.g. a name from the " +
						"Blocks list, such as \"kA\") — a Block ID names a piece of track, not an endpoint.",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "toEndpointName",
				description =
					"Exact name of the exit point, copied verbatim from the InOuts or " +
						"Signals list in the STATION TOPOLOGY section of your system prompt. Do not " +
						"abbreviate, translate, or invent a name — if the name you want isn't listed " +
						"there, do not call this tool. Do NOT pass a Block ID (e.g. a name from the " +
						"Blocks list, such as \"kA\") — a Block ID names a piece of track, not an endpoint.",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainName =
			args.stringParam("trainName")
				?: return ToolResult.Error("trainName parameter is required and must be a non-blank string")
		val fromEndpointName =
			args.stringParam("fromEndpointName")
				?: return ToolResult.Error("fromEndpointName parameter is required and must be a non-blank string")
		val toEndpointName =
			args.stringParam("toEndpointName")
				?: return ToolResult.Error("toEndpointName parameter is required and must be a non-blank string")

		if (fromEndpointName !in validEndpointNames) {
			return ToolResult.Error(
				"Unknown fromEndpointName '$fromEndpointName' — valid names are: " +
					validEndpointNames.sorted().joinToString(", ")
			)
		}
		if (toEndpointName !in validEndpointNames) {
			return ToolResult.Error(
				"Unknown toEndpointName '$toEndpointName' — valid names are: " +
					validEndpointNames.sorted().joinToString(", ")
			)
		}
		// Emit the canonical id, never the raw argument — see resolveTrainId for why the bare
		// ordinal ("1" for "Train #1") has to be accepted at all.
		val resolvedTrainName =
			knownTrainIds()?.let { known ->
				resolveTrainId(trainName, known)
					?: return ToolResult.Error(
						"Unknown trainName '$trainName' — routes can only be requested for a train that is " +
							"queued or active. Those trains are: " +
							known.sorted().joinToString(", ").ifEmpty { "(no trains queued or active)" }
					)
			} ?: trainName

		val action = DispatchAction.RequestRoute(resolvedTrainName, fromEndpointName, toEndpointName)
		logger.debug {
			"RequestRouteTool.execute: emitting action trainName=$resolvedTrainName (raw='$trainName'), " +
				"from=$fromEndpointName, to=$toEndpointName"
		}
		sinkHolder.emit(action)
		return ToolResult.Success(
			"emitted request_route train=$resolvedTrainName from=$fromEndpointName to=$toEndpointName"
		)
	}

	/**
	 * Every train name the model may legitimately pass this cycle: those queued for admission plus
	 * those already running. Returns `null` when neither port is wired, which disables the
	 * [trainName] pre-check entirely rather than validating against a misleadingly partial set —
	 * with only one port present the union would be incomplete, and rejecting a real train is worse
	 * than the pre-#847 behavior of accepting anything.
	 */
	private fun knownTrainIds(): Set<String>? {
		if (perceptionPort == null || sensorPort == null) return null
		val active = perceptionPort.snapshot().trainPositions.map { it.trainId }
		val queued = sensorPort.getQueuedTrains().map { it.trainId }
		return (active + queued).toSet()
	}
}
