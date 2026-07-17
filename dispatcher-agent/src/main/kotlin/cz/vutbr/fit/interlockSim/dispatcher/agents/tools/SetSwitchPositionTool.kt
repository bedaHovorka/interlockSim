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
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing [NetworkActuatorPort.setSwitchPosition] to Koog agents (SP1.6, Issue #551).
 *
 * Command a named rail switch to MAIN or BRANCH position. The switch is set only if not locked
 * (i.e. no train occupying or reserved through it).
 *
 * @param actuatorPort Scoped actuator port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class SetSwitchPositionTool(
	private val actuatorPort: NetworkActuatorPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "set_switch_position"

	override val description: String =
		"Command a named rail switch to MAIN or BRANCH position. " +
			"Returns true if the switch is now in the requested position, false if locked or does not exist."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "switchName",
				description = "Name of the switch (must exist in the network; case-sensitive)",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "position",
				description = "Target position: MAIN or BRANCH",
				type = DomainToolParameterType.Enum(listOf("MAIN", "BRANCH")),
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): Any? {
		val switchName =
			args["switchName"] as? String
				?: throw IllegalArgumentException("switchName parameter is required and must be a string")
		val positionStr =
			args["position"] as? String
				?: throw IllegalArgumentException("position parameter is required and must be a string")

		val position =
			when (positionStr.uppercase()) {
				"MAIN" -> RailSwitch.Conf.MAIN
				"BRANCH" -> RailSwitch.Conf.BRANCH
				else -> throw IllegalArgumentException("position must be MAIN or BRANCH, got: $positionStr")
			}

		logger.debug { "SetSwitchPositionTool.execute: switchName=$switchName, position=$position" }

		return actuatorPort.setSwitchPosition(switchName, position)
	}
}
