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

import cz.vutbr.fit.interlockSim.objects.paths.Path

/**
 * Result type for train navigation path requests.
 *
 * ## Purpose
 *
 * Distinguishes between different reasons why a path might not be available,
 * enabling better error handling and diagnostics.
 *
 * ## Variants
 *
 * - **Available**: Path exists and all blocks are reserved for the requesting train
 * - **NoTopologicalPath**: No path exists in the network topology (permanent condition)
 * - **OwnershipConflict**: Path exists but blocks are reserved for a different train (temporary condition)
 *
 * ## Semantics
 *
 * ### Permanent vs Temporary Conditions
 *
 * - **NoTopologicalPath** represents a permanent condition:
 *   - Network topology doesn't allow a path from current position to target
 *   - No amount of waiting will resolve this condition
 *   - Train should either stop (dead-end) or take alternative route
 *
 * - **OwnershipConflict** represents a temporary condition:
 *   - Path exists topologically but blocks are currently reserved for different train
 *   - Condition may resolve when other train releases blocks
 *   - Train should wait and retry periodically
 *
 * ## Usage Example
 *
 * ```kotlin
 * val result = trainNavService.findReservedPathForTrain(trainId, separator)
 * when (result) {
 *     is PathResult.Available -> {
 *         // Path is ready, continue moving
 *         accelerateToSignal(semaphore, result.path)
 *     }
 *     is PathResult.NoTopologicalPath -> {
 *         // Permanent condition - no path exists
 *         logger.error("Train $trainId: No topological path from $separator")
 *         stopAndReportError()
 *     }
 *     is PathResult.OwnershipConflict -> {
 *         // Temporary condition - wait for blocks to become available
 *         logger.debug("Train $trainId: Path blocked, waiting for dispatcher")
 *         fireStop()
 *         hold(5.0) // Wait and retry
 *     }
 * }
 * ```
 *
 * ## Migration from Nullable Path
 *
 * Previous API returned nullable `Path?`:
 * - `null` → now either `NoTopologicalPath` or `OwnershipConflict`
 * - `Path` → now `Available(path)`
 *
 * This sealed class provides better type safety and clearer semantics than
 * overloading `null` to mean multiple different conditions.
 *
 * @see TrainNavigationService.findReservedPathForTrain
 * @since Issue #311 (Null Path Semantics Clarification)
 */
sealed class PathResult {
	/**
	 * Path is available and all blocks are reserved for the requesting train.
	 *
	 * @param path Complete path from current position to next semaphore
	 */
	data class Available(
		val path: Path
	) : PathResult()

	/**
	 * No topological path exists from current position to target.
	 *
	 * This is a **permanent** condition - the network topology doesn't allow
	 * a path between the current position and the target. No amount of waiting
	 * will resolve this condition.
	 *
	 * Possible causes:
	 * - Dead-end track with no continuation
	 * - Disconnected network segments
	 * - Single InOut with no outbound connections
	 * - Malformed network topology
	 *
	 * Train behavior: Should stop permanently or take alternative route.
	 */
	data object NoTopologicalPath : PathResult()

	/**
	 * Path exists topologically but blocks are reserved for a different train.
	 *
	 * This is a **temporary** condition - blocks may become available when
	 * the other train releases them.
	 *
	 * Possible causes:
	 * - Blocks reserved for different train
	 * - No PathInfo registered for this train (dispatcher hasn't reserved path yet)
	 * - Partial ownership (train owns some but not all blocks in path)
	 *
	 * Train behavior: Should wait and retry periodically.
	 */
	data object OwnershipConflict : PathResult()
}
