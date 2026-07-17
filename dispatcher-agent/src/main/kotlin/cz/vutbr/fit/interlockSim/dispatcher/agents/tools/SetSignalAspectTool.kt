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
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing [NetworkActuatorPort.setSignalAspect] to Koog agents (SP1.6, Issue #551).
 *
 * Command a named semaphore to display a given signal aspect (STOP, S30, S40, S60, S80, S100, FREE).
 * Both upgrades and downgrades are permitted on a dynamic semaphore.
 *
 * @param actuatorPort Scoped actuator port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class SetSignalAspectTool(
	private val actuatorPort: NetworkActuatorPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "set_signal_aspect"

	override val description: String =
		"Command a named semaphore to display a given signal aspect (STOP, S30, S40, S60, S80, S100, FREE). " +
			"Returns true if the semaphore now displays the requested aspect, false if it does not exist or is constant."

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

		logger.debug { "SetSignalAspectTool.execute: semaphoreName=$semaphoreName, signal=$signal" }

		return runCatching { actuatorPort.setSignalAspect(semaphoreName, signal) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("set_signal_aspect failed: ${it.message}", it) })
	}
}
