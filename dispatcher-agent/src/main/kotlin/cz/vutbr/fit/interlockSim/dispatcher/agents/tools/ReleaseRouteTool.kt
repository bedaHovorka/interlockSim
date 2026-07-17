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
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing [NetworkActuatorPort.releaseRoute] to Koog agents (SP1.6, Issue #551).
 *
 * Release all track blocks reserved for a named train. The symmetric counterpart of request_route:
 * when a train completes its journey or reverses, the dispatcher releases the route so blocks return to free.
 *
 * @param actuatorPort Scoped actuator port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class ReleaseRouteTool(
	private val actuatorPort: NetworkActuatorPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "release_route"

	override val description: String =
		"Release all track blocks reserved for a named train. " +
			"Returns true if at least one block was released, false if the train held no reservation."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainName",
				description = "Name of the train whose route should be released (non-blank)",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): Any? {
		val trainName =
			args["trainName"] as? String
				?: throw IllegalArgumentException("trainName parameter is required and must be a string")

		logger.debug { "ReleaseRouteTool.execute: trainName=$trainName" }

		return actuatorPort.releaseRoute(trainName)
	}
}
