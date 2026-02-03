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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

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
 * val registry = PathReservationRegistry(context)
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
 * @param context Simulation context (needed for PathInfo merging with ArrayPath)
 * @since Issue #294 (Phase 2 of Issue #292)
 * @since Issue #296 Phase 8 (PathInfo extension fix)
 */
class PathReservationRegistry(
	private val context: SimulationContext
) {
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
	 * Mapping: Train ID → PathInfo metadata
	 *
	 * Stores complete path information including entry directions for each train.
	 * This enables TrainNavigationService to determine correct direction at switches.
	 *
	 * @since Issue #295/#296 Phase 3
	 */
	private val trainToPathInfo = mutableMapOf<String, PathInfo>()

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
	 * Removes all bidirectional mappings for the given train, regardless of block state.
	 * This is used for simulation cleanup, test scenarios, and forced release.
	 *
	 * ## State Changes
	 *
	 * - Removes trainToBlocks[trainId]
	 * - Removes blockToTrain[block] for all blocks owned by this train
	 * - Removes trainToPathInfo[trainId] (Issue #295/#296)
	 *
	 * @param trainId The train identifier
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock> {
		val blocks = trainToBlocks[trainId] ?: return emptyList()

		// Remove all blocks from mappings (regardless of state)
		blocks.forEach { block ->
			blockToTrain.remove(block)
		}

		// Remove train entry and PathInfo
		trainToBlocks.remove(trainId)
		trainToPathInfo.remove(trainId)

		logger.debug {
			"unregister: Released ${blocks.size} blocks for '$trainId'"
		}

		return blocks.toList()
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry mappings if it is FREE (no occupant).
	 * This is called automatically when a train's Tail leaves a block, ensuring
	 * blocks are cleaned up as soon as they become available for subsequent trains.
	 *
	 * ## Preconditions
	 *
	 * - Block must be FREE (no occupant, state == FREE)
	 * - Block must be registered to the given train
	 *
	 * ## State Changes
	 *
	 * If block is FREE and owned by trainId:
	 * - Removes block from trainToBlocks[trainId]
	 * - Removes blockToTrain[block]
	 * - If this was the last block, removes trainToBlocks[trainId] (but keeps trainToPathInfo[trainId])
	 *
	 * ## PathInfo Lifecycle (Issue #301 Fix)
	 *
	 * PathInfo represents the train's **intended path** (where it's going) and is semantically
	 * separate from the train's **current reservations** (which blocks it owns). A train needs
	 * PathInfo for navigation queries even after all blocks are unregistered (e.g., when the
	 * tail has cleared all blocks but the front is requesting the next path segment).
	 *
	 * PathInfo is only deleted in:
	 * - `releaseTrainReservations()` - when train completes its journey
	 * - `unregister()` - explicit full unregistration
	 * - `clear()` - clear all registrations
	 *
	 * ## Use Case
	 *
	 * Called by Train's Tail process after calling block.leave():
	 * ```kotlin
	 * current.leave(this@Train)
	 * env.unregisterBlock(trainId, current)
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if block is still occupied or not owned
	 */
	fun unregisterBlock(trainId: String, block: DynamicTrackBlock): Boolean {
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

		// Remove from mappings
		blockToTrain.remove(block)
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

		return true
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
	 * Register PathInfo for a train.
	 *
	 * ## PathInfo Extension (Issue #296 Phase 8)
	 *
	 * Instead of overwriting the old PathInfo, this method **extends** it by merging:
	 * - Appends new path elements to old path (preserves Tail navigation)
	 * - Updates target to new target (allows Front to progress)
	 * - Merges entry directions (new overwrites old for conflicts)
	 *
	 * This fixes the "Train Tail Double-Leave Bug" where overwriting PathInfo caused
	 * the Tail to lose track of its position, leading to wrong-direction navigation.
	 *
	 * @param trainId The train identifier
	 * @param newPathInfo Complete path metadata to register or merge
	 * @since Issue #295/#296 Phase 3
	 * @since Issue #296 Phase 8 (PathInfo extension fix)
	 */
	fun registerPathInfo(trainId: String, newPathInfo: PathInfo) {
		val oldPathInfo = trainToPathInfo[trainId]

		if (oldPathInfo == null) {
			// First registration, just store it
			logger.debug {
				"registerPathInfo: first registration for '$trainId', storing PathInfo " +
					"(start=${newPathInfo.start}, target=${newPathInfo.target}, " +
					"path length=${newPathInfo.reservedPath.size})"
			}
			trainToPathInfo[trainId] = newPathInfo
			return
		}

		// Merge old and new PathInfo
		val mergedPathInfo = mergePathInfo(oldPathInfo, newPathInfo)
		trainToPathInfo[trainId] = mergedPathInfo

		logger.debug {
			"registerPathInfo: merged PathInfo for '$trainId' " +
				"(old: ${oldPathInfo.start}→${oldPathInfo.target}, " +
				"new: ${newPathInfo.start}→${newPathInfo.target}, " +
				"merged: ${mergedPathInfo.start}→${mergedPathInfo.target}, " +
				"path length: ${oldPathInfo.reservedPath.size} + ${newPathInfo.reservedPath.size} " +
				"= ${mergedPathInfo.reservedPath.size})"
		}

		// Log merged PathInfo (debug level for normal operation)
		logger.debug {
			"registerPathInfo: Created/merged PathInfo for '$trainId': " +
				"start=${mergedPathInfo.start}, target=${mergedPathInfo.target}, " +
				"path length=${mergedPathInfo.reservedPath.size}"
		}
	}

	/**
	 * Merge two PathInfo instances by extending old path with new path.
	 *
	 * ## Algorithm
	 *
	 * 1. Validate circular route assumption (new.start appears exactly once)
	 * 2. Create new ArrayPath and add all elements from old path
	 * 3. Find overlap point (old.target == new.start)
	 * 4. Add elements from new path, skipping first occurrence if it overlaps
	 * 5. Merge entry directions (new overwrites old for same blocks)
	 *
	 * ## Example
	 *
	 * ```
	 * old: B → zB → vB → doB1  (Tail at B, Front at doB1)
	 * new: doB1 → k1 → A       (Front reserves forward to A)
	 * merged: B → zB → vB → doB1 → k1 → A  (complete path for both Front and Tail)
	 * ```
	 *
	 * ## Assumptions
	 *
	 * - Railway networks are acyclic within a single path (no circular routes)
	 * - new.start appears exactly ONCE in new.reservedPath (at the beginning)
	 * - old.target and new.start may overlap (direct continuation)
	 *
	 * ## Circular Route Handling
	 *
	 * Circular routes (where start appears >1 time) are EXPLICITLY REJECTED
	 * with IllegalStateException. Railway interlocking systems typically prohibit
	 * circular routes within a single path reservation.
	 *
	 * ## Entry Direction Merging
	 *
	 * When old and new have the same block with different entry directions,
	 * the NEW direction overwrites the old (most recent direction is used).
	 *
	 * @param old Previous PathInfo (Tail may still be navigating through this)
	 * @param new New PathInfo (Front just reserved this)
	 * @return Merged PathInfo covering both old and new paths
	 * @throws IllegalStateException if new.start appears multiple times in path
	 * @since Issue #296 Phase 8
	 */
	private fun mergePathInfo(old: PathInfo, new: PathInfo): PathInfo {
		logger.trace { "mergePathInfo: merging old path ${old.start}->${old.target} with new ${new.start}->${new.target}" }

		// Step 0: Validate circular route assumption
		val occurrences = new.reservedPath.count { it == new.start }
		requireValidState(occurrences == 1) {
			"Circular routes not supported: new.start ($new.start) appears $occurrences times in path. " +
				"Expected exactly 1 occurrence at path beginning."
		}

		// Step 1: Create merged path starting from old path
		val mergedPath = ArrayPath(context)

		// Step 2: Add all elements from old path
		old.reservedPath.forEach { mergedPath.add(it) }

		// Step 3: Find overlap point (old.target == new.start)
		val skipFirst = (new.start == old.target)
		var skipped = false
		var cycleDetected = false
		var actualTarget: cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator = new.target

		// Step 4: Add elements from new path (skip first separator if overlapping, detect cycles)
		for (element in new.reservedPath) {
			if (cycleDetected) break  // Stop if cycle was detected

			if (skipFirst && !skipped && element == new.start) {
				skipped = true  // Skip this occurrence (already in old path)
				logger.trace {
					"mergePathInfo: skipping overlap element $element (old.target == new.start)"
				}
			} else {
				// Check for cycle: if this separator already exists in merged path, stop
				if (element is cz.vutbr.fit.interlockSim.objects.core.PathSeparator &&
					mergedPath.any { it == element }) {
					logger.info {
						"mergePathInfo: CYCLE DETECTED - separator $element already in merged path, " +
							"truncating to avoid infinite loop (old: ${old.start}→${old.target}, " +
							"new: ${new.start}→${new.target})"
					}
					cycleDetected = true
					// Update target to the last valid separator before the cycle
					// Find the last PathSeparator in mergedPath (before this cycle point)
					val lastSeparator = mergedPath.findLast { it is cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator }
					if (lastSeparator is cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator) {
						actualTarget = lastSeparator
						logger.info {
							"mergePathInfo: Updated target from ${new.target} to $actualTarget (last separator before cycle)"
						}
					}
					break  // Stop adding elements
				}
				mergedPath.add(element)
			}
		}

		// Step 5: Merge entry directions (new overwrites old for conflicts)
		val mergedDirections = old.entryDirections.toMutableMap()
		mergedDirections.putAll(new.entryDirections)

		logger.trace {
			"mergePathInfo: merged ${old.reservedPath.size} + ${new.reservedPath.size} " +
				"elements into ${mergedPath.size} elements " +
				"(overlap: ${if (skipFirst) "yes" else "no"}, cycle: ${if (cycleDetected) "yes" else "no"})"
		}

		return PathInfo(
			start = old.start,  // Keep original start (where Tail might still be)
			target = actualTarget,  // Use actual target (updated if cycle detected)
			reservedPath = mergedPath,
			entryDirections = mergedDirections
		)
	}

	/**
	 * Get PathInfo for a train.
	 *
	 * Retrieves complete path metadata including entry directions.
	 * Used by TrainNavigationService to determine correct direction.
	 *
	 * @param trainId The train identifier
	 * @return PathInfo if registered, null otherwise
	 * @since Issue #295/#296 Phase 3
	 */
	fun getPathInfo(trainId: String): PathInfo? = trainToPathInfo[trainId]

	/**
	 * Clear all registrations.
	 *
	 * Removes all train-to-block and block-to-train mappings.
	 * Used for simulation reset or cleanup.
	 */
	fun clear() {
		trainToBlocks.clear()
		blockToTrain.clear()
		trainToPathInfo.clear()
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
