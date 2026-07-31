/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.ActionValidator
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation

/**
 * Projects [ActionValidator] verdicts over the candidate set from
 * [ActionCandidateEnumerator] into a flat [List]<[Affordance]> (SP2c.4, Issue #827).
 *
 * ## Zero predicate logic
 *
 * This class contains **no predicate logic of its own**. Every [Affordance.applicable] flag
 * and every [Affordance.reason] string is produced by delegating to [validator] verbatim.
 * There is no second implementation of any predicate, so the risk of the annotation drifting
 * from the validator (RC4) is closed **by construction** — the consistency property test
 * in `AffordanceAnnotatorTest` documents this and will start failing the instant someone
 * reimplements a predicate inside this class.
 *
 * ## Goal 9 C7 ruling — option (a)
 *
 * When a [ConflictHintLatch] is wired, blocked-train affordance reasons are augmented with
 * the latched conflict hint (e.g. `"blocked — … (blocked at kB by T-3)"`). The hint is
 * derived from [ConflictDetectedEvent][cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent]
 * events, not from any actuator or conflict-resolution path — Goal 9 becomes better-worded
 * perception. See [ConflictHintLatch] for latch lifecycle.
 *
 * ## Output contract
 *
 * - No numbering, no selection semantics, no ordering by preference (C9 compliance).
 * - [Affordance.NO_OP] is always the **last** entry, regardless of other train state.
 * - `approve_train` entries appear before `request_route`/`cancel_route` pairs, matching
 *   [ActionCandidateEnumerator.enumerate] order.
 *
 * @param validator     Pure validator; called once per candidate — never mutated.
 * @param candidates    Candidate enumerator; called once per [annotate] call.
 * @param conflictHintLatch Optional latch that augments rejected `request_route` reasons
 *   with a conflict hint. Pass `null` (default) to disable hint augmentation.
 *
 * @since Issue #827 (SP2c.4 — Goal 10)
 */
class AffordanceAnnotator(
	private val validator: ActionValidator,
	private val candidates: ActionCandidateEnumerator,
	private val conflictHintLatch: ConflictHintLatch? = null
) {
	/**
	 * Returns the annotated affordance list for [observation].
	 *
	 * Steps:
	 * 1. Clear stale conflict hints for trains that are no longer HELD.
	 * 2. Enumerate candidates via [ActionCandidateEnumerator.enumerate].
	 * 3. Validate each candidate via [ActionValidator.validate], store verdict verbatim.
	 * 4. Append [Affordance.NO_OP] as the final entry.
	 */
	fun annotate(observation: DispatcherObservation): List<Affordance> {
		conflictHintLatch?.updateFromObservation(observation)

		val affordances =
			candidates
				.enumerate(observation)
				.map { action -> toAffordance(action, validator.validate(action, observation)) }
				.toMutableList()

		affordances += Affordance.NO_OP
		return affordances
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	/** Maps one (action, verdict) pair to an [Affordance] with no predicate logic. */
	private fun toAffordance(
		action: DispatchAction,
		verdict: ValidationVerdict
	): Affordance {
		val trainId = action.subjectTrainId() ?: Affordance.NO_OP_TRAIN_ID
		val applicable = verdict is ValidationVerdict.Valid
		val baseReason =
			when (verdict) {
				is ValidationVerdict.Valid -> "applicable"
				is ValidationVerdict.Rejected -> "blocked — ${verdict.detail}"
			}
		// Augment rejected request_route reasons with the latched conflict hint (Goal 9 C7 option (a)).
		val reason =
			if (!applicable && action is DispatchAction.RequestRoute) {
				val hint = conflictHintLatch?.getHint(action.trainId)
				if (hint != null) "$baseReason ($hint)" else baseReason
			} else {
				baseReason
			}

		return Affordance(trainId, action.kind, applicable, reason)
	}

	/** Returns the train ID this action targets, or `null` for [DispatchAction.NoOp]. */
	private fun DispatchAction.subjectTrainId(): String? =
		when (this) {
			is DispatchAction.ApproveTrain -> trainId
			is DispatchAction.RequestRoute -> trainId
			is DispatchAction.CancelRoute -> trainId
			is DispatchAction.NoOp -> null
		}
}
