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
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing signal commands to Koog agents (SP1.6, Issue #551; rewired to the
 * command queue in SP1.7, Issue #774).
 *
 * Command a named semaphore to display a given signal aspect (STOP, S30, S40, S60, S80, S100, FREE).
 * Both upgrades and downgrades are permitted on a dynamic semaphore.
 *
 * ## Threading contract (SP1.7, Issue #774)
 *
 * `execute()` runs on the agent driver thread and must not touch the live
 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] directly. It builds a
 * [DispatchDecision.SetSignalAspect] and posts it via [ActuatorCommandQueue.postAll];
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains the queue on the
 * kDisco thread and applies the signal command.
 *
 * Fire-and-forget: returns once the request is queued; the agent observes the displayed aspect
 * via `signal_aspect` on the next tick.
 *
 * @param commandQueue Scoped command queue for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class SetSignalAspectTool(
	private val commandQueue: ActuatorCommandQueue
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "set_signal_aspect"

	override val description: String =
		"Command a named semaphore to display a given signal aspect (STOP, S30, S40, S60, S80, S100, FREE). " +
			"Fire-and-forget: returns once the command is queued; observe the aspect via signal_aspect next tick."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "semaphoreName",
				description = "Name of the semaphore (must exist in the network; case-sensitive)",
				type = DomainToolParameterType.String,
				required = true
			),
			DomainToolParameter(
				name = "signal",
				description = "Target signal aspect (STOP, S30, S40, S60, S80, S100, FREE)",
				type = DomainToolParameterType.Enum(Signal.entries.map { it.name }),
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val semaphoreName =
			args.stringParam("semaphoreName")
				?: return ToolResult.Error("semaphoreName parameter is required and must be a non-blank string")
		val signal =
			args.enumParam<Signal>("signal")
				?: return ToolResult.Error(
					"signal parameter is required and must be one of: " +
						Signal.entries.joinToString(", ") { it.name }
				)

		val decision = DispatchDecision.SetSignalAspect(semaphoreName, signal)
		logger.debug {
			"SetSignalAspectTool.execute: posting decision semaphoreName=$semaphoreName, signal=$signal"
		}
		val accepted = commandQueue.postAll(listOf(decision))
		return if (accepted) {
			ToolResult.Success("queued set_signal_aspect semaphore=$semaphoreName signal=${signal.name}")
		} else {
			ToolResult.Error("set_signal_aspect rejected: actuator command queue is full (backpressure)")
		}
	}
}
