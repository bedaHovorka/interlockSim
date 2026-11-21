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
 * available or not; an unavailable one is the engine's to answer — the request passes. Otherwise the
 * request is refused: `to` is behind the train, sideways, or more than one section away, and the
 * refusal text names the NEXT SECTION to request instead.
 *
 * The rule is **inert** whenever there is no such input — the empty list means *no comparison is
 * possible* (a look-ahead request with no owned input toward `from`, a path extended beyond the
 * signal without awaiting an extension, a queued train, an empty snapshot before the first
 * capture), never *every target is wrong*.
 *
 * ## The suggested pick
 *
 * The pick is [chosenTargetName] of the first input that is eligible by
 * [NextHopResolver.isEligible] — the single NEXT SECTION eligibility predicate, the same one the
 * prompt's NEXT SECTION line uses. It is `null` when no input is eligible, including the Issue
 * #1244 case of a train awaiting a route extension whose pick is the end of the route it already
 * holds (naming that end would re-grant the held route). The pick follows that predicate but not
 * [NextHopResolver.resolveAll]'s same-tick dedup, so it may name a hop the prompt shows as
 * ClaimedByAnotherTrain this tick. That costs one turn, not a stall: the engine answers the
 * follow-up request, and the refusal comes back as an outcome the model can act on.
 *
 * One shared entry point so the live tool (`RequestRouteTool`) and the reference validator
 * ([ActionValidator]) cannot drift apart — both emit the text of [refusal] verbatim.
 *
 * Reading `candidateTargets` here is allow-listed in `:core`'s `TargetSelectionSingleEntryTest`
 * (`ALLOWED_CANDIDATE_TARGET_FILES`) as a membership check only: this object never selects a target.
 */
object NextSectionTargets {
	/**
	 * The model-facing refusal for a refused Signal target, or `null` when the request passes or the
	 * rule is inert. The two layers emit this text verbatim, so they can no longer drift apart.
	 *
	 * @param inner Inner block inputs; searched before [outer] — the fixed order the shell produces
	 *   them in, owned here so a future caller cannot flip it.
	 * @param outer Outer block inputs.
	 * @return The refusal text, or `null` when [to] is listed one section ahead of [from] for
	 *   [trainId], or when no input of [trainId] facing [from] carries a candidate list (inert).
	 */
	fun refusal(
		inner: List<BlockInputObservation>,
		outer: List<BlockInputObservation>,
		trainId: String,
		from: String,
		to: String
	): String? {
		val matching =
			(inner + outer).filter {
				it.ownerTrainId == trainId && it.towardSemaphoreName == from && it.candidateTargets.isNotEmpty()
			}
		if (matching.isEmpty()) return null
		val ahead = matching.flatMap { it.candidateTargets }.map { it.name }.toSet()
		if (to in ahead) return null
		val pick = matching.firstOrNull { NextHopResolver.isEligible(it) }?.chosenTargetName()
		return message(trainId, from, to, pick = pick, ahead = ahead.sorted())
	}

	/**
	 * Builds the refusal text. With a [pick], the head plus the exact pair to request instead and
	 * the no-retry instruction; without one, the head plus the no-request instruction over the
	 * sorted sections ahead.
	 */
	private fun message(
		trainId: String,
		from: String,
		to: String,
		pick: String?,
		ahead: List<String>
	): String {
		val head =
			"Train '$trainId' cannot go from '$from' to '$to': '$to' is not the next section ahead of the " +
				"train at '$from'."
		return if (pick != null) {
			"$head Its NEXT SECTION is from '$from' to '$pick'. Request exactly fromEndpointName='$from', " +
				"toEndpointName='$pick'. Do not retry '$to'."
		} else {
			"$head No section ahead of '$from' can be reserved right now (sections ahead: " +
				ahead.joinToString(", ") { "'$it'" } +
				"). Make no route request for this train this tick."
		}
	}
}
