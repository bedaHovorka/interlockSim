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
import cz.vutbr.fit.interlockSim.ports.DispatchLoopActuatorPort
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
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
 * delegates to [DispatchLoopActuatorPort.approveTrain], which posts a
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ApproveTrain] to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] (fire-and-forget) and returns
 * immediately. [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains and
 * applies it on the kDisco thread during the next control step.
 *
 * The agent observes the outcome (the train appearing in network perception on the following
 * tick) via [AllTrainPositionsTool] or [QueuedTrainsTool].
 *
 * Approving a train that is already active or that does not exist in the queue is idempotent
 * (silently ignored at the sim thread).
 *
 * ## Concurrency cap
 *
 * [execute] rejects the request early with [ToolResult.Error] when [activeTrainCountProvider]
 * already reports [maxConcurrentTrains] or more active trains. This is a **validator hint**
 * (fast feedback to the LLM, based on the most recent tick's snapshot), not the authoritative
 * enforcement point.
 *
 * The **authoritative enforcement** is in [DispatchDecisionApplier] on the kDisco simulation
 * thread, where the live active-train count is available. Two `approve_train` calls issued in
 * the same driver tick can both pass this stale-snapshot check; the applier resolves them in
 * FIFO order and publishes [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Approved]
 * with `admitted = false` and reason
 * [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.CAP_EXCEEDED] for any that exceed the cap.
 * The refusal surfaces in the next tick's `applied_outcomes` block
 * (SP2c.17/#840) so the agent learns the admission did not take effect.
 *
 * Pre-queue rejections (this method) are distinguishable from apply-time refusals in the
 * metrics by their type: [cz.vutbr.fit.interlockSim.dispatcher.RejectionCode.CAPACITY_FULL]
 * vs [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.CAP_EXCEEDED] (SP2c.18/#841, SP2c.20).
 *
 * @param actuatorPort Dispatch-loop actuator port for this context (injected per simulation);
 *   the default implementation wraps the scoped [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue].
 * @param activeTrainCountProvider Reads the current number of active (approved) trains; must be
 *   safe to call from the agent driver thread (e.g. an off-thread-safe perception projection).
 * @param maxConcurrentTrains Maximum number of trains allowed active at once. Defaults to
 *   [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS], the same station-capacity constant the
 *   non-LLM dispatcher uses.
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class ApproveTrainTool(
	private val actuatorPort: DispatchLoopActuatorPort,
	private val activeTrainCountProvider: () -> Int,
	private val maxConcurrentTrains: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "approve_train"

	override val description: String =
		"Approve a queued train by id, admitting it from the pending queue into the active ShuntingLoop " +
			"simulation. Fire-and-forget: returns once the request is queued; the train appears in " +
			"all_train_positions on the next tick after the sim-thread applier processes it. " +
			"Rejected if $maxConcurrentTrains trains are already active (this station's concurrent-train " +
			"capacity)."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Identifier of the train to approve (non-blank; as returned by queued_trains)")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		val activeCount = activeTrainCountProvider()
		if (activeCount >= maxConcurrentTrains) {
			return ToolResult.Error(
				"approve_train rejected: $activeCount of $maxConcurrentTrains maximum concurrent trains " +
					"already active. Wait for an active train to complete its journey (or be released) " +
					"before approving another."
			)
		}

		logger.debug { "ApproveTrainTool.execute: trainId=$trainId" }
		return runCatching { actuatorPort.approveTrain(trainId) }
			.fold(
				{ accepted ->
					if (accepted) {
						ToolResult.Success("queued approve_train trainId=$trainId")
					} else {
						ToolResult.Error("approve_train rejected: actuator command queue is full (backpressure)")
					}
				},
				{ ToolResult.Error("approve_train failed: ${it.message}", it) }
			)
	}
}
