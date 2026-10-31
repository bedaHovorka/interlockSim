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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Who blocks whom (Issue #1269, Goal 9B demand 1): the block-owner query behind
 * [PathReservationService.getBlockOwner] and the per-candidate "blocked by" record of a refused
 * [PathReservationService.reservePath].
 *
 * A pure query over the [registry] and the blocks' own state: it changes nothing.
 * **NOT thread-safe**, like its owner: single-threaded simulation access only.
 */
internal class CandidateBlockerScan(
	private val registry: PathReservationRegistry
) {
	/**
	 * The train that keeps [block] from other trains: its registry owner; else the train the block
	 * records as reserved for ([DynamicTrackBlock.trainName]) or standing on it -- a block held
	 * without a registry entry, the Issue #893 shape; `null` when no train is attributed.
	 */
	fun ownerOf(block: DynamicTrackBlock): String? = registry.getOwner(block) ?: block.trainName ?: block.occupant?.name

	/**
	 * What keeps [trainId] out of candidate number [candidateIndex], whose blocks are [blocks]:
	 * every block another train holds or that is not FREE, and every switch between those blocks
	 * whose live claim belongs to another train. A stale claim is left out: [trainId]'s next attempt
	 * reclaims it before its switch check (`CandidateCommitter` Step 2e.5), so it never refuses.
	 *
	 * Called after the candidate's own rollback, so nothing of [trainId]'s attempt is in the result.
	 */
	fun scan(
		trainId: String,
		candidateIndex: Int,
		blocks: List<DynamicTrackBlock>
	): PathReservationService.BlockedCandidate {
		val blockedBlocks = linkedMapOf<DynamicTrackBlock, String?>()
		for (block in blocks) {
			val owner = ownerOf(block)
			if (owner != trainId && (owner != null || block.getState() != TrackFacility.State.FREE)) {
				blockedBlocks[block] = owner
			}
		}
		val lockedSwitches = linkedMapOf<DynamicRailSwitch, String>()
		for (switch in blocks.flatMap { it.ends().filterIsInstance<DynamicRailSwitch>() }.distinct()) {
			val owner = registry.getSwitchOwner(switch)
			if (owner != null && owner != trainId && !registry.isStaleSwitchOwnership(switch)) {
				lockedSwitches[switch] = owner
			}
		}
		return PathReservationService.BlockedCandidate(candidateIndex, blockedBlocks, lockedSwitches)
	}
}
