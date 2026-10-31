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
 * 1. **The train's own exit** (Issue #1278): when a destination is given — the timetable's `out`
 *    InOut, [BlockInputObservation.destinationName] — and it is an available station-exit
 *    candidate, it wins.
 * 2. **Otherwise the fixed order:** station exits first, then signals; within one kind the
 *    interlocking's search order ([BlockInputObservation.candidateTargets] is already in that
 *    order); the first available one wins. The sort is stable and the interlocking already lists
 *    InOuts first, so on real data this is the first available candidate in list order —
 *    byte-identical to the pre-#970 `findNextReservationTarget` result.
 *
 * The destination only decides between candidates **one section ahead**: it lets a train whose
 * own exit is reachable from here take it rather than the nearest one (Červený Újezd has two
 * exits per side; on `vyhybna.xml`, one exit per side, the pick is the same either way). It does
 * not steer a train through the station toward its exit — the signal a train is routed to inside
 * the station is still the first available one, which can lead to a track whose only exit is the
 * other one; then the fallback takes that exit. A full destination-aware router remains Goal 19.
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
	 * @param destinationName The name of the station exit the train is timetabled to leave by, or
	 *   `null` for no preference (Issue #1278). Only an available [SeparatorKind.IN_OUT] candidate
	 *   of that name is preferred; a signal of the same name is not.
	 * @return The available exit named [destinationName] if there is one; otherwise the first
	 *   available candidate, station exits before signals; `null` when [candidates] is empty or
	 *   none is available.
	 */
	fun pick(
		candidates: List<CandidateTarget>,
		destinationName: String? = null
	): CandidateTarget? =
		destinationName?.let { destination ->
			candidates.firstOrNull { it.kind == SeparatorKind.IN_OUT && it.name == destination && it.available }
		} ?: candidates.sortedBy { it.kind.rank }.firstOrNull { it.available }

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
 * [ReservationTargetPolicy.pick] applied to [BlockInputObservation.candidateTargets] with the
 * input's [BlockInputObservation.destinationName] (Issue #1278), resolved
 * from the same list `RuleBasedDispatcher.reserveOrDefer` reads, so the two arms cannot disagree
 * about which separator a given input leads to (Issue #1152).
 *
 * Every dispatcher resolves its target through this seam. Selecting from the raw candidate list
 * instead is a second opinion about which route to set, and the
 * `TargetSelectionSingleEntryTest` allow-list rejects that spelling outright.
 *
 * @since Issue #1152 (SP5 — Goal 1B)
 */
fun BlockInputObservation.chosenTargetName(): String? =
	ReservationTargetPolicy.pick(candidateTargets, destinationName)?.name
