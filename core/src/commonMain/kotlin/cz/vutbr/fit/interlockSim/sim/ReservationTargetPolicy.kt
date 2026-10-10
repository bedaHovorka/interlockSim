/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

/**
 * The dispatcher's choice of forward-reservation target among the candidates the shell reports
 * (Issue #970): the route-selection act that [RuleBasedDispatcher] used to delegate to the shell.
 *
 * ## Policy
 * Station exits first, then signals; within one kind the interlocking's search order
 * ([BlockInputObservation.candidateTargets] is already in that order); the first available one
 * wins. No timetable, no destination: the nearest exit wins whenever a branch terminating at an
 * InOut competes with one continuing into the station — a placeholder until a destination-aware
 * policy (Goal 19) replaces it. The sort is stable and the interlocking already lists InOuts
 * first, so on real data the pick is the first available candidate in list order — byte-identical
 * to the pre-#970 `findNextReservationTarget` result.
 *
 * A separate object rather than a [RuleBasedDispatcher] member because the shell
 * ([ShuntingLoop]) applies the same policy to fill the [BlockInputObservation.toSeparatorName]
 * compatibility projection, and the shell must not depend on a dispatcher implementation.
 *
 * @since Issue #970
 */
object ReservationTargetPolicy {
	/**
	 * Chooses the target to reserve toward from [candidates], or `null` when none is available
	 * (the train waits and is reconsidered next tick).
	 *
	 * @param candidates The evaluated next separators in the interlocking's search order.
	 * @return The first available candidate, station exits before signals; `null` when
	 *   [candidates] is empty or none is available.
	 */
	fun pick(candidates: List<CandidateTarget>): CandidateTarget? =
		candidates.sortedBy { it.kind.rank }.firstOrNull { it.available }

	/** Sort key for [pick]: exits first. Explicit so the order does not hang on enum declaration order. */
	private val SeparatorKind.rank: Int
		get() =
			when (this) {
				SeparatorKind.IN_OUT -> 0
				SeparatorKind.SEMAPHORE -> 1
			}
}
