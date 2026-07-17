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
				type = DomainToolParameterType.Enum(RailSwitch.Conf.entries.map { it.name }),
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val switchName =
			args.stringParam("switchName")
				?: return ToolResult.Error("switchName parameter is required and must be a non-blank string")
		val position =
			args.enumParam<RailSwitch.Conf>("position")
				?: return ToolResult.Error(
					"position parameter is required and must be one of: " +
						RailSwitch.Conf.entries.joinToString(", ") { it.name }
				)

		logger.debug { "SetSwitchPositionTool.execute: switchName=$switchName, position=$position" }

		return runCatching { actuatorPort.setSwitchPosition(switchName, position) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("set_switch_position failed: ${it.message}", it) })
	}
}
