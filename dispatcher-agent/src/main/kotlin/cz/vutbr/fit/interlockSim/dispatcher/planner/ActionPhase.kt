/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

/**
 * Lifecycle phase of one [DispatchAction][cz.vutbr.fit.interlockSim.dispatcher.DispatchAction]
 * from emission through validation to final application on the simulation thread (SP2c.20,
 * Issue #843).
 *
 * | Phase | Meaning |
 * |---|---|
 * | [EMITTED] | The action was produced by the emission strategy and entered the validate-and-act pipeline. |
 * | [REJECTED_BY_VALIDATOR] | [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] rejected the action; it was never posted to the actuator queue. |
 * | [APPLIED] | The action was accepted by the validator, posted to the queue, and applied on the simulation thread (including any apply-time failure such as `ALL_PATHS_BLOCKED` or `CAP_EXCEEDED_APPLY` — the command was applied even if it did not produce the desired result). |
 * | [APPLIED_THEN_FAILED] | The action was applied but the simulation refused the request (e.g. `ALL_PATHS_BLOCKED`). [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode] carries the specific reason. |
 *
 * ## `ALL_PATHS_BLOCKED` is `APPLIED_THEN_FAILED`, not `REJECTED_BY_VALIDATOR`
 *
 * A `request_route` that reaches the simulation thread but finds all paths physically occupied
 * was correctly validated (the validator cannot run pathfinding, see
 * [cz.vutbr.fit.interlockSim.dispatcher.RejectionCode.NO_FREE_PATH]). It is ordinary network
 * contention — reporting it as an invalid-output error would make a correctly-cautious agent
 * appear broken. [APPLIED_THEN_FAILED] with [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.ALL_PATHS_BLOCKED]
 * is the correct phase/code pair for this outcome, and it is excluded from the invalid-output
 * rate (SP2c.20 gate).
 *
 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
 */
enum class ActionPhase {
	/**
	 * The action was produced by the emission strategy and entered the validate-and-act pipeline.
	 * All actions start in this phase before the validator runs.
	 */
	EMITTED,

	/**
	 * The action was rejected by [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] before
	 * being posted to the actuator queue. [cz.vutbr.fit.interlockSim.dispatcher.RejectionCode]
	 * carries the specific pre-execution reason.
	 */
	REJECTED_BY_VALIDATOR,

	/**
	 * The action passed validation and was applied on the simulation thread. This includes
	 * cases where the application returned a non-blocking contention result such as
	 * [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode.ALL_PATHS_BLOCKED] — the command
	 * reached the sim thread and was processed; the contention is not an LLM failure.
	 *
	 * For actions with a non-null [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode],
	 * see [APPLIED_THEN_FAILED].
	 */
	APPLIED,

	/**
	 * The action was applied on the simulation thread but the simulation refused the request
	 * (e.g. all paths were blocked, or the concurrent-train cap was already reached at apply
	 * time). [cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode] carries the specific
	 * apply-time reason. The action reached the sim thread; this is not a validator rejection
	 * and must not count toward the invalid-output rate.
	 */
	APPLIED_THEN_FAILED
}
