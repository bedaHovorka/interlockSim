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

import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Service for train-specific path navigation within reserved blocks.
 *
 * ## Purpose
 *
 * Provides train navigation logic that respects block ownership and reservation:
 * - Find paths through blocks RESERVED for a specific train
 * - Validate train ownership before returning navigation results
 * - Gracefully handle cases where path is not available (train waits)
 *
 * This is Phase 3 of the Path Discovery Restructuring (Issue #292).
 *
 * ## Design Principles
 *
 * - **Ownership validation**: Only return paths through blocks reserved for THIS train
 * - **Graceful waiting**: Return null when no valid path exists (train waits)
 * - **Registry integration**: Use PathReservationRegistry to check block ownership
 * - **Separation of concerns**: Path finding delegated to existing infrastructure
 *
 * ## Use Cases
 *
 * 1. **Train navigation** - Train requests path to next semaphore (only through owned blocks)
 * 2. **Wait on blocked path** - Train waits when blocks are reserved for different train
 * 3. **Resume on availability** - Train continues when path becomes available
 *
 * ## Comparison with PathReservationService
 *
 * | Aspect | PathReservationService | TrainNavigationService |
 * |---|---|---|
 * | **Purpose** | Reserve paths BEFORE train enters | Navigate paths AFTER reservation |
 * | **Ownership** | Creates ownership mappings | Validates existing ownership |
 * | **State changes** | Modifies block state (RESERVED) | Read-only (no state changes) |
 * | **Error handling** | Try alternative paths on conflict | Return null on ownership mismatch |
 * | **Used by** | Dispatcher/Generator (pre-entry) | Train (during travel) |
 *
 * ## Architecture Context
 *
 * ```
 * Dispatcher/Generator
 *   ↓
 * PathReservationService.reservePath(trainId, start, target)
 *   → Reserves blocks atomically
 *   → Registry tracks ownership
 *   ↓
 * Train enters network
 *   ↓
 * TrainNavigationService.findReservedPathForTrain(trainId, separator, next)
 *   → Validates blocks are RESERVED for trainId
 *   → Returns path only if ownership matches
 *   ↓
 * Train follows path through owned blocks
 * ```
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** All operations assume single-threaded access to the network state.
 *
 * @see PathReservationService
 * @see PathReservationRegistry
 * @since Issue #295 (Phase 3 of Issue #292)
 */
interface TrainNavigationService {
	/**
	 * Find path to next semaphore through blocks reserved for the specified train.
	 *
	 * ## Algorithm
	 *
	 * 1. Build path from separator through next section to next semaphore (existing pathToNextSemaphore logic)
	 * 2. Extract all track blocks from path
	 * 3. For each block, validate it is RESERVED for trainId (via PathReservationRegistry)
	 * 4. If ANY block is not owned by this train, return null (train waits)
	 * 5. If all blocks are owned, return complete path (train continues)
	 *
	 * ## Ownership Validation
	 *
	 * ```kotlin
	 * for (block in path.blocks) {
	 *     val owner = registry.getOwner(block)
	 *     if (owner != trainId) {
	 *         return null  // Block reserved for different train or not reserved
	 *     }
	 * }
	 * return path  // All blocks owned by this train
	 * ```
	 *
	 * ## Waiting Behavior
	 *
	 * When this method returns null:
	 * - Train should halt (fireStop)
	 * - Train waits for path to become available (waitUntil with condition)
	 * - Train retries periodically (via semaphore signal or timer)
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * // In Train.Front.semaphoreAction():
	 * val path = env.getTrainNavigationService().findReservedPathForTrain(
	 *     trainId = toString(),  // "Train #1"
	 *     separator = semaphore,
	 *     next = next
	 * )
	 *
	 * if (path == null) {
	 *     // Path not reserved for this train, halt and wait
	 *     fireStop()
	 *     waitUntil { pathBecomesAvailable(separator, next) }
	 * } else {
	 *     // Path is reserved for us, continue
	 *     accelerateToSignal(semaphore, path)
	 * }
	 * ```
	 *
	 * ## Relationship to pathToNextSemaphore
	 *
	 * This method wraps the existing `pathToNextSemaphore` logic with ownership validation:
	 *
	 * ```kotlin
	 * // Old approach (no ownership validation):
	 * val path = env.pathToNextSemaphore(separator, next)
	 *
	 * // New approach (with ownership validation):
	 * val path = trainNavService.findReservedPathForTrain(trainId, separator, next)
	 * ```
	 *
	 * The core path-finding logic remains unchanged; this method adds train-specific filtering.
	 *
	 * @param trainId Unique identifier for the train (typically Train.toString())
	 * @param separator Starting point (semaphore, InOut)
	 * @param next First track section in path
	 * @return Path to next semaphore if all blocks are reserved for this train, null otherwise
	 */
	fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator,
		next: TrackSection
	): Path?

	/**
	 * Check if a path to the next semaphore is currently reserved for the specified train.
	 *
	 * This is a read-only check that validates ownership without modifying state.
	 *
	 * ## Performance
	 *
	 * This method is optimized for repeated polling scenarios:
	 * - Validates block ownership without returning the full Path object
	 * - Early exit on first ownership conflict (avoids checking remaining blocks)
	 * - Lower memory footprint than findReservedPathForTrain
	 * - Uses same validation logic to ensure consistency
	 *
	 * ## Use Cases
	 *
	 * - Condition checks: `waitUntil { service.isPathReservedForTrain(...) }`
	 * - UI indicators: show green/red based on path availability
	 * - Polling: check periodically if path has become available
	 *
	 * @param trainId Unique identifier for the train
	 * @param separator Starting point
	 * @param next First track section
	 * @return true if path exists and all blocks are reserved for this train, false otherwise
	 */
	fun isPathReservedForTrain(
		trainId: String,
		separator: PathSeparator,
		next: TrackSection
	): Boolean
}
