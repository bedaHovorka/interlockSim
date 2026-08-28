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

/**
 * Releases the un-travelled **tail** of a route while leaving the part the train occupies intact.
 *
 * ## Why a whole-route release cannot do this (Issue #847 round 4, finding R4-3)
 *
 * `NetworkActuatorPort.releaseRoute` is train-scoped and all-or-nothing: it ends in
 * `PathReservationService.releasePath`, whose `finally` runs `registry.unregister(trainId)` and
 * drops **every** block registered to the train — including the one it is physically standing on.
 * Sweeping a train that occupies part of its route would therefore mark an occupied block free and
 * let the next `request_route` route another train straight into it.
 *
 * That is exactly why [OrphanReservationSweeper] refuses to touch a train holding any occupied
 * block, and equally why it could not reclaim the case round 3 measured most often: a train stopped
 * *on* a block while holding the next one RESERVED and empty ahead of it. Run 1 ended with
 * `Train #1` occupying `kB` and holding `kA`; 301 sweeps reclaimed nothing.
 *
 * ## Contract
 *
 * An implementation must release **only** blocks the train does not occupy, must leave the
 * occupied block registered to it, and must never leave a released block reachable through a
 * permissive signal. It is free to refuse — returning fewer ids than it was offered, or none — and
 * the sweeper counts only what actually came back.
 *
 * @since Issue #847 round 4 (PR #891)
 */
fun interface PartialRouteReleaser {
	/**
	 * Releases as much of [blockIds] as can be released safely for [trainId].
	 *
	 * @param trainId Owner of the reservation.
	 * @param blockIds Blocks the sweeper believes are reserved-but-un-travelled. Advisory: the
	 *   implementation re-checks live state and may release fewer.
	 * @return the ids actually released, in any order. Empty if nothing could be.
	 */
	fun releaseUntravelledTail(
		trainId: String,
		blockIds: List<String>
	): List<String>
}
