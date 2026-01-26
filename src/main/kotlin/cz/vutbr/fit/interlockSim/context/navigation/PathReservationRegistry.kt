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
 * Registry tracking train ownership of reserved track blocks.
 *
 * ## Purpose
 *
 * Maintains bidirectional mapping between trains and their reserved track blocks:
 * - Train ID → List of reserved blocks
 * - Block → Owning train ID
 *
 * This enables:
 * - Conflict detection (block already reserved by different train)
 * - Atomic rollback (release all blocks when reservation fails)
 * - Path release (free all blocks reserved by a train)
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * val registry = PathReservationRegistry()
 *
 * // Reserve blocks for a train
 * registry.register("train123", listOf(block1, block2, block3))
 *
 * // Check ownership
 * val owner = registry.getOwner(block1)  // returns "train123"
 *
 * // Release all blocks for a train
 * val released = registry.unregister("train123")
 * ```
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** All operations assume single-threaded access.
 *
 * @since Issue #294 (Phase 2 of Issue #292)
 */
class PathReservationRegistry {
	/**
	 * Mapping: Train ID → List of reserved blocks
	 */
	private val trainToBlocks = mutableMapOf<String, MutableList<DynamicTrackBlock>>()

	/**
	 * Mapping: Block → Owning train ID
	 */
	private val blockToTrain = mutableMapOf<DynamicTrackBlock, String>()

	/**
	 * Register blocks as reserved by a train.
	 *
	 * This creates bidirectional mappings for all blocks in the list.
	 * If the train already has reserved blocks, the new blocks are added to the existing list.
	 *
	 * ## Preconditions
	 *
	 * - No block in the list should be already owned by a different train
	 * - Caller is responsible for validating blocks are FREE before registration
	 *
	 * ## State Changes
	 *
	 * For each block:
	 * - Adds block to trainToBlocks[trainId]
	 * - Sets blockToTrain[block] = trainId
	 *
	 * @param trainId The train identifier (typically Train.getClass().getSimpleName() + instance)
	 * @param blocks List of blocks to register as reserved
	 * @throws IllegalStateException if any block is already registered to a different train
	 */
	fun register(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		// Validate no conflicts
		blocks.forEach { block ->
			val existingOwner = blockToTrain[block]
			if (existingOwner != null && existingOwner != trainId) {
				throw IllegalStateException(
					"Block $block already reserved by $existingOwner (attempted reservation by $trainId)"
				)
			}
		}

		// Register all blocks
		val blockList = trainToBlocks.getOrPut(trainId) { mutableListOf() }
		blocks.forEach { block ->
			if (block !in blockList) {
				blockList.add(block)
			}
			blockToTrain[block] = trainId
		}
	}

	/**
	 * Unregister all blocks reserved by a train.
	 *
	 * Removes all bidirectional mappings for the given train.
	 *
	 * ## State Changes
	 *
	 * - Removes trainToBlocks[trainId]
	 * - Removes blockToTrain[block] for all blocks owned by this train
	 *
	 * @param trainId The train identifier
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock> {
		val blocks = trainToBlocks.remove(trainId) ?: return emptyList()

		// Remove reverse mappings
		blocks.forEach { block ->
			blockToTrain.remove(block)
		}

		return blocks
	}

	/**
	 * Get the train ID that owns a block.
	 *
	 * @param block The block to query
	 * @return Train ID if block is registered, null otherwise
	 */
	fun getOwner(block: DynamicTrackBlock): String? = blockToTrain[block]

	/**
	 * Get all blocks reserved by a train.
	 *
	 * @param trainId The train identifier
	 * @return List of blocks reserved by this train (empty if no reservations)
	 */
	fun getBlocks(trainId: String): List<DynamicTrackBlock> =
		trainToBlocks[trainId]?.toList() ?: emptyList()

	/**
	 * Check if a block is registered to any train.
	 *
	 * @param block The block to check
	 * @return true if block is registered, false otherwise
	 */
	fun isRegistered(block: DynamicTrackBlock): Boolean = blockToTrain.containsKey(block)

	/**
	 * Clear all registrations.
	 *
	 * Removes all train-to-block and block-to-train mappings.
	 * Used for simulation reset or cleanup.
	 */
	fun clear() {
		trainToBlocks.clear()
		blockToTrain.clear()
	}

	/**
	 * Get total number of registered trains.
	 *
	 * @return Count of trains with at least one reserved block
	 */
	fun trainCount(): Int = trainToBlocks.size

	/**
	 * Get total number of registered blocks across all trains.
	 *
	 * @return Count of blocks currently reserved
	 */
	fun blockCount(): Int = blockToTrain.size
}
