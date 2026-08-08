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

import cz.vutbr.fit.interlockSim.sim.DispatchObservation

/**
 * Finds the one forward route request that would help a given active train this cycle, or
 * classifies why none applies (Issue #893, phase beta, task B1 — the observation half of the
 * alpha/beta fix).
 *
 * ## Precondition rule (agent-architect B1; byte-for-byte the rule-based dispatcher)
 *
 * Mirrors [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.reserveOrDefer]'s own eligibility
 * check exactly, so the model is never pointed at a route the rule-based dispatcher itself would
 * refuse to even consider: the first input, in deterministic list order
 * ([DispatchObservation.innerBlockInputs] then [DispatchObservation.outerBlockInputs]), owned by
 * the train, not already extended beyond, either approached by or reserved toward that train, and
 * carrying a computed FREE next separator.
 *
 * ## No cross-train visibility (scope disclosure)
 *
 * [resolve] mirrors `reserveOrDefer`'s **per-input** predicate only, not
 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkAllInputs]'s same-tick
 * `claimedSeparators` dedup across trains: each train's [DispatchObservation] is resolved in
 * isolation, with no notion of what any other train's line this same cycle names. Two different
 * active trains can therefore both be shown `NEXT SECTION ... to "X"` for the same separator `X`
 * in one cycle's message, even though the interlocking would only ever grant one of them. This is
 * bounded and self-healing, not a correctness gap in what actually gets reserved: the atomic
 * `PathReservationService` arbitrates the two requests independently of what this resolver printed,
 * and the loser's refusal reaches the model as an apply-failure in the next cycle's OUTCOMES block
 * (task B0). Callers must not assume the target named here is unique across trains within a single
 * cycle — only that it is the correct next hop for *this* train in isolation.
 *
 * @since Issue #893 (phase beta, task B1)
 */
object NextHopResolver {
	/**
	 * Resolves [trainId]'s next-hop status from [observation].
	 *
	 * Pure and deterministic: the same [trainId]/[observation] pair always returns an
	 * `==`-equal [NextHopOutcome], and neither argument is mutated.
	 */
	fun resolve(
		trainId: String,
		observation: DispatchObservation
	): NextHopOutcome {
		val inputs = observation.innerBlockInputs + observation.outerBlockInputs
		val hop =
			inputs.firstOrNull { input ->
				input.ownerTrainId == trainId &&
					!input.pathAlreadyExtendedBeyond &&
					(input.isApproachingThisInput || input.pathSetUpTowardThisInput) &&
					input.toSeparatorName != null
			}
		if (hop != null) {
			return NextHopOutcome.Hop(
				fromSignalName = hop.towardSemaphoreName,
				toSeparatorName = requireNotNull(hop.toSeparatorName)
			)
		}
		val owned = inputs.filter { it.ownerTrainId == trainId }
		return if (owned.isNotEmpty() && owned.all { it.pathAlreadyExtendedBeyond }) {
			NextHopOutcome.RouteAlreadySet
		} else {
			NextHopOutcome.NoSectionReservable
		}
	}
}

/**
 * Outcome of [NextHopResolver.resolve] for one train: the single route request that would help it
 * this cycle, or why none applies. Rendered exhaustively by
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl] — the compiler enforces
 * coverage there the same way it does for
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome].
 *
 * @since Issue #893 (phase beta, task B1)
 */
sealed interface NextHopOutcome {
	/**
	 * A qualifying forward reservation exists: request a route from [fromSignalName] to
	 * [toSeparatorName]. Both are signal or InOut names — legal `request_route` endpoints — never
	 * a block id.
	 */
	data class Hop(
		val fromSignalName: String,
		val toSeparatorName: String
	) : NextHopOutcome

	/** Every input this train owns already has its path extended beyond it; nothing to request. */
	data object RouteAlreadySet : NextHopOutcome

	/**
	 * No owned input has a computed FREE next separator (or the train owns no input at all).
	 * This is never evidence that the track ahead is occupied or blocked — see
	 * [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.toSeparatorName]'s own contract — so
	 * rendered wording for this outcome must not use either word.
	 */
	data object NoSectionReservable : NextHopOutcome
}
