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
 * The rollback-only per-block step (Issue #961), the counterpart of [BlockReleaseSteps.releaseBlock].
 *
 * Internal to `:core` on purpose (PR #1115 review): a rollback publishes no release event and resets
 * no signal, so a caller outside the reservation machinery could free a block that was announced as
 * reserved and leave the event counters unbalanced (Issue #1081). The callers are the candidate and
 * bypass rollbacks in [DefaultPathReservationService] and the interlocking facade's rollback.
 *
 * @since Issue #961
 */
internal fun interface BlockRollbackStep {
	/**
	 * Roll back one [block] that a failed reservation attempt reserved for [trainId]: the rollback-only
	 * step (Issue #961). It undoes the reservation as if it had never happened.
	 *
	 * Refuses (returns `false`, changes nothing) a block [trainId] does not own: cancelling the path
	 * setup of another train's block would free it under that train's route, and the registry would then
	 * keep that train registered to a FREE block. For an owned block it cancels the path setup from its
	 * `reservedFrom` when that is set (a failure there is logged, not thrown), removes the block from
	 * [trainId]'s registration, and reclaims a switch lock that no held block protects any more. It
	 * resets no signal (the caller undoes the signals it set) and publishes **no** release event: the
	 * block was never announced as reserved, and a release without a reserve unbalances the event
	 * counters (Issue #1081). For a block that was announced, use [BlockReleaseSteps.releaseBlock].
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
