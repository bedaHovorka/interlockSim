/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

/**
 * Failure codes produced by [DispatchDecisionApplier] when an actuator command is refused
 * at apply time on the simulation thread (SP2c.18, Issue #841).
 *
 * ## Distinction from [RejectionCode]
 *
 * - [RejectionCode] codes are produced by [ActionValidator] on the **driver thread**
 *   (pre-queue rejection), against the stale snapshot the driver holds.
 * - [ApplyFailureCode] codes are produced by [DispatchDecisionApplier] on the **kDisco
 *   simulation thread**, against the live simulation state at the moment each command is
 *   drained from the queue.
 *
 * The distinction matters for metrics (SP2c.20): pre-queue rejections appear as
 * [ValidationVerdict.Rejected] with a [RejectionCode]; apply-time refusals appear as
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Approved] with `admitted = false` and a non-null
 * [ApplyFailureCode] reason.
 *
 * @since Issue #841 (SP2c.18 — Goal 10 apply-time cap enforcement)
 */
enum class ApplyFailureCode {
	/**
	 * The station concurrent-train capacity was already full when the `approve_train`
	 * command was applied on the simulation thread.
	 *
	 * ## Why this can happen even when the pre-queue check passed
	 *
	 * [ActionValidator] checks the cap against [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.activeCount],
	 * which is a snapshot captured at the start of the previous tick. Two `approve_train`
	 * commands queued in the same driver cycle both observe the same pre-tick count; [ActionValidator]
	 * may pass both if one slot appears free. [DispatchDecisionApplier] then enforces the cap
	 * in FIFO order on the sim thread: the first command is admitted (count incremented
	 * in-flight), and the second receives [CAP_EXCEEDED] because the live count now equals
	 * the maximum.
	 *
	 * The [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Approved] carrying this code is published through the
	 * SP2c.17 channel and surfaces in the next tick's `applied_outcomes` block so the agent
	 * learns the admission did not take effect.
	 */
	CAP_EXCEEDED
}
