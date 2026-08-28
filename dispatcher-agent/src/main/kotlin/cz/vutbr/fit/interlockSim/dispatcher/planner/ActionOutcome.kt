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

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode

/**
 * Complete outcome record for one [DispatchAction][cz.vutbr.fit.interlockSim.dispatcher.DispatchAction]
 * from emission through the full validate-and-apply pipeline (SP2c.20, Issue #843).
 *
 * Combines the lifecycle [phase] with the optional [rejection] or [applyFailure] code and
 * the full [authored] metadata so any downstream consumer (metrics, logging, replay) has
 * everything it needs without looking back at per-tick state.
 *
 * ## Phase semantics
 *
 * | [phase] | [rejection] | [applyFailure] | Meaning |
 * |---|---|---|---|
 * | [ActionPhase.EMITTED] | `null` | `null` | Action entered the pipeline; not yet validated. |
 * | [ActionPhase.REJECTED_BY_VALIDATOR] | non-null | `null` | Validator rejected before queue post. |
 * | [ActionPhase.APPLIED] | `null` | `null` | Applied on sim thread, no apply-time failure. |
 * | [ActionPhase.APPLIED_THEN_FAILED] | `null` | non-null | Applied, but sim refused the request. |
 *
 * ## `ALL_PATHS_BLOCKED` is `APPLIED_THEN_FAILED`, not `REJECTED_BY_VALIDATOR`
 *
 * See [ActionPhase] for the rationale. The [ApplyFailureCode.ALL_PATHS_BLOCKED] code must
 * **not** be counted in the invalid-output rate (SP2c.20 gate); it is ordinary contention.
 *
 * @property phase Lifecycle stage of this action when the outcome was recorded.
 * @property rejection Non-null when [phase] is [ActionPhase.REJECTED_BY_VALIDATOR]. Carries the
 *   machine-readable pre-queue rejection reason from
 *   [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator].
 * @property applyFailure Non-null when [phase] is [ActionPhase.APPLIED_THEN_FAILED]. Carries the
 *   machine-readable apply-time refusal reason from
 *   [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
 * @property authored Full attribution metadata — who made the decision, why, what kind, which tick.
 *
 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
 */
data class ActionOutcome(
	val phase: ActionPhase,
	val rejection: RejectionCode?,
	val applyFailure: ApplyFailureCode?,
	val authored: AuthoredAction
) {
	init {
		require(rejection == null || phase == ActionPhase.REJECTED_BY_VALIDATOR) {
			"rejection code must only be set for REJECTED_BY_VALIDATOR phase, but phase=$phase"
		}
		require(applyFailure == null || phase == ActionPhase.APPLIED_THEN_FAILED) {
			"applyFailure code must only be set for APPLIED_THEN_FAILED phase, but phase=$phase"
		}
		require(!(rejection != null && applyFailure != null)) {
			"rejection and applyFailure cannot both be non-null"
		}
	}
}
