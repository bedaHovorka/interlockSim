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
 * ## Usage Pattern (New Atomic API)
 *
 * ```kotlin
 * val registry = PathReservationRegistry()
 *
 * // Attempt atomic registration
 * when (val result = registry.registerAtomic("train123", listOf(block1, block2, block3))) {
 *     is RegistrationResult.Success -> {
 *         // All blocks registered successfully
 *     }
 *     is RegistrationResult.Conflict -> {
 *         // Conflict detected: result.conflictingBlock, result.existingOwner
 *         // No blocks were registered (all-or-nothing)
 *     }
 * }
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
	 * Result of an atomic registration attempt.
	 *
	 * This sealed class provides type-safe result handling for registration operations,
	 * replacing exception-based control flow with explicit result types.
	 */
	sealed class RegistrationResult {
		/**
		 * All blocks successfully registered.
		 *
		 * When this result is returned, all blocks in the registration request
		 * have been added to the registry and are now owned by the train.
		 */
		data object Success : RegistrationResult()

		/**
		 * Registration conflict detected.
		 *
		 * At least one block in the registration request was already owned by
		 * a different train. No blocks were registered (all-or-nothing semantics).
		 *
		 * @property conflictingBlock The first block that caused the conflict
		 * @property existingOwner The train that already owns the conflicting block
		 */
		data class Conflict(
			val conflictingBlock: DynamicTrackBlock,
			val existingOwner: String
		) : RegistrationResult()
	}

	/**
	 * Mapping: Train ID → List of reserved blocks
	 */
	private val trainToBlocks = mutableMapOf<String, MutableList<DynamicTrackBlock>>()

	/**
	 * Mapping: Block → Owning train ID
	 */
	private val blockToTrain = mutableMapOf<DynamicTrackBlock, String>()

	/**
	 * Atomically register blocks for a train.
	 *
	 * Validates no conflicts exist, then registers all blocks. If any conflict
	 * is detected, no blocks are registered (all-or-nothing semantics).
	 *
	 * ## Atomicity Guarantee
	 *
	 * This operation is atomic with respect to the registry state:
	 * - Either all blocks are registered
	 * - Or no blocks are registered
	 *
	 * However, note that block state (reservedFrom, trainId) is managed
	 * separately by DynamicTrackBlock and must be rolled back by the caller
	 * if registration fails.
	 *
	 * ## Conflict Detection
	 *
	 * A conflict occurs when any block is already owned by a **different** train.
	 * If the same train attempts to register the same block again, it is allowed
	 * (idempotent operation).
	 *
	 * ## Usage Example
	 *
	 * ```kotlin
	 * when (val result = registry.registerAtomic("train1", blocks)) {
	 *     is RegistrationResult.Success -> {
	 *         logger.info { "All blocks registered" }
	 *     }
	 *     is RegistrationResult.Conflict -> {
	 *         logger.warn { "Block ${result.conflictingBlock} owned by ${result.existingOwner}" }
	 *         rollbackBlockReservations(blocks)
	 *     }
	 * }
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param blocks List of blocks to register
	 * @return Success if all registered, Conflict with details if any conflict
	 */
	fun registerAtomic(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	): RegistrationResult {
		// Phase 1: Validate no conflicts
		for (block in blocks) {
			val existingOwner = blockToTrain[block]
			if (existingOwner != null && existingOwner != trainId) {
				return RegistrationResult.Conflict(block, existingOwner)
			}
		}

		// Phase 2: All checks passed - register all blocks
		val blockList = trainToBlocks.getOrPut(trainId) { mutableListOf() }
		blocks.forEach { block ->
			if (block !in blockList) {
				blockList.add(block)
			}
			blockToTrain[block] = trainId
		}

		return RegistrationResult.Success
	}

	/**
	 * Register blocks as reserved by a train.
	 *
	 * **DEPRECATED:** Use registerAtomic() for better error handling.
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
	 * @deprecated Use registerAtomic() for explicit result handling instead of exceptions
	 */
	@Deprecated(
		message = "Use registerAtomic() for better error handling",
		replaceWith = ReplaceWith("registerAtomic(trainId, blocks)")
	)
	fun register(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		// Delegate to registerAtomic() and convert result to exception for backward compatibility
		when (val result = registerAtomic(trainId, blocks)) {
			is RegistrationResult.Success -> return
			is RegistrationResult.Conflict -> {
				throw IllegalStateException(
					"Block ${result.conflictingBlock} already reserved by ${result.existingOwner} " +
						"(attempted reservation by $trainId)"
				)
			}
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
