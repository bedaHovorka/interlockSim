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

import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
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
 * ## Same-tick same-target dedup lives in [resolveAll], not [resolve] (Issue #834, SP2c.11, task 8)
 *
 * [resolve] mirrors `reserveOrDefer`'s **per-input** predicate only and stays that way
 * deliberately: it is documented and tested as a *pure, per-train, in-isolation* function (see
 * [NextHopResolverTest][cz.vutbr.fit.interlockSim.dispatcher.agents.NextHopResolverTest]), with
 * no notion of what any other train's line this same cycle names. Calling it once per train, as
 * the original implementation did, let two different active trains both be shown
 * `NEXT SECTION ... to "X"` for the same separator `X` in one cycle's message — a request the
 * interlocking would only ever grant one of. That was bounded and self-healing for
 * *correctness* (the atomic `PathReservationService` still arbitrates the two requests, and the
 * loser's refusal reaches the model as an apply-failure in the next cycle's OUTCOMES block, task
 * B0), but not for *measurement*: the resulting apply-failure inflates `applyFailuresByCode` in
 * proportion to how many trains are concurrently active — the very parameter #834's sweep ranks
 * cells on — and burns part of the per-tick action budget on a request guaranteed to fail.
 *
 * [resolveAll] is the cycle-scoped entry point added to close that gap: it applies
 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkAllInputs]'s own `claimedSeparators`
 * dedup — the same fixed evaluation order over
 * [DispatchObservation.innerBlockInputs]`+`[DispatchObservation.outerBlockInputs] — so the two
 * arms can never disagree about which train gets a given separator this tick. Adding this as a
 * second, batch-oriented entry point rather than making [resolve] itself stateful (e.g. a hidden
 * mutable `claimedSeparators` field on the object) keeps [resolve] pure and its existing
 * single-train tests valid unchanged, while [resolveAll] stays equally pure and deterministic in
 * its own right: same `trainIds`/`observation` pair ⇒ same output map, no mutable state carried
 * between calls (constraint P8, reproducibility by construction).
 *
 * Callers rendering more than one active train's line in the same cycle message must use
 * [resolveAll], not repeated [resolve] calls, or the dedup this class exists to provide is
 * silently bypassed.
 *
 * @since Issue #893 (phase beta, task B1); [resolveAll] added Issue #834 (SP2c.11, task 8)
 */
object NextHopResolver {
	/**
	 * Resolves [trainId]'s next-hop status from [observation] alone, with no visibility into any
	 * other train.
	 *
	 * Pure and deterministic: the same [trainId]/[observation] pair always returns an
	 * `==`-equal [NextHopOutcome], and neither argument is mutated.
	 *
	 * Rendering more than one active train's line in the same cycle message? Use [resolveAll]
	 * instead — this function alone cannot see that two trains might race for the same separator.
	 */
	fun resolve(
		trainId: String,
		observation: DispatchObservation
	): NextHopOutcome {
		val inputs = observation.innerBlockInputs + observation.outerBlockInputs
		val hop = firstEligibleInput(inputs, trainId)
		if (hop != null) {
			return NextHopOutcome.Hop(
				fromSignalName = hop.towardSemaphoreName,
				toSeparatorName = requireNotNull(hop.toSeparatorName)
			)
		}
		return fallbackOutcome(inputs, trainId)
	}

