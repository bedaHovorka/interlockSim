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
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing [NetworkActuatorPort.setSignalAspect] to Koog agents (SP1.6, Issue #551).
 *
 * Command a named semaphore to display a given signal aspect (STOP, S30, S60, FREE, etc.).
 * Both upgrades and downgrades are permitted on a dynamic semaphore.
 *
 * @param actuatorPort Scoped actuator port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class SetSignalAspectTool(private val actuatorPort: NetworkActuatorPort) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "set_signal_aspect"

	override val description: String =
		"Command a named semaphore to display a given signal aspect (STOP, S30, S60, FREE). " +
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
				description = "Target signal aspect (STOP, S30, S60, FREE, etc.)",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): Any? {
		val semaphoreName = args["semaphoreName"] as? String
			?: throw IllegalArgumentException("semaphoreName parameter is required and must be a string")
		val signalStr = args["signal"] as? String
			?: throw IllegalArgumentException("signal parameter is required and must be a string")

		// Parse the signal aspect from string
		val signal = try {
			Signal.valueOf(signalStr.uppercase())
		} catch (e: IllegalArgumentException) {
			throw IllegalArgumentException("Invalid signal aspect: $signalStr (must be one of: ${Signal.entries.joinToString(", ") { it.name }})")
		}

		logger.debug { "SetSignalAspectTool.execute: semaphoreName=$semaphoreName, signal=$signal" }

		return actuatorPort.setSignalAspect(semaphoreName, signal)
	}
}
