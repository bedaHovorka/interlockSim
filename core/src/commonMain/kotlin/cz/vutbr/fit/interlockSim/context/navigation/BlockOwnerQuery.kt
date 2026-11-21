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
 * Who holds a block (Issue #1269, Goal 9B demand 1): the read-only half of "who blocks whom"; the
 * other half is [PathReservationService.ReservationResult.AllPathsBlocked.blockedBy]. Split out of
 * [PathReservationService] to keep that interface within the detekt function budget, as
 * [BlockReleaseSteps] and [ApproachLockedPathRelease] are.
 */
fun interface BlockOwnerQuery {
	/**
	 * The train that keeps [block] from being reserved by any other train: the train that holds it
	 * in the reservation registry; else the train the block itself records as reserved for or
	 * standing on it (a block held without a registry entry, the Issue #893 shape); `null` when no
	 * train is attributed to it.
	 *
	 * The inverse of [PathReservationService.getReservedBlocks], answered in O(1) instead of by
	 * scanning every train.
	 */
	fun getBlockOwner(block: DynamicTrackBlock): String?
}
