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
 * The two per-block steps that end a block's reservation (Issue #961): [releaseBlock] for a block
 * that was announced as reserved, [rollbackBlock] for one a failed attempt reserved without
 * announcing it. Every caller that frees a single block goes through one of them, so the release
 * event is published exactly when a reservation event was. Split out of [PathReservationService] to
 * keep that interface within the detekt function budget, as [ApproachLockedPathRelease] is.
 *
 * @since Issue #961
 */
interface BlockReleaseSteps {
	/**
	 * Release one [block] of [trainId]'s route for good: the committed-release step (Issue #961).
	 *
	 * The sequence:
	 * 1. Refuse (return `false`, change nothing) a block [trainId] does not own.
	 * 2. Cancel the block's path setup from its `reservedFrom` when that is still set (an un-travelled
	 *    RESERVED block becomes FREE; a block the train has already entered and left has none).
	 * 3. Run the [PathReservationService.unregisterBlock] path: reset the semaphores at the block's own
	 *    ends ([DynamicTrackBlock.ends]) to STOP, remove the block from the registry, publish the release
	 *    event, and reclaim a switch lock that no held block protects any more.
	 *
	 * **Signal precondition for an un-travelled block.** Step 2 clears the block's `reservedFrom`, so the
	 * reset in step 3 sees only the block's own ends. A far route START semaphore -- one that governs
	 * the block through `reservedFrom` but is not one of its ends -- is NOT reset by this step. A caller
	 * releasing an un-travelled RESERVED block must therefore call
	 * [PathReservationService.resetSemaphoresForReleasedBlocks] for it BEFORE [releaseBlock], as
	 * `RegistryPartialRouteReleaser` does; [releaseBlock] alone does not reset a far start signal.
	 *
	 * Use it only for a block that was announced as reserved: the release event it publishes is counted
	 * against that reservation by the metrics and the conflict and collision detectors. A rollback of a
	 * block that was never announced uses [rollbackBlock] instead.
	 *
	 * @param trainId The train releasing the block
	 * @param block The block to release
	 * @return true if the block was released (it is FREE and no longer registered to [trainId])
	 * @throws Exception whatever `cancelPathSetup` or a release-event listener throws; the caller decides
	 *   how to contain it
	 * @since Issue #961
	 */
	fun releaseBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean

	/**
	 * Roll back one [block] that a failed reservation attempt reserved for [trainId]: the rollback-only
	 * step (Issue #961). It undoes the reservation as if it had never happened.
	 *
	 * Cancels the block's path setup from its `reservedFrom` when that is set (a failure there is logged,
	 * not thrown), removes the block from [trainId]'s registration, and reclaims a switch lock that no
	 * held block protects any more. It resets no signal (the caller undoes the signals it set) and
	 * publishes **no** release event: the block was never announced as reserved, and a release without
	 * a reserve unbalances the event counters (Issue #1081). For a block that was announced, use
	 * [releaseBlock].
	 *
	 * @param trainId The train whose failed attempt reserved the block
	 * @param block The block to roll back
	 * @return true if the block was removed from [trainId]'s registration
	 * @since Issue #961
	 */
	fun rollbackBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean
}
