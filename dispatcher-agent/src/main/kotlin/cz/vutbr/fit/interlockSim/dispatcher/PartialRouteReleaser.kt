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
 * ## Approach locking (Issue #1025)
 *
 * A train that has just read a proceed aspect is physically inside the next block before it
 * books it (`Train.Front.semaphoreAction` sleeps `hold(1.0)` between the signal and
 * `DynamicTrackBlock.enter`). Freeing that block in the same call as the signal drop kills the
 * train at `enter` with `Wrong state: FREE , expected : RESERVED`. So when a proceed aspect
 * stands at the boundary between the occupied head and the tail, an implementation must drop the
 * signal to STOP and **defer** the physical release to a later call, reporting
 * [TailRelease.deferred] so the caller retries instead of restarting its staleness clock.
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
	 * @return the ids actually released, and whether the release was deferred by approach locking.
	 */
	fun releaseUntravelledTail(
		trainId: String,
		blockIds: List<String>
	): TailRelease
}

/**
 * Outcome of one [PartialRouteReleaser.releaseUntravelledTail] call.
 *
 * @property released the block ids actually freed, in any order. Empty if nothing could be.
 * @property deferred true when a proceed aspect stood at the head/tail boundary, so the signals
 *   were dropped to STOP but no block was freed; the caller should retry on its next sweep.
 */
data class TailRelease(
	val released: List<String>,
	val deferred: Boolean = false
) {
	companion object {
		/** Nothing freed, nothing pending. */
		val NOTHING = TailRelease(emptyList())
	}
}
