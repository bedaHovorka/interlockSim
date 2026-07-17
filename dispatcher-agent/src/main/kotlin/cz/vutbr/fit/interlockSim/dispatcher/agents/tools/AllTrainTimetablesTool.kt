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
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Perception tool exposing [NetworkPerceptionPort.allTrainTimetables] to Koog agents (SP1.6, Issue #551).
 *
 * Query the timetables of **all** currently active trains in one call.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class AllTrainTimetablesTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "all_train_timetables"

	override val description: String =
		"Query the timetables of all currently active trains in the railway network. " +
			"Returns origins, destinations, and scheduled times for all active trains."

	override val parameters: List<DomainToolParameter> = emptyList()

	override suspend fun execute(args: Map<String, Any?>): Any? {
		logger.debug { "AllTrainTimetablesTool.execute" }
		return perceptionPort.allTrainTimetables()
	}
}
