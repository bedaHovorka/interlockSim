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
 * 2. **Wait for the own exit** (Issue #1299): when it is a candidate but not available (another
 *    train's route holds the throat), nothing is picked — the train waits and is reconsidered next
 *    tick — for as long as the shell's [BlockInputObservation.destinationWaitSeconds] stays below
 *    [OWN_EXIT_WAIT_BOUND_SECONDS] of simulation time.
 * 3. **Otherwise the fixed order** (also once the wait reaches the bound, and whenever the shell
 *    reports no wait): station exits first, then signals; within one kind the
 *    interlocking's search order ([BlockInputObservation.candidateTargets] is already in that
 *    order); the first available one wins. The sort is stable and the interlocking already lists
 *    InOuts first, so on real data this is the first available candidate in list order —
 *    byte-identical to the pre-#970 `findNextReservationTarget` result.
 *
 * The destination only decides between candidates **one section ahead**: it lets a train whose
 * own exit is reachable from here take it rather than the nearest one (Červený Újezd has two
 * exits per side; on `vyhybna.xml`, one exit per side, the pick is the same either way). It does
 * not steer a train through the station toward its exit — the signal a train is routed to inside
 * the station is still the first available one — and its wait for the own exit is bounded: a train
 * still held out after [OWN_EXIT_WAIT_BOUND_SECONDS] leaves by the next available exit. A full
 * destination-aware router remains Goal 19.
 *
 * A separate object rather than a [RuleBasedDispatcher] member because every dispatcher —
 * including `:dispatcher-agent`'s `NextHopResolver`, which tells an LLM which hop the
 * rule-based arm would make — must reach its target through this one policy (Issue #1152).
 *
 * @since Issue #970
 */
object ReservationTargetPolicy {
	/**
	 * How long, in **simulation** seconds, a train holds out for its own exit while that exit is a
	 * candidate but not available (Issue #1299) before [pick] falls back to the fixed order. The
	 * wait is read from [BlockInputObservation.destinationWaitSeconds], which the shell measures; the
	 * bound keeps a holder that never releases (or a train that needs the waiting train's block to
	 * clear the throat) from holding the train up for ever — `ShuntingLoop` has no SP7 watchdog, so
	 * this bound is what breaks such a wait.
	 *
	 * **Value (proposed, awaiting traffic-simulation-expert confirmation):** 120 s, a clear margin
	 * above the measured time the holding train needs to clear the throat. Measured 2026-11-03 with
	 * an unbounded wait, sampled on the 2 s control tick: in the seeded `CervenyUjezdRunTest` run
	 * Train #2 waits at `e32` for `east_exit_1` while Train #1 clears the throat — 14 s reported,
	 * free 16 s after the wait began. Over eleven `cerveny_ujezd.xml` runs (600 s, seeds 0-9 and
	 * 1163, uncapped generator) the longest wait that ended within the run lasted 76 s (median about
	 * 14 s); with 120 s none of them reaches the bound, so every train leaves by its own exit.
	 * On `vyhybna.xml` (one exit per side) the wait never changes a pick.
	 */
	const val OWN_EXIT_WAIT_BOUND_SECONDS: Double = 120.0

	/**
	 * `true` when [destinationName] is listed among [candidates] as a station exit
	 * ([SeparatorKind.IN_OUT]) that is **not** available — the state in which [pick] holds out for
	 * it (Issue #1299). The shell starts and keeps its wait clock exactly while this holds, so the
	 * shell and the policy share one definition of "waiting for the own exit".
	 */
	fun isWaitingForOwnExit(
		candidates: List<CandidateTarget>,
		destinationName: String?
	): Boolean =
		destinationName != null &&
			candidates.any { it.kind == SeparatorKind.IN_OUT && it.name == destinationName && !it.available }

	/**
	 * Chooses the target to reserve toward from [candidates], or `null` when none is available
	 * (the train waits and is reconsidered next tick).
	 *
	 * @param candidates The evaluated next separators in the interlocking's search order.
	 * @param destinationName The name of the station exit the train is timetabled to leave by, or
	 *   `null` for no preference (Issue #1278). Only an available [SeparatorKind.IN_OUT] candidate
	 *   of that name is preferred; a signal of the same name is not.
	 * @param destinationWaitSeconds How long, in simulation seconds, the train has waited for
	 *   [destinationName] while it is a candidate but not available (Issue #1299), or `null` when
	 *   no wait is reported — then a blocked own exit falls back at once, as before #1299.
	 * @return The available exit named [destinationName] if there is one; `null` while the train
	 *   waits for that exit below [OWN_EXIT_WAIT_BOUND_SECONDS]; otherwise the first available
	 *   candidate, station exits before signals; `null` when [candidates] is empty or none is
	 *   available.
	 */
	fun pick(
		candidates: List<CandidateTarget>,
		destinationName: String? = null,
		destinationWaitSeconds: Double? = null
	): CandidateTarget? {
		val ownExit =
			destinationName?.let { destination ->
				candidates.firstOrNull { it.kind == SeparatorKind.IN_OUT && it.name == destination && it.available }
			}
		return when {
			ownExit != null -> ownExit
			holdsOutForOwnExit(candidates, destinationName, destinationWaitSeconds) -> null
			else -> candidates.sortedBy { it.kind.rank }.firstOrNull { it.available }
		}
	}

	/**
	 * Whether [pick] denies now so the train can wait for its blocked own exit (Issue #1299): the
	 * exit is a candidate but not available, the shell reports a wait, and that wait is still below
	 * [OWN_EXIT_WAIT_BOUND_SECONDS]. A `null` wait — an observation the shell did not measure —
	 * keeps the immediate #1278 fallback.
	 */
	private fun holdsOutForOwnExit(
		candidates: List<CandidateTarget>,
		destinationName: String?,
		destinationWaitSeconds: Double?
	): Boolean =
		destinationWaitSeconds != null &&
			destinationWaitSeconds < OWN_EXIT_WAIT_BOUND_SECONDS &&
			isWaitingForOwnExit(candidates, destinationName)

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
 * input's [BlockInputObservation.destinationName] (Issue #1278) and
 * [BlockInputObservation.destinationWaitSeconds] (Issue #1299), resolved
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
	ReservationTargetPolicy.pick(candidateTargets, destinationName, destinationWaitSeconds)?.name
