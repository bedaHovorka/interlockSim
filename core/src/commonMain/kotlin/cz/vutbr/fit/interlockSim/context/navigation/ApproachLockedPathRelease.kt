/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Outcome of [ApproachLockedPathRelease.releasePathDetailed].
 *
 * @property released blocks that were freed and dropped from the registry
 * @property deferred blocks kept RESERVED and registered by approach locking; non-empty means
 *   the release is partial and must be retried
 * @since Issue #1050
 */
data class PathRelease(
	val released: List<DynamicTrackBlock>,
	val deferred: List<DynamicTrackBlock>
)

/**
 * The approach-locked whole-route release, split out of [PathReservationService] to keep that
 * interface within the detekt function budget (Issue #1050).
 *
 * @since Issue #1050
 */
interface ApproachLockedPathRelease {
	/**
	 * `PathReservationService.releasePath` with approach locking, reporting what it kept back (Issue #1050). Plain
	 * `releasePath` stays unconditional (teardown, tests); the production release goes through here.
	 *
	 * A RESERVED, unoccupied block whose governing signal shows a proceed aspect may have a train
	 * committed to it: `Train.Front` reads the aspect, moves, and books the block only after
	 * `hold(1.0)`. Freeing it inside that second kills the train's process at `enter`. Such a
	 * block is therefore **kept reserved and registered** (its signals drop to STOP) and reported
	 * in [PathRelease.deferred]; the caller retries, and the retry frees it because the signal now
	 * shows STOP -- or the train has booked it, which makes it occupied and never released.
	 *
	 * @since Issue #1050
	 */
	fun releasePathDetailed(trainId: String): PathRelease
}
