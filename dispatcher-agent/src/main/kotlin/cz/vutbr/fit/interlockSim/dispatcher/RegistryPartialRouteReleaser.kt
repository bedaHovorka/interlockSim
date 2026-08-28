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

import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * [PartialRouteReleaser] composed from the public reservation API, block by block.
 *
 * ## Why it is built out here and not added to the port (Issue #847 round 4, R4-3)
 *
 * No public API expresses "release part of a route". `NetworkActuatorPort.releaseRoute` is
 * train-scoped and ends in `registry.unregister(trainId)`, which drops every block including the
 * occupied one. `PathReservationService.unregisterBlock` *is* block-scoped, but its registry
 * precondition requires `occupant == null && state == FREE` — so on its own it cannot drop an
 * un-travelled **RESERVED** block either; it just returns `false`.
 *
 * The working sequence already exists inside `DefaultPathReservationService`, in the private
 * `rollbackUnconfigurableCandidate` used when a candidate path is rejected mid-reservation:
 * `block.cancelPathSetup(reservedFrom)` first, moving RESERVED→FREE, then `unregisterBlock`. This
 * class performs the same two steps from outside, which is why PR #891 can close R4-3 without
 * changing a single file under `core/`.
 *
 * ## Safety
 *
 * - **The occupied block is never touched.** Only blocks in state RESERVED with no occupant are
 *   considered, and the whole operation is refused unless the train really does occupy part of its
 *   route — a train occupying nothing is the whole-route sweeper's case, not this one.
 * - **No released block is left reachable through a permissive signal.** `cancelPathSetup` on a
 *   *block* frees the block but does not touch the semaphore that authorised entry to it. Every
 *   semaphore governing a released block is driven to STOP via
 *   [PathReservationService.resetSemaphoresForReleasedBlocks] (Issue #893, task A3) -- the
 *   ownership-aware, `ends()`/`reservedFrom`-scoped reset that also reaches an INTERMEDIATE
 *   semaphore between two released blocks and, for a route that started at a `DynamicInOut`, that
 *   InOut's `inSemaphore`, neither of which a per-block `reservedFrom as? DynamicRailSemaphore`
 *   cast alone can recover. STOP is always the fail-safe direction: it authorises nothing, so this
 *   can only ever be over-restrictive, never permissive. The train is by definition stalled — it
 *   has held this tail unchanged for at least the staleness threshold — so the restriction costs
 *   nothing it was using.
 * - **Switches are deliberately left locked.** Nothing available here says which switch belongs to
 *   the released tail rather than the retained head, and `unregisterSwitch` on a switch the train
 *   still needs would unlock a route under a standing train. An over-locked switch merely blocks
 *   movement; an under-locked one is a safety failure. This is the conservative half of the
 *   railway-domain question round 3 raised, and it means a reclaimed tail may not be immediately
 *   re-routable — a traffic-simulation-expert ruling could relax it later.
 * - **Per-block failure is contained.** A block that throws is logged and skipped; the rest of the
 *   tail is still attempted, and only ids that actually came free are returned.
 *
 * Runs on the simulation thread, from `ShuntingLoop`'s control-step listener.
 *
 * @since Issue #847 round 4 (PR #891)
 */
class RegistryPartialRouteReleaser(
	private val registry: PathReservationRegistry,
	private val pathReservationService: PathReservationService
) : PartialRouteReleaser {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override fun releaseUntravelledTail(
		trainId: String,
		blockIds: List<String>
	): List<String> {
		val held = registry.getBlocks(trainId)
		if (held.isEmpty()) return emptyList()

		// Refuse unless the train really is standing on part of its own route. Without an occupied
		// block this is an ordinary abandoned route, which the whole-route path handles correctly
		// and more cheaply — and doing it here would silently bypass that path's own accounting.
		if (held.none { it.isOccupied() }) {
			logger.debug {
				"RegistryPartialRouteReleaser: '$trainId' occupies none of its blocks — " +
					"not a partial-release case"
			}
			return emptyList()
		}

		val requested = blockIds.toSet()
		// Re-checked against live state, not trusted from the caller's snapshot: the sweeper's
		// reading is one control step old and the train may have entered a block since.
		val eligible =
			held.filter { block ->
				BlockIdentity.stableBlockId(block) in requested &&
					!block.isOccupied() &&
					block.getState() == TrackFacility.State.RESERVED
			}
		if (eligible.isEmpty()) return emptyList()

		// Fail-safe BEFORE any block becomes available to anyone else, and BEFORE cancelPathSetup
		// (below) clears each block's `reservedFrom` -- resetSemaphoresForReleasedBlocks needs that
		// field live to recover the governing semaphore/InOut for blocks whose `reservedFrom` is the
		// route's far-away START rather than a separator locally adjacent to them.
		pathReservationService.resetSemaphoresForReleasedBlocks(trainId, eligible)

		val released = mutableListOf<String>()
		for (block in eligible) {
			releaseBlock(trainId, block)?.let { released += it }
		}
		return released
	}

	/**
	 * Attempts to release a single [block] from [trainId]'s route.
	 *
	 * @return the block's stable id if it was successfully released, or `null` if it was skipped.
	 */
	private fun releaseBlock(
		trainId: String,
		block: DynamicTrackBlock
	): String? {
		val id = BlockIdentity.stableBlockId(block)
		val reservedFrom = block.reservedFrom ?: return null
		return try {
			block.cancelPathSetup(reservedFrom)
			if (pathReservationService.unregisterBlock(trainId, block)) {
				id
			} else {
				logger.warn {
					"RegistryPartialRouteReleaser: freed block '$id' but the registry refused to " +
						"unregister it for '$trainId'; leaving ownership in place"
				}
				null
			}
		} catch (e: Exception) {
			logger.warn(e) {
				"RegistryPartialRouteReleaser: could not release block '$id' of '$trainId'; " +
					"skipping it and continuing with the rest of the tail"
			}
			null
		}
	}

	private fun DynamicTrackBlock.isOccupied(): Boolean = occupant != null || getState() == TrackFacility.State.OCCUPIED
}
