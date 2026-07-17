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

import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing [NetworkActuatorPort.requestRoute] to Koog agents (SP1.6, Issue #551).
 *
 * Request a route reservation from one endpoint to another for a named train.
 *
 * @param actuatorPort Scoped actuator port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class RequestRouteTool(
	private val actuatorPort: NetworkActuatorPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "request_route"

	override val description: String =
		"Request the interlocking to find and atomically reserve a free end-to-end path for a named train. " +
			"Returns success with block count, or failure reason (no route exists, all paths blocked, " +
			"or conflict with existing train)."

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

		logger.debug {
			"RequestRouteTool.execute: trainName=$trainName, from=$fromEndpointName, to=$toEndpointName"
		}

		return runCatching { actuatorPort.requestRoute(trainName, fromEndpointName, toEndpointName) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("request_route failed: ${it.message}", it) })
	}
}
