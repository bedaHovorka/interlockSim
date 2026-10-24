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

import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Route release and rollback of [DefaultPathReservationService] (Issue #1168, SP12.2): the
 * whole-route releases ([releasePath], [releasePathDetailed], [unregister]), the per-block release
 * funnel ([unregisterBlock], [dropFreedBlock], [releaseBlock]), the rollback steps ([rollbackBlock],
 * [rollbackReservation]) and the release event ([emitBlockReleased]). Switch cleanup before a
 * candidate stays with the switch configuration (Issue #1170).
 *
 * Pure extraction: the service keeps every `PathReservationService` / [BlockRollbackStep] entry point
 * and delegates here. The unit owns the approach-lock deferral window ([approachLockDeferredUntil]);
 * the cleared-signal bookkeeping lives in [ClearedSignalLedger] and the blocked-contention tracking in
 * [ReservationContentionTracker]; both are reached through the three callbacks below.
 *
 * **NOT thread-safe**, like the service it belongs to.
 *
 * @property registry Ownership registry shared with the service
 * @property resetSemaphoresForReleasedBlocks [ClearedSignalLedger.resetSemaphoresForReleasedBlocks], the
 *   body of [PathReservationService.resetSemaphoresForReleasedBlocks]
 * @property resetClearedSemaphores [ClearedSignalLedger.resetClearedSemaphores]: sets every semaphore
 *   recorded as cleared for a train back to STOP
 * @property clearBlockedTracking [ReservationContentionTracker.clearBlockedTracking]: forgets the
 *   blocked-path contention recorded for a train
 * @since Issue #1168
 */
internal class RouteReleaser(
	private val registry: PathReservationRegistry,
	private val resetSemaphoresForReleasedBlocks: (String, Collection<DynamicTrackBlock>) -> Unit,
	private val resetClearedSemaphores: (String) -> Unit,
	private val clearBlockedTracking: (String) -> Unit
) {
	// ── Approach-lock deferral window (Issue #1050 review round) ──
	//
	// A block deferred by releasePathDetailed is safe to free only once the booking window it
	// was deferred for has certainly closed. Train.Front reads a proceed aspect, holds
	// APPROACH_LOCK_DEFER_SECONDS, and only then books the block, so a train that read the
	// aspect just before the deferral can book it until (deferral time + the window). A retry
	// inside that window -- a second cancel_route in the same emission batch, since the sim
	// clock is paused during LLM emission -- would otherwise find the signal already at STOP,
	// classify the block as not approach-locked, and free it mid-booking (the exact #1050
	// FATAL). The deferral is therefore signal-read-AND-time-based: the entry stays until the
	// sim clock passes the expiry recorded here, whatever cadence the caller runs at.
	//
	// Entries are pruned when the block is freed (dropFreedBlock) or the train is unregistered
	// wholesale (releasePath), so the map stays bounded to deferred-and-still-held blocks.
	private val approachLockDeferredUntil: MutableMap<DynamicTrackBlock, Double> = mutableMapOf()

	/**
	 * The booking window [Train.Front] spends between reading a proceed aspect and booking the
	 * next block (`hold(1.0)` in its `semaphoreAction`). An approach-locked block stays deferred
	 * for this long after its deferral, so no caller cadence can free it mid-booking.
	 */
	private companion object {
		const val APPROACH_LOCK_DEFER_SECONDS: Double = 1.0
	}

	/**
	 * Release all blocks reserved by a train.
	 *
	 * ## Implementation
	 *
	 * 1. Get all blocks from registry
	 * 2. Cancel path setup for each block
	 * 3. Unregister train from registry
	 *
	 * This operation is idempotent - safe to call multiple times.
	 * Registry cleanup is guaranteed even if block release fails (try-finally).
	 */
	fun releasePath(trainId: String): List<DynamicTrackBlock> = release(trainId, approachLock = false).released

	/**
	 * Issue #1050: [releasePath] with approach locking, the counterpart of the rule
	 * `RegistryPartialRouteReleaser` applies to a tail (Issue #1025). A RESERVED, unoccupied block
	 * the train may be entering across a proceed aspect -- `Train.Front` books the block only after
	 * `hold(1.0)` -- is kept reserved and registered, and the caller retries. The retry frees the
	 * block once [APPROACH_LOCK_DEFER_SECONDS] of simulated time have passed since the deferral
	 * (by then the train has either booked it -- it stays as an occupied block until the train
	 * leaves -- or lost interest, the signal reading STOP). A kept tail whose PathInfo cannot be
	 * trimmed (a switch or rear-facing semaphore boundary) keeps the whole route. This is the
	 * release the production callers use (`NetworkActuatorPort.releaseRoute`: `cancel_route` and
	 * the orphan sweeper); plain [releasePath] stays unconditional for teardown and tests.
	 */
	fun releasePathDetailed(trainId: String): PathRelease = release(trainId, approachLock = true)

	private fun release(
		trainId: String,
		approachLock: Boolean
	): PathRelease {
		// Read BEFORE the signal reset below, which would erase the evidence.
		val approachLocked = if (approachLock) approachLockedBlocks(registry.getBlocks(trainId)) else emptyList()
		// Signals first, blocks second: a block must never become available to another train
		// while the semaphore that authorises entry to it still shows proceed. Runs before the
		// early return below because a train can hold cleared signals without holding blocks
		// (e.g. after a partial release reclaimed its un-travelled tail). The first call also
		// covers a governing signal of a kept block that this ledger never recorded.
		resetSemaphoresForReleasedBlocks(trainId, approachLocked)
		resetClearedSemaphores(trainId)

		val blocks = registry.getBlocks(trainId)
		if (blocks.isEmpty()) {
			return PathRelease(emptyList(), emptyList())
		}
		if (approachLock) {
			// Arm the deferral window for exactly the blocks a proceed aspect stands at NOW:
			// the latest moment a train can have read that aspect is this call, so the booking
			// it may still perform lands at now + APPROACH_LOCK_DEFER_SECONDS. A retry that
			// finds the signal already at STOP must NOT re-arm the window -- the train's
			// booking deadline is the FIRST reset, not the last retry.
			val now = currentSimulationTime()
			approachLocked.forEach { block -> approachLockDeferredUntil[block] = now + APPROACH_LOCK_DEFER_SECONDS }
			val kept = keptBlocks(blocks, approachLocked, now)
			if (kept.isNotEmpty()) {
				return releaseAllBut(trainId, blocks, kept)
			}
		}
		return PathRelease(releaseAllBlocks(trainId, blocks), emptyList())
	}

	/**
	 * The blocks the detailed release must keep (Issue #1050 review round), on top of the
	 * [approachLocked] ones:
	 *
	 * - **Occupied** blocks: a train stands on them. Falling back to [releaseAllBlocks] while
	 *   the train occupies any block of the route unregisters it wholesale and emits
	 *   `BlockReleased` for a still-occupied block -- the exact registry-vs-physical divergence
	 *   this release exists to prevent. They are freed by later calls once the train has left
	 *   them (`dropFreedBlock` refuses a non-FREE block, so the registry says the truth meanwhile).
	 * - **Unexpired deferrals**: blocks approach locking kept in an earlier call whose
	 *   [APPROACH_LOCK_DEFER_SECONDS] booking window is still open. The signal now reads STOP,
	 *   but a train that read the proceed aspect before the reset can still book the block
	 *   until the window closes.
	 */
	private fun keptBlocks(
		blocks: List<DynamicTrackBlock>,
		approachLocked: List<DynamicTrackBlock>,
		now: Double
	): List<DynamicTrackBlock> =
		blocks.filter { block ->
			block in approachLocked ||
				isOccupied(block) ||
				// Kept through the expiry instant itself (Issue #1050 review round): Train.Front
				// resumes from hold(1.0) and books the block AT the expiry timestamp, before
				// next.enter() runs. A retry scheduled first at that same simulation time must
				// still see the block as deferred -- only a strictly later `now` proves the
				// booking window has fully closed.
				approachLockDeferredUntil[block]?.let { it >= now } == true
		}

	/**
	 * The blocks of [held] a train may be committed to (Issue #1050): RESERVED, unoccupied, and
	 * entered across a boundary that shows a proceed aspect.
	 *
	 * Only blocks the train can be entering next qualify, which is what keeps a fresh reservation far
	 * from the train releasable: the boundary is an end shared with an occupied block of the train, or
	 * -- when it occupies nothing yet -- the route's start separator (a train waiting at the entry).
	 * Every other reserved block is not adjacent to the train, however many signals stand at proceed
	 * along the route.
	 */
	private fun approachLockedBlocks(held: List<DynamicTrackBlock>): List<DynamicTrackBlock> {
		val occupied = held.filter(::isOccupied)
		val headEnds = occupied.flatMap { it.ends().asList() }.toSet()
		return held.filter { block ->
			block.occupant == null &&
				block.getState() == TrackFacility.State.RESERVED &&
				boundarySemaphores(block, occupied.isNotEmpty(), headEnds).any { it.signal.isAllowing() }
		}
	}

	/** Whether a train stands on [block]: it has an occupant or is already booked as OCCUPIED. */
	private fun isOccupied(block: DynamicTrackBlock): Boolean =
		block.occupant != null || block.getState() == TrackFacility.State.OCCUPIED

	/** The semaphores a train reads to enter [block]: see [approachLockedBlocks]. */
	private fun boundarySemaphores(
		block: DynamicTrackBlock,
		trainOccupiesBlocks: Boolean,
		headEnds: Set<PathSeparator>
	): List<DynamicRailSemaphore> {
		val start = block.reservedFrom
		return block
			.ends()
			.filter { end -> if (trainOccupiesBlocks) end in headEnds else end == start }
			.mapNotNull { end ->
				when (end) {
					is DynamicRailSemaphore -> end
					is DynamicInOut -> end.inSemaphore
					else -> null
				}
			}
	}

	/** The whole-route release: every block cancelled, the train unregistered entirely. */
	private fun releaseAllBlocks(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	): List<DynamicTrackBlock> {
		try {
			// Cancel path setup for all blocks
			// Note: We don't know which separator was used for reservation,
			// but cancelPathSetup validates it matches the reservedFrom,
			// so we need to get it from the block itself
			blocks.forEach { cancelBlock(it) }

			// Tier 2: Unlock switches atomically with blocks (Issue #291)
			val switches = registry.getSwitches(trainId)
			switches.forEach { switch ->
				try {
					switch.unlock()
					logger.debug { "releasePath: Unlocked switch ${switch.hashCode()} for $trainId" }
				} catch (e: Exception) {
					logger.warn(e) { "releasePath: Failed to unlock switch $switch" }
				}
			}

			return blocks
		} finally {
			// Unregister blocks and switches from registry - ALWAYS executed
			// This prevents memory leaks and stale reservations if block release fails
			registry.unregister(trainId)
			registry.unregisterSwitches(trainId)
			// Whatever deferral window was still open for these blocks is over: the train's
			// whole footprint is gone (Issue #1050 review round).
			blocks.forEach { approachLockDeferredUntil.remove(it) }
			// Emit BlockReleased after registry cleanup so isBlockAvailable() returns true for subscribers
			val simTime = currentSimulationTime()
			blocks.forEach { block ->
				emitBlockReleased(block, trainId, simTime)
			}
			// Train is done contending for any block it may have been blocked on.
			clearBlockedTracking(trainId)
		}
	}

	/**
	 * Issue #1050: releases every block of [trainId] except [kept], which stay RESERVED and
	 * registered. Each freed block leaves the registry through [dropFreedBlock] -- never
	 * `registry.unregister(trainId)`, which would drop the kept entries too and leave the registry
	 * saying "free" for a block that is physically reserved (the Issue #1051 divergence). Switches
	 * are not unlocked wholesale: [dropFreedBlock] reclaims exactly those no held block still needs.
	 */
	private fun releaseAllBut(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		kept: List<DynamicTrackBlock>
	): PathRelease {
		val toRelease = blocks.filter { it !in kept }
		// Preflight (Issue #1050 review round): free nothing unless the PathInfo tail beyond
		// the kept blocks can be trimmed off. A freed tail that survives in the stored
		// PathInfo because trimPathInfoToHeldBlocks refuses the cut (a switch or rear-facing
		// semaphore boundary) later stalls the train with OwnershipConflict -- the failure
		// this release must not create. The whole route stays intact and is reported as
		// deferred, so the sweeper keeps its clock and the caller retries; the topology can
		// become trimmable once the train moves on.
		if (!registry.canTrimPathInfoTo(trainId, kept)) {
			logger.warn {
				"releasePath: approach lock for '$trainId' -- the PathInfo cannot be trimmed past " +
					"${kept.size} kept block(s), so nothing was freed and the whole route stays " +
					"reserved (Issue #1050)"
			}
			return PathRelease(emptyList(), blocks)
		}
		toRelease.forEach { cancelBlock(it) }
		val released =
			toRelease.filter { block ->
				try {
					dropFreedBlock(trainId, block)
				} catch (e: Exception) {
					logger.warn(e) { "releasePath: Failed to drop released block $block" }
					false
				}
			}
		// The PathInfo still describes the freed blocks; cut it to what the train holds. The
		// preflight above already validated this cut is possible, so a refusal here is a bug
		// in that validation -- logged loudly by the registry rather than silently ignored.
		registry.trimPathInfoToHeldBlocks(trainId)
		logger.info {
			"releasePath: approach lock for '$trainId' -- a proceed aspect stood at " +
				"${kept.size} reserved block(s); signals set to STOP, physical release of them deferred " +
				"to the next call (Issue #1050)"
		}
		return PathRelease(released, kept)
	}

	private fun cancelBlock(block: DynamicTrackBlock) {
		val reservedFrom = block.reservedFrom ?: return
		try {
			block.cancelPathSetup(reservedFrom)
		} catch (e: Exception) {
			logger.warn(e) { "releasePath: Failed to release block $block" }
		}
	}

	/**
	 * Rollback reservation of blocks.
	 *
	 * Cancels path setup for all blocks in the list. Errors during rollback are logged
	 * but not propagated (best-effort cleanup).
	 *
	 * @param separator The separator used for reservation
	 * @param blocks List of blocks to rollback
	 */
	fun rollbackReservation(
		separator: PathSeparator,
		blocks: List<DynamicTrackBlock>
	) {
		rollbackBlocks(separator, blocks)
	}

	/**
	 * Rollback block reservations only.
	 *
	 * Used when path discovery fails before registration.
	 * Only cancels block path setup via cancelPathSetup().
	 *
	 * @param separator The path separator that reserved the blocks
	 * @param blocks The blocks to rollback
	 */
	private fun rollbackBlocks(
		separator: PathSeparator,
		blocks: List<DynamicTrackBlock>
	) {
		for (block in blocks) {
			try {
				// Only rollback if block was actually reserved from this separator
				if (block.reservedFrom === separator) {
					logRollbackOfBlock(block, separator)
					block.cancelPathSetup(separator)
				}
			} catch (e: Exception) {
				logger.warn(e) { "rollbackBlocks: Failed to rollback block $block" }
			}
		}
	}

	/**
	 * Issue #1025 diagnostics: a rollback that cancels a block the registry still attributes to a
	 * train leaves the registry and the physical block state divergent. Navigation trusts the
	 * registry only, so such a train is later routed into a FREE block and
	 * [DynamicTrackBlock.enter] throws `Wrong state: FREE, expected: RESERVED`. Log that case at
	 * WARN so a run log names the block, the separator and the train it was taken from.
	 */
	private fun logRollbackOfBlock(
		block: DynamicTrackBlock,
		separator: PathSeparator
	) {
		val registryOwner = registry.getOwner(block)
		if (registryOwner != null && registryOwner == block.trainName) {
			logger.warn {
				"rollbackBlocks: cancelling block $block reserved from $separator while the registry still " +
					"attributes it to '$registryOwner' (state=${block.getState()}); " +
					"the registry and the physical state now diverge (Issue #1025)"
			}
		} else {
			logger.debug {
				"rollbackBlocks: cancelling block $block reserved from $separator " +
					"(trainName=${block.trainName}, registry owner=${registryOwner ?: "none"})"
			}
		}
	}

	/**
	 * Unregister all block reservations for a train.
	 *
	 * Removes the train from the registry, freeing all blocks it owns.
	 * Called when a train completes its journey.
	 *
	 * This is a full route release, not just registry removal: the train's cleared signals return
	 * to STOP first, blocks still RESERVED but never entered are cancelled back to FREE, and the
	 * route's switches are unlocked only after their blocks are free — see the interface KDoc for
	 * the contract.
	 *
	 * @param trainId The train identifier to unregister
	 * @return List of blocks that were released
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock> {
		// Same ordering rationale as releasePath: return this train's signals to STOP before its
		// blocks become available to anyone else.
		resetClearedSemaphores(trainId)

		// Cancel path setup for blocks still physically RESERVED (un-travelled route head or
		// extension tail). registry.unregister below only removes ownership maps; it does NOT
		// drive block state RESERVED->FREE. Without this, a journey completing with
		// RESERVED-but-never-entered blocks (reachable via bidirectional reversal or an
		// abandoned forward extension) leaves orphan RESERVED blocks no other train can ever
		// reserve (isBlockAvailable fails on state != FREE). Mirrors the cancelBlock loop of
		// releaseAllBlocks.
		// The reservedFrom != null guard skips already-entered/left blocks (enter() nulls
		// reservedFrom), so only RESERVED-unentered blocks are freed.
		// unregister is the production train-completion path; releasePath is test-only.
		val blocks = registry.getBlocks(trainId)
		blocks.forEach { block ->
			val reservedFrom = block.reservedFrom
			if (reservedFrom != null) {
				try {
					block.cancelPathSetup(reservedFrom)
				} catch (e: Exception) {
					logger.warn(e) { "unregister: Failed to release block $block" }
				}
			}
		}

		// Unlock switches AFTER their route blocks are cancelled, matching releaseAllBlocks' switch
		// step: a switch is unlocked only once nothing it protects is still RESERVED.
		val switches = registry.getSwitches(trainId)
		switches.forEach { switch ->
			try {
				switch.unlock()
				logger.debug { "unregister: Unlocked switch ${switch.hashCode()} for $trainId" }
			} catch (e: Exception) {
				logger.warn(e) { "unregister: Failed to unlock switch $switch" }
			}
		}

		val releasedBlocks = registry.unregister(trainId)
		registry.unregisterSwitches(trainId)
		// Whatever deferral window was still open for these blocks is over: the train's whole
		// footprint is gone (Issue #1050 review round; unregister() is the other wholesale-release
		// path releaseAllBlocks already covers).
		releasedBlocks.forEach { approachLockDeferredUntil.remove(it) }

		logger.info {
			"unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
				releasedBlocks.joinToString(", ") { it.toString() }
		}
		val simTime = currentSimulationTime()
		releasedBlocks.forEach { block ->
			emitBlockReleased(block, trainId, simTime)
		}
		return releasedBlocks
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry if it is FREE (no occupant).
	 * Called by Train's Tail process after leaving a block.
	 *
	 * On success, also resets the released block's governing semaphores via
	 * [resetSemaphoresForReleasedBlocks] -- see the interface KDoc for why this is safe on this
	 * per-block tail-clearance call site (every boundary of a released block is behind the train's
	 * head).
	 *
	 * On the production `Train.Tail.separatorAction` call site, [block]'s `reservedFrom` is always
	 * null by the time this runs: a block can only be left after
	 * [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter] has run for it (the
	 * `Front` occupies it before the `Tail` can leave it), and `enter()` unconditionally nulls
	 * `reservedFrom`. [resetSemaphoresForReleasedBlocks]'s `reservedFrom` candidate source is
	 * therefore dead code on this call site -- `block.ends()` alone (a structural superset here)
	 * carries the coverage.
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if still occupied or not owned
	 * @since Issue #893 (phase alpha, task A4) -- added the [resetSemaphoresForReleasedBlocks] call
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		// PR #1068 review: check the release preconditions BEFORE the reset. A block that is not
		// owned by this train, or not FREE, is not released, so its boundaries are not known to be
		// behind the train's head — resetting there could drop a proceed aspect the train still
		// needs and stall it.
		val releasable =
			registry.getOwner(block) == trainId && block.occupant == null && block.getState() == TrackFacility.State.FREE
		if (!releasable) {
			logger.debug {
				"unregisterBlock: not releasing $block for '$trainId' (owner='${registry.getOwner(block)}', " +
					"occupant=${block.occupant}, state=${block.getState()}); signals left as they are"
			}
			return false
		}
		// Signals first, then registry: reset the governing semaphore to STOP BEFORE the block
		// leaves the registry, so there is no instant where a block is owner-less-and-FREE while
		// its authorising signal still shows proceed. Matches release's "Signals first, blocks
		// second" step.
		// Emission stays last, so a subscriber still never observes a FREE block whose signal
		// shows proceed.
		resetSemaphoresForReleasedBlocks(trainId, listOf(block))
		return dropFreedBlock(trainId, block)
	}

	fun dropFreedBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		val released = registry.unregisterBlock(trainId, block)
		if (released) {
			approachLockDeferredUntil.remove(block)
			// Issue #1065, #1103: the registry reclaimed every now-stale switch claim at the block's
			// ends inside unregisterBlock, BEFORE this event, so a throwing release listener cannot
			// skip it (PR #1115 review) and no release path -- this funnel, rollbackBlock, or a direct
			// registry call -- is left out.
			emitBlockReleased(block, trainId, currentSimulationTime())
		}
		return released
	}

	/**
	 * The ownership guard of [releaseBlock] and [rollbackBlock]: `true` when [trainId] owns [block]. A
	 * refusal is logged with the [step] name and the actual owner.
	 */
	private fun ownsBlock(
		step: String,
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		val owner = registry.getOwner(block)
		if (owner != trainId) {
			logger.debug { "$step: refusing $block for '$trainId' (owner='$owner')" }
		}
		return owner == trainId
	}

	fun releaseBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		// Ownership first: cancelling the path setup of a block another train holds would free it
		// under that train's route.
		if (!ownsBlock("releaseBlock", trainId, block)) return false
		// cancelPathSetup clears reservedFrom, so unregisterBlock's reset below sees only block.ends():
		// a far START semaphore is not reset here. A caller releasing an un-travelled block resets the
		// signals first (resetSemaphoresForReleasedBlocks), as RegistryPartialRouteReleaser does.
		block.reservedFrom?.let { block.cancelPathSetup(it) }
		return unregisterBlock(trainId, block)
	}

	fun rollbackBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		// Ownership first, as in releaseBlock (PR #1115 review).
		if (!ownsBlock("rollbackBlock", trainId, block)) return false
		try {
			block.reservedFrom?.let { block.cancelPathSetup(it) }
		} catch (e: Exception) {
			// Logged, not thrown: the registry cleanup below must still run. A block left RESERVED
			// stays registered, because the registry only drops a FREE block.
			logger.warn(e) { "rollbackBlock: failed to cancel the path setup of $block for '$trainId'" }
		}
		// The registry reclaims any now-stale switch claim at the block's ends itself (Issue #1103).
		val unregistered = registry.unregisterBlock(trainId, block)
		if (unregistered) {
			approachLockDeferredUntil.remove(block)
		}
		return unregistered
	}

	/**
	 * Emit both the new kdisco-bus [BlockEvent.BlockReleased] and the legacy
	 * [BlockOccupancyEvent] (BLOCK_RELEASED) for a single block.
	 *
	 * This keeps the two event channels consistent on every release path
	 * ([releasePath], [unregister], [unregisterBlock]).
	 */
	fun emitBlockReleased(
		block: DynamicTrackBlock,
		trainId: String,
		simTime: Double
	) {
		emitCustom(BlockEvent.BlockReleased(block, trainId, simTime))
		registry.emit(
			BlockOccupancyEvent(
				block = block,
				type = BlockOccupancyEventType.BLOCK_RELEASED,
				trainId = trainId,
				occupant = null,
				previousState = TrackFacility.State.RESERVED,
				newState = TrackFacility.State.FREE,
				simulationTime = simTime
			)
		)
	}
}
