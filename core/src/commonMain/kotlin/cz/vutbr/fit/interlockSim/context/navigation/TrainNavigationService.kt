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

import cz.ksimulantenbande.kdisco.Condition
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

	/**
	 * Build a kDisco [Condition] that is true while a path from [separator] is reserved for
	 * [trainId].
	 *
	 * ## Why the factory is here (Issue #931 f2)
	 *
	 * kDisco re-tests a `waitUntil` predicate after every discrete event **and** every integration
	 * step, so a parked train re-ran a whole [findReservedPathForTrain] tens of thousands of times
	 * per run: 10,800-40,490 evaluations per 333 s GUI run, 39k-75k headless. An implementation
	 * that knows when its inputs last changed can skip almost all of them; a caller building a bare
	 * lambda cannot. Hence the factory, rather than each caller writing the condition itself.
	 *
	 * This default body is the unoptimised one, and it is deliberately kept: several test doubles
	 * implement this interface directly, and they must keep working without knowing anything about
	 * caching. [DefaultTrainNavigationService] overrides it.
	 *
	 * ## Contract for overrides
	 *
	 * An override may cache the **boolean answer**. It must never cache the
	 * [PathResult.Available] path object: `Train.Site.separatorAction` mutates the path it is
	 * given (`removeFirst()`), which is safe only because every evaluation builds a fresh one.
	 *
	 * @param trainId The train that is waiting.
	 * @param separator Where it is waiting.
	 * @return a condition that is true exactly when [findReservedPathForTrain] returns
	 *   [PathResult.Available].
	 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
	 */
	fun createPathAvailableCondition(
		trainId: String,
		separator: PathSeparator
	): Condition = Condition { findReservedPathForTrain(trainId, separator) is PathResult.Available }

	/**
	 * How many path-available condition tests this service served, and how many of them it had to
	 * answer by really evaluating a path.
	 *
	 * The gap between the two is what Issue #931 f2 bought. Defaults to
	 * [PathEvaluationStats.UNMEASURED] because a service that does not count is *not measuring*,
	 * which is not the same as measuring zero — the same convention
	 * `cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome` follows.
	 *
	 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
	 */
	fun evaluationStats(): PathEvaluationStats = PathEvaluationStats.UNMEASURED
}

/**
 * How often a [TrainNavigationService] was asked whether a path was available, and how often it
 * had to work the answer out.
 *
 * Both fields are nullable and `null` means **not measured**, never "measured as none". A service
 * that keeps no counters must not report `0`, because a run with zero condition tests is a genuine
 * (and alarming) finding, while an uninstrumented service is simply silent.
 *
 * @property conditionTests Times a path-available condition was tested.
 * @property realEvaluations Times that test could not be answered from the cached epoch and ran a
 *   full [TrainNavigationService.findReservedPathForTrain].
 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
 */
data class PathEvaluationStats(
	val conditionTests: Long? = null,
	val realEvaluations: Long? = null
) {
	/**
	 * Fraction of condition tests answered without a path evaluation, or `null` when either figure
	 * is unmeasured or no test happened at all.
	 */
	val cacheHitRate: Double?
		get() {
			val tests = conditionTests ?: return null
			val real = realEvaluations ?: return null
			if (tests <= 0L) return null
			return (tests - real).toDouble() / tests.toDouble()
		}

	companion object {
		/** The all-absent stats: this service counts nothing. */
		val UNMEASURED: PathEvaluationStats = PathEvaluationStats()
	}
}
