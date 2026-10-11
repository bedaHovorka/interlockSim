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

import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.ConflictReason
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.RegistrationResult
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KLogger

private val logger: KLogger = registryLogger

/**
 * Block ownership of [PathReservationRegistry]: which train holds which [DynamicTrackBlock], and
 * since when (Issue #975). Split out of the registry by Issue #1172; the registry delegates every
 * block-ownership operation here and keeps the public contract and its KDoc.
 *
 * The table knows nothing about switches or PathInfo: the registry composes the cross-cutting
 * operations (whole-route teardown, the per-block release funnel that also reclaims stale switch
 * claims) out of this table, [SwitchOwnershipTable] and [PathInfoStore].
 *
 * **NOT thread-safe**, like the registry.
 *
 * @param simTimeSource Current simulation time, read once for each newly registered block.
 * @since Issue #1172 (the logic since Issue #294 / #581 / #975)
 */
internal class BlockOwnershipTable(
	private val simTimeSource: () -> Double
) {
	/** Train ID -> reserved blocks, in registration order. */
	private val trainToBlocks = mutableMapOf<String, MutableList<DynamicTrackBlock>>()

	/** Block -> owning train ID. */
	private val blockToTrain = mutableMapOf<DynamicTrackBlock, String>()

	/** Block -> simulation time it was registered to its current owner; exactly the keys of [blockToTrain]. */
	private val blockRegisteredAt = mutableMapOf<DynamicTrackBlock, Double>()

	/**
	 * The all-or-nothing check-and-reserve of [PathReservationRegistry.registerAtomic]: the registry
	 * owner, then the block's own `trainName`, then its physical occupant are checked for every
	 * block before any is registered.
	 */
	fun registerAtomic(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	): RegistrationResult {
		// Phase 1: Validate no conflicts
		for (block in blocks) {
			findConflict(trainId, block)?.let { return it }
		}

		// Phase 2: All checks passed - register all blocks
		claim(trainId, blocks)
		return RegistrationResult.Success
	}

	/**
	 * Phase 1 of [registerAtomic] for one block: the registry owner, then the block's own
	 * `trainName`, then its physical occupant.
	 *
	 * @return the first conflict found, or `null` when [trainId] may take [block]
	 */
	private fun findConflict(
		trainId: String,
		block: DynamicTrackBlock
	): RegistrationResult.Conflict? {
		val registryOwner = blockToTrain[block]
		if (registryOwner != null && registryOwner != trainId) {
			return RegistrationResult.Conflict(block, registryOwner, classifyConflict(block))
		}

		// Defence in depth: also check the block's own reservation state in case
		// the registry and the dynamic block have diverged (e.g. manual cleanup).
		val blockOwner = block.trainName
		if (blockOwner != null && blockOwner != trainId) {
			return RegistrationResult.Conflict(block, blockOwner, classifyConflict(block))
		}

		// Defence in depth: a block may be occupied even if the registry has no
		// record of it. Reject the reservation to avoid entering an occupied block.
		val occupant = block.occupant
		if (occupant != null && occupant.name != trainId) {
			return RegistrationResult.Conflict(block, occupant.name, ConflictReason.OCCUPIED_BY_OTHER_TRAIN)
		}
		return null
	}

	/** Phase 2 of [registerAtomic]: record every block for [trainId]; callers have checked for conflicts. */
	private fun claim(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		val blockList = trainToBlocks.getOrPut(trainId) { mutableListOf() }
		blocks.forEach { block ->
			if (block !in blockList) {
				blockList.add(block)
			}
			blockToTrain[block] = trainId
			// The clock is read only for a newly registered block; it does not advance within this call.
			blockRegisteredAt.getOrPut(block) { simTimeSource() }
		}
	}

	/**
	 * Classify a conflict based on the block's physical state.
	 *
	 * @return [ConflictReason.OCCUPIED_BY_OTHER_TRAIN] if the block currently has
	 *         a physical occupant, [ConflictReason.RESERVED_BY_OTHER_TRAIN] otherwise.
	 */
	private fun classifyConflict(block: DynamicTrackBlock): ConflictReason =
		if (block.occupant != null) {
			ConflictReason.OCCUPIED_BY_OTHER_TRAIN
		} else {
			ConflictReason.RESERVED_BY_OTHER_TRAIN
		}

	/**
	 * Remove every block of [trainId] together with their timestamps and the train's entry,
	 * regardless of block state.
	 *
	 * @return the blocks the train held, or `null` when the train has no entry at all
	 */
	fun removeTrain(trainId: String): List<DynamicTrackBlock>? {
		val blocks: List<DynamicTrackBlock> = trainToBlocks[trainId] ?: return null

		// Remove all blocks from mappings (regardless of state)
		blocks.forEach { block ->
			blockToTrain.remove(block)
			blockRegisteredAt.remove(block)
		}
		trainToBlocks.remove(trainId)
		return blocks.toList()
	}

	/**
	 * The block half of [PathReservationRegistry.unregisterBlock]: remove [block] if [trainId] owns
	 * it and it is FREE (no occupant, state FREE). The train's entry goes with its last block; its
	 * PathInfo is not this table's concern.
	 *
	 * @return `true` if the block was released, `false` if it is not owned by [trainId] or not FREE
	 */
	fun release(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		if (!isReleasable(trainId, block)) return false
		drop(trainId, block)
		return true
	}

	/** The guard of [release]: [trainId] owns [block] and the block is FREE. Logs the refusal. */
	private fun isReleasable(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		// Validate block is owned by this train
		val owner = blockToTrain[block]
		if (owner != trainId) {
			logger.debug {
				"unregisterBlock: Block $block not owned by '$trainId' (owner='$owner')"
			}
			return false
		}

		// Only unregister if block is FREE
		if (block.occupant != null || block.getState() != TrackFacility.State.FREE) {
			logger.debug {
				"unregisterBlock: Block $block still occupied (occupant=${block.occupant}, " +
					"state=${block.getState()})"
			}
			return false
		}
		return true
	}

	/** The mutation of [release]: remove [block] from the maps, and the train's entry with its last block. */
	private fun drop(
		trainId: String,
		block: DynamicTrackBlock
	) {
		// Remove from mappings
		blockToTrain.remove(block)
		blockRegisteredAt.remove(block)
		trainToBlocks[trainId]?.remove(block)

		val remainingBlocks = trainToBlocks[trainId]?.size ?: 0
		logger.debug {
			"unregisterBlock: Released block $block for '$trainId', $remainingBlocks blocks remaining"
		}

		// If no blocks remain, remove train entry from trainToBlocks
		// BUT keep PathInfo for navigation queries (Issue #301 - deadlock fix)
		if (trainToBlocks[trainId]?.isEmpty() == true) {
			trainToBlocks.remove(trainId)
			logger.debug {
				"unregisterBlock: All blocks released for '$trainId', " +
					"removed from trainToBlocks (PathInfo retained for navigation)"
			}
		}
	}

	/**
	 * The live list of blocks [trainId] holds, or `null` when the train has no entry. Read-only view
	 * for [SwitchOwnershipTable]'s staleness rule, which distinguishes "no entry" from "no block
	 * bounded by the switch" exactly as the registry did before the split.
	 */
	fun heldBlocksOrNull(trainId: String): List<DynamicTrackBlock>? = trainToBlocks[trainId]

	/** A copy of the blocks [trainId] holds (empty if none). */
	fun getBlocks(trainId: String): List<DynamicTrackBlock> = trainToBlocks[trainId]?.toList() ?: emptyList()

	/** The train that owns [block], or `null`. */
	fun getOwner(block: DynamicTrackBlock): String? = blockToTrain[block]

	/** The simulation time [block] was registered to its current owner, or `null` when unowned. */
	fun getRegisteredAtSimTime(block: DynamicTrackBlock): Double? = blockRegisteredAt[block]

	/** FREE, without registry owner and without physical occupant. */
	fun isBlockAvailable(block: DynamicTrackBlock): Boolean =
		block.getState() == TrackFacility.State.FREE &&
			blockToTrain[block] == null &&
			block.occupant == null

	/** The held blocks of [trainId] that [trainId] physically occupies. */
	fun getOccupiedBlocks(trainId: String): List<DynamicTrackBlock> =
		trainToBlocks[trainId]?.filter { it.occupant?.name == trainId } ?: emptyList()

	/** Whether any train owns [block]. */
	fun isRegistered(block: DynamicTrackBlock): Boolean = blockToTrain.containsKey(block)

	/** Count of trains with at least one held block. */
	fun trainCount(): Int = trainToBlocks.size

	/** Count of held blocks across all trains. */
	fun blockCount(): Int = blockToTrain.size

	/** Drop every owner, held block and timestamp. */
	fun clear() {
		trainToBlocks.clear()
		blockToTrain.clear()
		blockRegisteredAt.clear()
	}
}
