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
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing switch commands to Koog agents (SP1.6, Issue #551; rewired to the
 * command queue in SP1.7, Issue #774).
 *
 * Command a named rail switch to MAIN or BRANCH position. The switch is set only if not locked
 * (i.e. no train occupying or reserved through it).
 *
 * ## Threading contract (SP1.7, Issue #774)
 *
 * `execute()` runs on the agent driver thread and must not touch the live
 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] directly. It builds a
 * [DispatchDecision.SetSwitchPosition] and posts it via [ActuatorCommandQueue.postAll];
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains the queue on the
 * kDisco thread and applies the switch command.
 *
 * Fire-and-forget: returns once the request is queued; the agent observes the switch position
 * via perception tools on the next tick.
 *
 * @param commandQueue Scoped command queue for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class SetSwitchPositionTool(
	private val commandQueue: ActuatorCommandQueue
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "set_switch_position"

	override val description: String =
		"Command a named rail switch to MAIN or BRANCH position. " +
			"Fire-and-forget: returns once the command is queued; observe the effect next tick."

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

		val decision = DispatchDecision.SetSwitchPosition(switchName, position)
		logger.debug { "SetSwitchPositionTool.execute: posting decision switchName=$switchName, position=$position" }
		val accepted = commandQueue.postAll(listOf(decision))
		return if (accepted) {
			ToolResult.Success("queued set_switch_position switch=$switchName position=${position.name}")
		} else {
			ToolResult.Error("set_switch_position rejected: actuator command queue is full (backpressure)")
		}
	}
}
