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
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing train approval to agent consumers (SP4.1, Issue #563).
 *
 * Approves a queued train by name, admitting it from the pending-admission queue into the
 * active ShuntingLoop simulation.
 *
 * ## Threading contract
 *
 * `execute()` runs on the agent driver thread, **not** the kDisco simulation thread. It
 * posts a [DispatchDecision.ApproveTrain] to the [ActuatorCommandQueue] (fire-and-forget)
 * and returns immediately. [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * drains and applies it on the kDisco thread during the next control step.
 *
 * The agent observes the outcome (the train appearing in network perception on the following
 * tick) via [AllTrainPositionsTool] or [QueuedTrainsTool].
 *
 * Approving a train that is already active or that does not exist in the queue is idempotent
 * (silently ignored at the sim thread).
 *
 * @param commandQueue Scoped command queue for this context (injected per simulation)
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class ApproveTrainTool(
	private val commandQueue: ActuatorCommandQueue
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "approve_train"

	override val description: String =
		"Approve a queued train by id, admitting it from the pending queue into the active ShuntingLoop " +
			"simulation. Fire-and-forget: returns once the request is queued; the train appears in " +
			"all_train_positions on the next tick after the sim-thread applier processes it."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "trainId",
				description = "Identifier of the train to approve (non-blank; as returned by queued_trains)",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam("trainId")
				?: return ToolResult.Error("trainId parameter is required and must be a non-blank string")

		val decision = DispatchDecision.ApproveTrain(trainId)
		logger.debug { "ApproveTrainTool.execute: posting ApproveTrain(trainId=$trainId)" }
		val accepted = commandQueue.postAll(listOf(decision))
		return if (accepted) {
			ToolResult.Success("queued approve_train trainId=$trainId")
		} else {
			ToolResult.Error("approve_train rejected: actuator command queue is full (backpressure)")
		}
	}
}
