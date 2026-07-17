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
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Perception tool exposing [NetworkPerceptionPort.signalAspect] to Koog agents (SP1.6, Issue #551).
 *
 * Query the current signal aspect (STOP, S30, S60, FREE, etc.) of a named semaphore.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class SignalAspectTool(private val perceptionPort: NetworkPerceptionPort) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "signal_aspect"

	override val description: String =
		"Query the current signal aspect (STOP, S30, S60, FREE, etc.) of a named semaphore. " +
			"Returns the signal aspect and aspect metadata, or null if the semaphore does not exist."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "semaphoreName",
				description = "Name of the semaphore as configured in the railway network XML (e.g. \"zA\", \"doB1\")",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): Any? {
		val semaphoreName = args["semaphoreName"] as? String
			?: throw IllegalArgumentException("semaphoreName parameter is required and must be a string")

		logger.debug { "SignalAspectTool.execute: semaphoreName=$semaphoreName" }

		return perceptionPort.signalAspect(semaphoreName)
	}
}
