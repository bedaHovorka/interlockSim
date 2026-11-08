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

import cz.vutbr.fit.interlockSim.dispatcher.agents.NextHopResolver
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.chosenTargetName

/**
 * The next-section rule for a section-hop (Signal) route target (Issue #1378): a train standing at
 * or approaching signal `from` may only be routed to a separator the interlocking lists **one
 * section ahead** of `from` for that train.
 *
 * ## Why
 *
 * The Praha LLM arm asked `request_route Train #2 from=S9 to=S15` five times in a row, although the
 * train stood at `S9` with its own exit `N-Vys-1` free one section ahead. `S15` is the signal at the
 * other end of the same platform track — behind the train. The engine granted the one-block
 * "route" onto the block the train already occupied, and the train never moved again. Signal
 * targets had been left alone by the tool (only InOut targets were checked, #944), so nothing on
 * the live path refused it.
 *
 * ## The rule
 *
 * The facts come from [BlockInputObservation.candidateTargets] — the very list the NEXT SECTION line
 * of the prompt (`NextHopResolver`) and the rule-based dispatcher read through
 * [cz.vutbr.fit.interlockSim.sim.ReservationTargetPolicy]. The inputs considered are those owned by
 * the train, facing `from`, with a non-empty candidate list. When `to` is among their candidates —
 * available or not; an unavailable one is the engine's to answer — the request passes. Otherwise it
 * is a [Mismatch]: `to` is behind the train, sideways, or more than one section away.
 *
 * The rule is **inert** whenever there is no such input — the empty list means *no comparison is
 * possible* (a look-ahead request with no owned input toward `from`, a path already extended
 * beyond it, a queued train, an empty snapshot before the first capture), never *every target is
 * wrong*.
 *
 * ## The suggested pick
 *
 * The pick follows the NEXT SECTION eligibility rule ([NextHopResolver.isEligible]) but not
 * [NextHopResolver.resolveAll]'s same-tick dedup, so it may name a hop the prompt shows as
 * ClaimedByAnotherTrain this tick. That costs one turn, not a stall: the engine answers the
 * follow-up request, and the refusal comes back as an outcome the model can act on.
 *
 * One shared helper so the live tool (`RequestRouteTool`) and the reference validator
 * ([ActionValidator]) cannot drift apart.
 *
 * Reading `candidateTargets` here is allow-listed in `:core`'s `TargetSelectionSingleEntryTest`
 * (`ALLOWED_CANDIDATE_TARGET_FILES`) as a membership check only: this object never selects a target.
 */
object NextSectionTargets {
	/**
	 * A refused next-section target.
	 *
	 * @property from The signal the request starts at.
	 * @property pick The NEXT SECTION target for [from]: [chosenTargetName] of the first input that
	 *   is eligible by [NextHopResolver.isEligible] — the same predicate the prompt's NEXT SECTION
	 *   line uses. `null` when no input is eligible, including the Issue #1244 case of a train
	 *   awaiting a route extension whose pick is the end of the route it already holds (naming that
	 *   end would re-grant the held route).
	 * @property ahead Every separator listed one section ahead of [from], sorted.
	 */
	data class Mismatch(
		val from: String,
		val pick: String?,
		val ahead: List<String>
	)

	/**
	 * Checks [to] against the separators listed one section ahead of [from] for [trainId].
	 *
	 * @return `null` when [to] is listed, or when no input of [trainId] facing [from] carries a
	 *   candidate list (inert); a [Mismatch] otherwise.
	 */
	fun mismatch(
		inputs: List<BlockInputObservation>,
		trainId: String,
		from: String,
		to: String
	): Mismatch? {
		val matching =
			inputs.filter {
				it.ownerTrainId == trainId && it.towardSemaphoreName == from && it.candidateTargets.isNotEmpty()
			}
		if (matching.isEmpty()) return null
		val allowed = matching.flatMap { it.candidateTargets }.map { it.name }.toSet()
		if (to in allowed) return null
		return Mismatch(
			from = from,
			pick = matching.firstOrNull { NextHopResolver.isEligible(it) }?.chosenTargetName(),
			ahead = allowed.sorted()
		)
	}

	/** The model-facing rejection text for [mismatch], shared verbatim by the tool and the validator. */
	fun rejectionMessage(
		trainId: String,
		to: String,
		mismatch: Mismatch
	): String {
		val from = mismatch.from
		val head =
			"Train '$trainId' cannot go from '$from' to '$to': '$to' is not the next section ahead of the " +
				"train at '$from'."
		val pick = mismatch.pick
		return if (pick != null) {
			"$head Its NEXT SECTION is from '$from' to '$pick'. Request exactly fromEndpointName='$from', " +
				"toEndpointName='$pick'. Do not retry '$to'."
		} else {
			"$head No section ahead of '$from' can be reserved right now (sections ahead: " +
				mismatch.ahead.joinToString(", ") { "'$it'" } +
				"). Make no route request for this train this tick."
		}
	}
}
