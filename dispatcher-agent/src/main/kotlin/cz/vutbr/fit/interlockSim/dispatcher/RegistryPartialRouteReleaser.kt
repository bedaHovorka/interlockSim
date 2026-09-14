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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
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
 *   tail is still attempted, and only ids that actually came free are returned. A block that is
 *   already FREE when its unregister fails is dropped from the registry directly, so it can never
 *   stay owned but invisible to the sweeper (Issue #1067).
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
	): TailRelease {
		val held = registry.getBlocks(trainId)
		if (held.isEmpty()) return TailRelease.NOTHING

		// Refuse unless the train really is standing on part of its own route. Without an occupied
		// block this is an ordinary abandoned route, which the whole-route path handles correctly
		// and more cheaply — and doing it here would silently bypass that path's own accounting.
		val occupied = held.filter { it.isOccupied() }
		if (occupied.isEmpty()) {
			logger.debug {
				"RegistryPartialRouteReleaser: '$trainId' occupies none of its blocks — " +
					"not a partial-release case"
			}
			return TailRelease.NOTHING
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
		if (eligible.isEmpty()) return TailRelease.NOTHING

		// Issue #1063 / #1067 gap 2: release and PathInfo trim succeed or fail together. The trim will
		// cut the PathInfo right after the occupied head, so check THAT end — not the ends of the
		// blocks offered in this call, which a sweep offering far blocks first would miss. A switch
		// (a train on zA-vA) or a signal facing away from the train (a train on vA-doA1) cannot end a
		// PathInfo, so releasing would leave one describing free track — the #1031 stall. Refuse
		// BEFORE any signal or block changes; the train keeps its reservation.
		val end = registry.pathInfoEndAfter(trainId, occupied)
		val validEnd = end?.let { registry.isValidPathInfoEnd(it.boundary, it.nextBlock) } == true
		// No PathInfo means nothing to trim, so the release may go; a PathInfo without a valid end may not.
		if (registry.getPathInfo(trainId) != null && !validEnd) {
			logger.info {
				"RegistryPartialRouteReleaser: not releasing the tail of '$trainId' — its PathInfo cannot end " +
					"after the occupied head (at ${end?.boundary ?: "no separator on the PathInfo"}: a switch, " +
					"or a signal facing away from the train) (Issue #1063, #1067)"
			}
			return TailRelease.NOTHING
		}

		// Approach locking (Issue #1025): read the boundary signals BEFORE the reset below drops
		// them. A proceed aspect standing where the occupied head meets the tail means the train
		// may already be committed to the first tail block — Train.Front reads the aspect, moves,
		// and books the block only after hold(1.0). Freeing that block now would kill it at enter.
		// The deferral below protects that train for exactly ONE sweep: on the retry the boundary
		// signals read STOP, so the tail goes. That in turn holds only while the caller's retry
		// interval — one control step, 2.0 simulated seconds in ShuntingLoop — outlasts
		// Train.Front's hold(1.0) booking window, so a committed train has booked the block
		// before the retry re-offers it.
		val standingProceed = boundarySignals(occupied, eligible).filter { it.signal.isAllowing() }

		// Fail-safe BEFORE any block becomes available to anyone else, and BEFORE cancelPathSetup
		// (below) clears each block's `reservedFrom` -- resetSemaphoresForReleasedBlocks needs that
		// field live to recover the governing semaphore/InOut for blocks whose `reservedFrom` is the
		// route's far-away START rather than a separator locally adjacent to them.
		pathReservationService.resetSemaphoresForReleasedBlocks(trainId, eligible)

		if (standingProceed.isNotEmpty()) {
			logger.info {
				"RegistryPartialRouteReleaser: approach lock for '$trainId' — a proceed aspect stood at " +
					"${standingProceed.joinToString(", ") { it.name }} between the occupied head and the tail; " +
					"signals set to STOP, physical release deferred to the next sweep (Issue #1025)"
			}
			return TailRelease(emptyList(), deferred = true)
		}

		val released = mutableListOf<String>()
		for (block in eligible) {
			releaseBlock(trainId, block)?.let { released += it }
		}
		if (!trimIfOnlyHeadIsHeld(trainId, occupied)) {
			logger.warn {
				"RegistryPartialRouteReleaser: released the tail of '$trainId' but its PathInfo was not trimmed, " +
					"although the end was checked before the release (see the trimPathInfoTo log)"
			}
		}
		return TailRelease(released)
	}

	/**
	 * Issue #1063 / #1067 gap 1: trims the PathInfo once the train holds nothing beyond its [occupied]
	 * head. It reads the CURRENT held set, not what one call released, so whichever sweep frees the
	 * last tail block does the trim, and the registry finds the boundary from the stored PathInfo.
	 *
	 * @return `false` only when a trim was due and the registry refused it
	 */
	private fun trimIfOnlyHeadIsHeld(
		trainId: String,
		occupied: List<DynamicTrackBlock>
	): Boolean {
		if (registry.getPathInfo(trainId) == null || !registry.getBlocks(trainId).all { it in occupied }) return true
		return registry.trimPathInfoToHeldBlocks(trainId)
	}

	/**
	 * The signals a train standing on [occupied] would read to enter one of the [tail] blocks: every
	 * semaphore (or InOut entry signal) that is an end shared by an occupied block and a tail block.
	 */
	private fun boundarySignals(
		occupied: List<DynamicTrackBlock>,
		tail: List<DynamicTrackBlock>
	): List<DynamicRailSemaphore> {
		val headEnds = occupied.flatMap { it.ends().asList() }.toSet()
		return tail
			.flatMap { block -> block.ends().filter { it in headEnds } }
			.mapNotNull { end ->
				when (end) {
					is DynamicRailSemaphore -> end
					is DynamicInOut -> end.inSemaphore
					else -> null
				}
			}.distinct()
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
		val unregistered =
			try {
				block.cancelPathSetup(reservedFrom)
				pathReservationService.unregisterBlock(trainId, block)
			} catch (e: Exception) {
				logger.warn(e) {
					"RegistryPartialRouteReleaser: could not release block '$id' of '$trainId'; " +
						"continuing with the rest of the tail"
				}
				false
			}
		return if (unregistered || (block.getState() == TrackFacility.State.FREE && dropFreedBlock(trainId, block, id))) {
			id
		} else {
			null
		}
	}

	/**
	 * Issue #1067 gap 1 (PR #1068 review): [block] is FREE, but the service did not finish unregistering
	 * it. Left owned, it reads as owner-less to the sweeper — a FREE reading carries no train — so no
	 * sweep offers it again and the PathInfo is never trimmed: the #1031 stall. Its signals were set to
	 * STOP before the release, so [PathReservationService.dropFreedBlock] finishes the release without a
	 * second reset, and still publishes the release event the metrics and conflict detectors count. If
	 * the service had already unregistered it before it failed, nothing more is done, so the event is
	 * never published twice.
	 *
	 * @return `true` when [trainId] no longer owns [block]
	 */
	private fun dropFreedBlock(
		trainId: String,
		block: DynamicTrackBlock,
		id: String
	): Boolean {
		if (registry.getOwner(block) != trainId) {
			logger.info {
				"RegistryPartialRouteReleaser: the service unregistered freed block '$id' of '$trainId' " +
					"before it failed; nothing more to do"
			}
			return true
		}
		logger.warn {
			"RegistryPartialRouteReleaser: the service did not unregister freed block '$id' of '$trainId'; " +
				"finishing the release without a second signal reset"
		}
		// PR #1068 review: the release event is published synchronously and a listener may throw. The
		// failure stays with this block, so the rest of the tail is still released.
		return try {
			pathReservationService.dropFreedBlock(trainId, block)
		} catch (e: Exception) {
			val dropped = registry.getOwner(block) != trainId
			logger.warn(e) {
				"RegistryPartialRouteReleaser: finishing the release of freed block '$id' of '$trainId' failed; " +
					if (dropped) "the block is unregistered, only its release event failed" else "the block stays registered"
			}
			dropped
		}
	}

	private fun DynamicTrackBlock.isOccupied(): Boolean = occupant != null || getState() == TrackFacility.State.OCCUPIED
}
