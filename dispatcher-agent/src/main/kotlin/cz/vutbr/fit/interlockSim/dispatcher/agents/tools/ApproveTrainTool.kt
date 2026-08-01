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

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing train approval to agent consumers (SP4.1, Issue #563;
 * rewired to [SinkHolder] in SP2c.6, Issue #829).
 *
 * Approves a queued train by name, admitting it from the pending-admission queue into the
 * active ShuntingLoop simulation.
 *
 * ## Threading contract (SP2c.6, Issue #829)
 *
 * `execute()` runs on the agent driver thread. It emits [DispatchAction.ApproveTrain] to the
 * active [sinkHolder] (fire-and-forget). The [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]
 * collects the emission, validates it through [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator],
 * and posts the validated [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ApproveTrain] to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] for the kDisco thread.
 *
 * Approving a train that is already active or that does not exist in the queue is idempotent
 * (silently ignored at the sim thread).
 *
 * ## Concurrency cap (removed in SP2c.6)
 *
 * The pre-queue cap check that existed before SP2c.6 has been removed. The **authoritative**
 * enforcement is in [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] on the kDisco
 * simulation thread, where the live active-train count is available. The [ActionValidator] now
 * handles early rejection at the validation step.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.ApproveTrain]
 *   is emitted to the active sink on every successful execution.
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent); rewired to [SinkHolder] in Issue #829 (SP2c.6)
 */
class ApproveTrainTool(
	private val sinkHolder: SinkHolder
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "approve_train"

	override val description: String =
		"Approve a queued train by id, admitting it from the pending queue into the active ShuntingLoop " +
			"simulation. Fire-and-forget: returns once the request is emitted; the train appears in " +
			"all_train_positions on the next tick after the sim-thread applier processes it."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Identifier of the train to approve (non-blank; as returned by queued_trains)")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		logger.debug { "ApproveTrainTool.execute: trainId=$trainId" }
		sinkHolder.emit(DispatchAction.ApproveTrain(trainId))
		return ToolResult.Success("emitted approve_train trainId=$trainId")
	}
}
