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
 * Perception tool exposing [NetworkPerceptionPort.trainTimetable] to Koog agents (SP1.6, Issue #551).
 *
 * Query the timetable of a named train: origin, destination, and scheduled times.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class TrainTimetableTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "train_timetable"

	override val description: String =
		"Query the timetable of a named train. " +
			"Returns origin, destination, and scheduled arrival/departure times."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Train identifier (e.g. \"Train #1\", \"Train #2\")")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		logger.debug { "TrainTimetableTool.execute: trainId=$trainId" }

		return runCatching { perceptionPort.trainTimetable(trainId) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("train_timetable failed: ${it.message}", it) })
	}
}
