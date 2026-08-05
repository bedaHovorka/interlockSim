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
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
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
 * ## Train-id validation (Issue #847 round 2)
 *
 * An earlier pass recorded the in-turn `trainId` check as impossible here, on the grounds that
 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort] exposes only *active* trains and never
 * the pending-admission *queue* this tool approves from. That reasoning was wrong about the means,
 * not the constraint: [DispatchLoopSensorPort] already carries the queue, is already a constructor
 * field of [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory], and — like the
 * perception port — is an internal port, not an LLM-facing tool. Reading it therefore adds no query
 * *capability* to the agent's surface, so the Goal 10 SP2c "no new query tool" decision is
 * respected; the tool surface remains exactly the four actuators.
 *
 * The check matters because the alternatives do not catch a bad id. `ActionValidator`'s
 * `UNKNOWN_TRAIN` rejection does not run on the live wiring path, and at the sim thread
 * `ShuntingLoop.approveQueuedTrain` returns silently on an unknown name — so a hallucinated id was
 * simply a lost turn, with nothing anywhere telling the model it had misnamed the train.
 *
 * [sensorPort] is optional and defaults to `null` (no pre-check, matching the prior behavior) so
 * existing callers and tests that wire no port are unaffected.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.ApproveTrain]
 *   is emitted to the active sink on every successful execution.
 * @param sensorPort Optional dispatch-loop sensor port used to validate `trainId` against the
 *   current admission queue before emitting; `null` skips the pre-check. Reads are a single
 *   volatile snapshot read and safe from the agent driver thread.
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent); rewired to [SinkHolder] in Issue #829 (SP2c.6);
 *   queue-membership pre-check added in Issue #847 round 2
 */
class ApproveTrainTool(
	private val sinkHolder: SinkHolder,
	private val sensorPort: DispatchLoopSensorPort? = null
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "approve_train"

	override val description: String =
		"Approve a queued train by id, admitting it from the pending queue into the active ShuntingLoop " +
			"simulation. Pass an id exactly as listed under the queued trains in this cycle's message. " +
			"Fire-and-forget: returns once the request is emitted; the train shows up among the active " +
			"trains in the next cycle's message, after the sim-thread applier processes it."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Identifier of the queued train to approve (non-blank)")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		val queuedTrainIds = sensorPort?.getQueuedTrains()?.map { it.trainId }?.toSet()
		// Emit the canonical id, never the raw argument — see resolveTrainId for why the bare
		// ordinal ("1" for "Train #1") has to be accepted at all.
		val resolvedTrainId =
			if (queuedTrainIds == null) {
				trainId
			} else {
				resolveTrainId(trainId, queuedTrainIds)
					?: return ToolResult.Error(
						"Unknown trainId '$trainId' — approve_train only accepts a train currently queued " +
							"for admission. Queued trains are: " +
							queuedTrainIds.sorted().joinToString(", ").ifEmpty { "(none queued)" }
					)
			}

		logger.debug { "ApproveTrainTool.execute: trainId=$resolvedTrainId (raw='$trainId')" }
		sinkHolder.emit(DispatchAction.ApproveTrain(resolvedTrainId))
		return ToolResult.Success("emitted approve_train trainId=$resolvedTrainId")
	}
}
