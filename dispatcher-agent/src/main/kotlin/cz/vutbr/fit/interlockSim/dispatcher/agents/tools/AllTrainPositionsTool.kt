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
 * Perception tool exposing [NetworkPerceptionPort.allTrainPositions] to Koog agents (SP1.6, Issue #551).
 *
 * Query the kinematics of **all** currently active trains in one call.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class AllTrainPositionsTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "all_train_positions"

	override val description: String =
		"Query the kinematics of all currently active trains in the railway network. " +
			"Returns a list of all train positions, velocities, accelerations, and current track sections."

	override val parameters: List<DomainToolParameter> = emptyList()

	override suspend fun execute(args: Map<String, Any?>): Any? {
		logger.debug { "AllTrainPositionsTool.execute" }
		return perceptionPort.allTrainPositions()
	}
}
