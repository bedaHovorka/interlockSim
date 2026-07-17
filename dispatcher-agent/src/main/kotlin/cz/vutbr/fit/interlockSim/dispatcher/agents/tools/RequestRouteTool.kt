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
 * Actuator tool exposing end-to-end route reservation to Koog agents (SP1.6, Issue #551;
 * rewired to the command queue in SP1.7, Issue #774).
 *
 * Request a route reservation from one endpoint to another for a named train.
 *
 * ## Threading contract (SP1.7, Issue #774)
 *
 * `execute()` runs on the agent driver thread, **not** the kDisco simulation thread, so it
 * must not touch the live [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] directly.
 * Instead it marshals the request through the [ActuatorCommandQueue]: it builds a
 * [DispatchDecision.RequestRoute] and posts it via [ActuatorCommandQueue.postAll].
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains the queue on the
 * kDisco thread and applies the decision — satisfying the threading contract.
 *
 * This is **fire-and-forget**: the tool returns a `Success` describing the queued request and
 * does **not** wait for the reservation to succeed or fail. The agent observes the outcome
 * (blocks reserved, or still free / a conflict) via the perception tools (`block_occupancy`,
 * `signal_aspect`) on the next tick's snapshot. The synchronous grant/deny reason
 * (`RouteRequestResult`) is therefore not returned to the LLM on this path; reintroducing it
 * would require a request/response channel over the queue (out of scope for SP1.7).
 *
 * @param commandQueue Scoped command queue for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class RequestRouteTool(
	private val commandQueue: ActuatorCommandQueue
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "request_route"

	override val description: String =
		"Request the interlocking to find and atomically reserve a free end-to-end path for a named train. " +
			"Fire-and-forget: returns once the request is queued; the reservation outcome is observed " +
			"via block_occupancy / signal_aspect on the next tick."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainName",
				description = "Identifier of the train that will use the reserved route (non-blank)",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "fromEndpointName",
				description = "Name of the entry InOut or Semaphore (must exist in the network)",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "toEndpointName",
				description = "Name of the exit InOut or Semaphore (must exist in the network)",
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

		val decision = DispatchDecision.RequestRoute(trainName, fromEndpointName, toEndpointName)
		logger.debug {
			"RequestRouteTool.execute: posting decision trainName=$trainName, " +
				"from=$fromEndpointName, to=$toEndpointName"
		}
		val accepted = commandQueue.postAll(listOf(decision))
		return if (accepted) {
			ToolResult.Success("queued request_route train=$trainName from=$fromEndpointName to=$toEndpointName")
		} else {
			ToolResult.Error("request_route rejected: actuator command queue is full (backpressure)")
		}
	}
}
