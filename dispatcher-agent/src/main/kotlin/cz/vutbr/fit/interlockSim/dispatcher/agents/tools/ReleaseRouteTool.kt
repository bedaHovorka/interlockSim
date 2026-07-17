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

import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing route release to Koog agents (SP1.6, Issue #551; rewired to the
 * command queue in SP1.7, Issue #774).
 *
 * Release all track blocks reserved for a named train. The symmetric counterpart of
 * request_route: when a train completes its journey or reverses, the dispatcher releases the
 * route so blocks return to free.
 *
 * ## Threading contract (SP1.7, Issue #774)
 *
 * `execute()` runs on the agent driver thread and must not touch the live
 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] directly. It builds a
 * [DispatchDecision.ReleaseRoute] and posts it via [ActuatorCommandQueue.postAll];
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains the queue on the
 * kDisco thread and applies the release.
 *
 * Fire-and-forget: returns once the request is queued; the agent observes the freed blocks
 * via `block_occupancy` on the next tick.
 *
 * @param commandQueue Scoped command queue for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class ReleaseRouteTool(
	private val commandQueue: ActuatorCommandQueue
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "release_route"

	override val description: String =
		"Release all track blocks reserved for a named train. " +
			"Fire-and-forget: returns once the release is queued; observe the effect via block_occupancy next tick."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainName",
				description = "Name of the train whose route should be released (non-blank)",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainName =
			args.stringParam("trainName")
				?: return ToolResult.Error("trainName parameter is required and must be a non-blank string")

		val decision = DispatchDecision.ReleaseRoute(trainName)
		logger.debug { "ReleaseRouteTool.execute: posting decision trainName=$trainName" }
		val accepted = commandQueue.postAll(listOf(decision))
		return if (accepted) {
			ToolResult.Success("queued release_route train=$trainName")
		} else {
			ToolResult.Error("release_route rejected: actuator command queue is full (backpressure)")
		}
	}
}
