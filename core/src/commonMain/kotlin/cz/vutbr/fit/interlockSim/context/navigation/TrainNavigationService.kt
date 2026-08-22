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

import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

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
	 * 4. If no topological path exists, return NoTopologicalPath (permanent condition)
	 * 5. If ANY block is not owned by this train, return OwnershipConflict (temporary condition)
	 * 6. If all blocks are owned, return Available with complete path (train continues)
	 *
	 * ## Result Semantics
	 *
	 * - **Available(path)**: Path exists and all blocks are reserved for this train
	 * - **NoTopologicalPath**: Network topology doesn't allow a path (permanent, train should stop)
	 * - **OwnershipConflict**: Blocks are reserved for different train (temporary, train should wait)
	 *
	 * ## Ownership Validation
	 *
	 * ```kotlin
	 * for (block in path.blocks) {
	 *     val owner = registry.getOwner(block)
	 *     if (owner != trainId) {
	 *         return PathResult.OwnershipConflict  // Block reserved for different train
	 *     }
	 * }
	 * return PathResult.Available(path)  // All blocks owned by this train
	 * ```
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * // In Train.Front.semaphoreAction():
	 * val result = env.getRoutingServices().getTrainNavigationService().findReservedPathForTrain(
	 *     trainId = toString(),  // "Train #1"
	 *     separator = semaphore
	 * )
	 *
	 * when (result) {
	 *     is PathResult.Available -> {
	 *         // Path is reserved for us, continue
	 *         accelerateToSignal(semaphore, result.path)
	 *     }
	 *     is PathResult.NoTopologicalPath -> {
	 *         // No path exists (dead-end or disconnected network)
	 *         logger.error("No topological path from $separator")
	 *         stopAndReportError()
	 *     }
	 *     is PathResult.OwnershipConflict -> {
	 *         // Blocks reserved for different train, wait
	 *         fireStop()
	 *         hold(5.0) // Wait and retry
	 *     }
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
	 * val result = trainNavService.findReservedPathForTrain(trainId, separator)
	 * ```
	 *
	 * The core path-finding logic remains unchanged; this method adds train-specific filtering
	 * and better error reporting.
	 *
	 * @param trainId Unique identifier for the train (typically Train.toString())
	 * @param separator Starting point (semaphore, InOut)
	 * @return PathResult indicating success (Available) or reason for failure
	 */
	fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator
	): PathResult

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
	 * @return true if path exists and all blocks are reserved for this train, false otherwise
	 */
	fun isPathReservedForTrain(
		trainId: String,
		separator: PathSeparator
	): Boolean

	/**
	 * Return up to [limit] oriented separators ahead of [separator] along the train's
	 * RESERVED route (`PathInfo.reservedPath`), in travel order, skipping intermediate
	 * non-oriented separators (e.g. switches) and the [TrackSection]s between them.
	 *
	 * Read-only: walks the registry's reserved path by index using the same `element == separator`
	 * matching as [findReservedPathForTrain] / `determineNextFromPathInfo`. Returns an empty list
	 * when the train has no `PathInfo`, [separator] is not on the reserved route, or there are no
	 * further oriented separators (the train is within one semaphore of its destination InOut).
	 *
	 * Used by SP2a.1 train perception (Issue #552) to expose the **second** signal ahead — the
	 * semaphore after the immediate `nextSemaphore()` — so a reactive train agent (SP2a.2) can
	 * derive předvěst / Výstraha semantics from the `(immediate, second)` aspect pair per SŽ D1
	 * (a distant signal encodes the next main signal's aspect; no dedicated `Výstraha` enum value
	 * is needed — see `TrainPerceptionReading`).
	 *
	 * @param trainId Train identifier (typically `Train.name`).
	 * @param separator Anchor separator already on the reserved route (typically `nextSemaphore()`).
	 * @param limit Max number of subsequent oriented separators to return (≥ 0).
	 * @return Subsequent oriented separators along the reserved route, in order; empty if none.
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	fun reservedSeparatorsAhead(
		trainId: String,
		separator: PathSeparator,
		limit: Int
	): List<OrientedPathSeparator>
}
