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
 * @property released blocks that were freed and dropped from the registry. In the partial
 *   branch these are only the blocks whose registry drop succeeded; a block whose
 *   `cancelPathSetup` or drop failed is reported neither here nor in [deferred] -- the
 *   sweeper re-detects it on the next sweep with a fresh clock. The unconditional branch
 *   reports every block of the route, successes and failures alike.
 * @property deferred blocks kept RESERVED and registered -- by approach locking, because the
 *   train occupies them, or because the deferral window is still open; non-empty means the
 *   release is not complete: a refused release (Issue #1242) lists the whole route, an
 *   approach-lock deferral lists the blocks it kept, a partial release lists the blocks it kept
 *   back. A repeated call frees a kept block only after the train has left it or its booking
 *   window has closed, so retrying while the train stands on it changes nothing
 * @since Issue #1050
 */
data class PathRelease(
	val released: List<DynamicTrackBlock>,
	val deferred: List<DynamicTrackBlock>
)

/**
 * The approach-locked whole-route release, split out of [PathReservationService] to keep that
 * interface within the detekt function budget (Issue #1050). Also carries [releasePath] itself
 * (moved here from [PathReservationService] in the same review round): a `fun interface` may add
 * as many default (non-abstract) members as it likes, so [releasePath] stays the sole abstract
 * member here and [releasePathDetailed] can default in terms of it, giving every implementation a
 * working (if unconditional) [releasePathDetailed] for free instead of forcing every implementer
 * to add it by hand.
 *
 * @since Issue #1050
 */
fun interface ApproachLockedPathRelease {
	/**
	 * Release all blocks reserved by a train.
	 *
	 * This operation is idempotent - calling it multiple times for the same train
	 * is safe (subsequent calls do nothing).
	 *
	 * ## State Changes
	 *
	 * For each block owned by the train:
	 * - Block state transitions from RESERVED to FREE
	 * - `DynamicTrackBlock.trainName` set to null
	 * - Ownership removed from registry
	 *
	 * Signals first, always: every semaphore the reservation service recorded as cleared for [trainId] is
	 * returned to STOP BEFORE any of its blocks becomes available — a block must never become
	 * reserveable while the aspect authorising entry to it still shows proceed. This reset runs
	 * even when the train owns no blocks (a partial release may have reclaimed them earlier),
	 * which is why [PathReservationService.hasClearedSignals] exists.
	 *
	 * ## Use Cases
	 *
	 * - Train completes journey (exits network)
	 * - Train cancels path before entering
	 * - Simulation cleanup/reset
	 *
	 * @param trainId Unique identifier for the train
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun releasePath(trainId: String): List<DynamicTrackBlock>

	/**
	 * `releasePath` with approach locking, reporting what it kept back (Issue #1050). Plain
	 * `releasePath` stays unconditional and is used by tests only; the production release goes through
	 * here. This KDoc is the single home of the approach-lock contract; the time window it relies on
	 * is documented at `RouteReleaser.approachLockDeferredUntil`.
	 *
	 * A RESERVED, unoccupied block whose governing signal shows a proceed aspect may have a train
	 * committed to it: `Train.Front` reads the aspect, moves, and books the block only after
	 * `hold(1.0)`. Freeing it inside that second kills the train's process at `enter`. Such a
	 * block is therefore **kept reserved and registered** (its signals drop to STOP) and reported
	 * in [PathRelease.deferred]. The caller retries: the retry frees the block once the booking
	 * window has certainly closed -- the train has either booked it, in which case it stays
	 * occupied and registered until the train leaves, or lost interest, the signal reading STOP.
	 * A deferral is therefore time-based as well as signal-based, so no caller cadence (a second
	 * `cancel_route` in the same emission batch, whose sim clock never advanced) can free a block
	 * mid-booking.
	 *
	 * Once the deferred train books its block it occupies track again, so any later partial
	 * release of that route goes through the occupying-train arm of the sweeper (the Issue
	 * #1025 approach lock on tail releases) -- the two mechanisms hand over exactly there.
	 *
	 * Caveat: only signals recorded in the `ClearedSignalLedger` are
	 * reset to STOP. A proceed aspect that reached the boundary semaphore outside the ledger
	 * keeps authorising entry, so every retry defers again until some other cause clears it.
	 * Every aspect writer in production records into the ledger today.
	 *
	 * The default here is a plain, unconditional wrap of [releasePath] (no deferral) --
	 * [DefaultPathReservationService] delegates it to `RouteReleaser.releasePathDetailed`, the real
	 * approach-locked logic; any other implementation gets a safe, non-deferring fallback instead of a
	 * forced abstract member (Copilot review round, PR #1080).
	 *
	 * @since Issue #1050
	 */
	fun releasePathDetailed(trainId: String): PathRelease = PathRelease(releasePath(trainId), emptyList())
}
