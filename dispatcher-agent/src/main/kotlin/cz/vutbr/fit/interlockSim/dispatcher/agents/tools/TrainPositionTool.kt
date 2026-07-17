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
 * Perception tool exposing [NetworkPerceptionPort.trainPosition] to Koog agents (SP1.6, Issue #551).
 *
 * Query the current kinematics (position, velocity, acceleration) of a named train.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class TrainPositionTool(private val perceptionPort: NetworkPerceptionPort) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "train_position"

	override val description: String =
		"Query the current kinematics (position, velocity, acceleration) of a named train. " +
			"Returns velocity, acceleration, total distance traveled, and the current track block section."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainId",
				description = "Train identifier (e.g. \"Train #1\", \"Train #2\")",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): Any? {
		val trainId = args["trainId"] as? String
			?: throw IllegalArgumentException("trainId parameter is required and must be a string")

		logger.debug { "TrainPositionTool.execute: trainId=$trainId" }

		return perceptionPort.trainPosition(trainId)
	}
}