	/**
	 * Resolves next-hop outcomes for every id in [trainIds] from the single shared [observation],
	 * applying the same-tick same-target dedup [resolve] alone cannot provide (see this object's
	 * KDoc). A separator that would qualify as more than one requested train's [NextHopOutcome.Hop]
	 * target is granted to exactly one of them — the winner determined by
	 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkAllInputs]'s own fixed evaluation
	 * order over [DispatchObservation.innerBlockInputs]`+`[DispatchObservation.outerBlockInputs],
	 * so the rule-based and LLM arms can never disagree about who gets a given separator this tick.
	 * Every other requested train keeps exactly the outcome [resolve] would have given it alone.
	 *
	 * Pure and deterministic: the same `trainIds`/[observation] pair always returns an `==`-equal
	 * result map, and neither argument is mutated.
	 *
	 * @return A map with exactly one entry per (deduplicated) id in [trainIds].
	 */
	fun resolveAll(
		trainIds: List<String>,
		observation: DispatchObservation
	): Map<String, NextHopOutcome> {
		val inputs = observation.innerBlockInputs + observation.outerBlockInputs
		val claimedSeparators = mutableSetOf<String>()
		val decidedTrains = mutableSetOf<String>()
		val hopByTrain = mutableMapOf<String, NextHopOutcome.Hop>()
		val claimedAwayByTrain = mutableMapOf<String, String>()

		for (candidate in inputs) {
			val ownerId = candidate.ownerTrainId ?: continue
			if (ownerId in decidedTrains) continue
			if (!isEligible(candidate)) continue
			decidedTrains += ownerId
			val target = requireNotNull(candidate.toSeparatorName)
			if (claimedSeparators.add(target)) {
				hopByTrain[ownerId] =
					NextHopOutcome.Hop(fromSignalName = candidate.towardSemaphoreName, toSeparatorName = target)
			} else {
				claimedAwayByTrain[ownerId] = target
			}
		}

		return trainIds.associateWith { trainId ->
			hopByTrain[trainId]
				?: claimedAwayByTrain[trainId]?.let { NextHopOutcome.ClaimedByAnotherTrain(toSeparatorName = it) }
				?: fallbackOutcome(inputs, trainId)
		}
	}

	/** `true` when [input] qualifies as a forward-reservation candidate for its owner. */
	private fun isEligible(input: BlockInputObservation): Boolean =
		!input.pathAlreadyExtendedBeyond &&
			(input.isApproachingThisInput || input.pathSetUpTowardThisInput) &&
			input.toSeparatorName != null

	/** The first (list-order) input owned by [trainId] and qualifying per [isEligible], or `null`. */
	private fun firstEligibleInput(
		inputs: List<BlockInputObservation>,
		trainId: String
	): BlockInputObservation? = inputs.firstOrNull { it.ownerTrainId == trainId && isEligible(it) }

	/**
	 * The outcome for a train with no [NextHopOutcome.Hop] this cycle (never claimed by an
	 * eligible input): [NextHopOutcome.RouteAlreadySet] when every owned input already extends
	 * beyond it, [NextHopOutcome.NoSectionReservable] otherwise.
	 */
	private fun fallbackOutcome(
		inputs: List<BlockInputObservation>,
		trainId: String
	): NextHopOutcome {
		val owned = inputs.filter { it.ownerTrainId == trainId }
		return if (owned.isNotEmpty() && owned.all { it.pathAlreadyExtendedBeyond }) {
			NextHopOutcome.RouteAlreadySet
		} else {
			NextHopOutcome.NoSectionReservable
		}
	}
}

/**
 * Outcome of [NextHopResolver.resolve]/[NextHopResolver.resolveAll] for one train: the single
 * route request that would help it this cycle, or why none applies. Rendered exhaustively by
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl] — the compiler enforces
 * coverage there the same way it does for
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome].
 *
 * @since Issue #893 (phase beta, task B1); [ClaimedByAnotherTrain] added Issue #834 (SP2c.11,
 *   task 8)
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

	/**
	 * [NextHopResolver.resolveAll]-only outcome: this train's own next-hop target,
	 * [toSeparatorName], qualified exactly like a [Hop] would, but another active train's
	 * eligible input claimed the same separator earlier in this same cycle's fixed evaluation
	 * order (same-tick same-target dedup, mirroring
	 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkAllInputs]'s `claimedSeparators`).
	 * [resolve] alone can never produce this outcome — it has no visibility into other trains.
	 *
	 * Like [NoSectionReservable], this is never evidence the track ahead is occupied or
	 * blocked: the section exists and is being reserved this very cycle, just for a different
	 * train. It is re-evaluated fresh next tick, by which point the winner's reservation has
	 * updated the topology and this train's own next FREE separator is likely to differ. Rendered
	 * wording for this outcome must not use "occupied"/"blocked" either, and — like every
	 * prompt-facing text in this codebase (constraint C9) — no "option"/"choose"/"select" and no
	 * numbered-list markers.
	 */
	data class ClaimedByAnotherTrain(
		val toSeparatorName: String
	) : NextHopOutcome
}
