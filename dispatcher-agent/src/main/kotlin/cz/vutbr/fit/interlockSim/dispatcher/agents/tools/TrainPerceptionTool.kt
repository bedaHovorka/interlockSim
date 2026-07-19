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
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Perception tool exposing [NetworkPerceptionPort.trainPerception] to Koog agents (SP2a.1, Issue #552).
 *
 * Retrieves the first-person perception snapshot of a named train: signal ahead,
 * current speed limit, own kinematics (velocity, acceleration, position), next timetable
 * event, and dwell state — all the inputs a reactive train agent (SP2a) needs for its
 * sense → decide → act loop.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 */
class TrainPerceptionTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "train_perception"

	override val description: String =
		"Query the first-person perception snapshot of a named train. " +
			"Returns the signal aspect of the next semaphore ahead, the current track speed limit, " +
			"the train's own kinematics (velocity, acceleration, total distance, front block), " +
			"the next timetable destination and scheduled arrival time, and whether the train is " +
			"currently stopped (dwelling)."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Train identifier (e.g. \"Train #1\", \"Train #2\")")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		logger.debug { "TrainPerceptionTool.execute: trainId=$trainId" }

		return runCatching { perceptionPort.trainPerception(trainId) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("train_perception failed: ${it.message}", it) })
	}
}
