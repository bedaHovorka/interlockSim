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
 * A separate object rather than a [RuleBasedDispatcher] member because every dispatcher —
 * including `:dispatcher-agent`'s `NextHopResolver`, which tells an LLM which hop the
 * rule-based arm would make — must reach its target through this one policy (Issue #1152).
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

/**
 * The name this input's reservation would be made to, or `null` when no candidate is available —
 * [ReservationTargetPolicy.pick] applied to [BlockInputObservation.candidateTargets], resolved
 * from the same list [RuleBasedDispatcher.reserveOrDefer] reads, so the two arms cannot disagree
 * about which separator a given input leads to (Issue #1152).
 *
 * Every dispatcher resolves its target through this seam. Selecting from the raw candidate list
 * instead is a second opinion about which route to set, and the
 * `TargetSelectionSingleEntryTest` allow-list rejects that spelling outright.
 *
 * @since Issue #1152 (SP5 — Goal 1B)
 */
fun BlockInputObservation.chosenTargetName(): String? = ReservationTargetPolicy.pick(candidateTargets)?.name
