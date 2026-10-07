/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Holds the FREE/RESERVED/OCCUPIED state of a dynamic track wrapper and performs its guarded
 * transitions.
 *
 * @since Issue #1123 — replaces the near-identical private `stateChange` / `errorStateMessage`
 *   pairs in [DynamicTrack] and [DynamicTrackBlock]
 */
internal class TrackStateHolder {
	/** The current state; starts as [TrackFacility.State.FREE]. */
	var current: TrackFacility.State = TrackFacility.State.FREE
		private set

	/**
	 * Moves to [to] if the current state is [from].
	 *
	 * @return `true` if the transition happened, `false` (state unchanged) otherwise
	 */
	fun transition(
		from: TrackFacility.State,
		to: TrackFacility.State
	): Boolean {
		val ok = current == from
		if (ok) current = to
		return ok
	}

	/** The message for a failed transition that expected [expected]. */
	fun wrongStateMessage(expected: TrackFacility.State): String = "Wrong state: $current , expected : $expected"
}
