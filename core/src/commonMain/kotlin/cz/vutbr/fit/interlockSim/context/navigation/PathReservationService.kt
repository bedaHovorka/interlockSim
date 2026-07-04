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

import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Service for finding and reserving free paths in railway network.
 *
 * ## Purpose
 *
 * Provides dispatcher logic for path reservation with atomic all-or-nothing semantics:
 * - Find candidate paths using TopologyNavigator
 * - Filter by block availability (must be FREE)
 * - Reserve blocks atomically (rollback on partial failure)
 * - Track train ownership of blocks
 *
 * This is Phase 2 of the Path Discovery Restructuring (Issue #292).
 *
 * ## Atomic Reservation Guarantee
 *
 * All reservation operations are atomic:
 * - **Success**: All blocks reserved, train owns the path
 * - **Partial failure**: All changes rolled back, no blocks reserved
 * - **Conflict detection**: Prevents multiple trains from reserving same block
 *
 * ## Design Principles
 *
 * - **Stateless operations**: Each method is independent, no internal state
 * - **Registry separation**: Ownership tracking delegated to PathReservationRegistry
 * - **Navigator delegation**: Path finding delegated to TopologyNavigator
 * - **Transaction semantics**: Either all blocks reserved or none
 *
 * ## Use Cases
 *
 * 1. **Train path setup** - Reserve path before train enters network
 * 2. **Dynamic rerouting** - Find and reserve alternative path when preferred route is blocked
 * 3. **Conflict resolution** - Detect and prevent path conflicts between trains
 * 4. **Path release** - Free all blocks when train completes journey
 *
 * ## Separation of Concerns
 *
 * This interface is intentionally separated from:
 * - **Static topology** - Path finding (handled by TopologyNavigator)
 * - **Train navigation** - Following reserved paths (handled by TrainNavigationService in Phase 3)
 * - **Simulation execution** - Runtime state management (handled by SimulationContext)
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** All operations assume single-threaded access to the network state.
 *
 * @see TopologyNavigator
 * @see PathReservationRegistry
 * @since Issue #294 (Phase 2 of Issue #292)
 */
interface PathReservationService {
	/**
	 * Result of a path reservation attempt.
	 *
	 * ## Success Case
	 *
	 * ```kotlin
	 * val result = service.reservePath(trainId, start, target)
	 * if (result is ReservationResult.Success) {
	 *     val blocks = result.reservedBlocks
	 *     // Train can now enter the path
	 * }
	 * ```
	 *
	 * ## Failure Cases
	 *
	 * - **NoPathExists**: No topological route exists between start and target
	 * - **AllPathsBlocked**: Route(s) exist but all are occupied/reserved
	 * - **Conflict**: Attempted to reserve block already owned by different train
	 */
	sealed class ReservationResult {
		/**
		 * Reservation succeeded. All blocks are now reserved for the train.
		 *
		 * @property reservedBlocks List of blocks reserved in path order (start to target)
		 */
		data class Success(
			val reservedBlocks: List<DynamicTrackBlock>
		) : ReservationResult()

		/**
		 * No topological path exists between start and target.
		 *
		 * This indicates a network topology issue (dead-end, disconnected network).
		 */
		data object NoPathExists : ReservationResult()

		/**
		 * Path(s) exist but all are blocked (OCCUPIED or RESERVED by other trains).
		 *
		 * @property attemptedPaths Number of candidate paths that were checked
		 */
		data class AllPathsBlocked(
			val attemptedPaths: Int
		) : ReservationResult()

		/**
		 * Reservation conflict detected during atomic reservation.
		 *
		 * This indicates a race condition or logic error. In a well-designed system,
		 * this should not occur because availability is checked before reservation.
		 *
		 * @property conflictingBlock The block that caused the conflict
		 * @property existingOwner The train that already owns the block
		 */
		data class Conflict(
			val conflictingBlock: DynamicTrackBlock,
			val existingOwner: String
		) : ReservationResult()
	}

	/**
	 * Find and reserve a free path from start to target separator.
	 *
	 * ## Algorithm
	 *
	 * 1. Use TopologyNavigator to find all possible paths
	 * 2. For each path, extract the sequence of track blocks
	 * 3. Check if all blocks in path are FREE
	 * 4. If found, atomically reserve all blocks for the train
	 * 5. If reservation fails, rollback and try next path
	 * 6. If all paths blocked, return failure result
	 *
	 * ## Atomic Guarantee
	 *
	 * Partial failures are automatically rolled back:
	 * ```kotlin
	 * // If block3 is OCCUPIED, blocks 1-2 are released
	 * val result = service.reservePath("train1", inOut1, inOut2)
	 * // Either all blocks reserved, or none
	 * ```
	 *
	 * ## Multiple Paths
	 *
	 * If multiple paths exist (via switches), this method tries them in order
	 * until a free path is found. The order is determined by TopologyNavigator's
	 * BFS traversal (typically shortest path first).
	 *
	 * @param trainId Unique identifier for the train (typically Train.toString())
	 * @param start Starting path separator (typically InOut or semaphore)
	 * @param target Target path separator to reach
	 * @param maxDepth Maximum search depth for path finding (default: 100)
	 * @return ReservationResult indicating success or failure reason
	 */
	fun reservePath(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int = 100
	): ReservationResult

	/**
	 * Release all blocks reserved by a train.
	 *
	 * This operation is idempotent - calling it multiple times for the same train
	 * is safe (subsequent calls do nothing).
	 *
	 * ## State Changes
	 *
	 * For each block owned by the train:
	 * - Block state transitions from RESERVED to FREE
	 * - Block.trainId set to null
	 * - Ownership removed from registry
	 *
	 * ## Use Cases
	 *
	 * - Train completes journey (exits network)
	 * - Train cancels path before entering
	 * - Simulation cleanup/reset
	 *
	 * @param trainId Unique identifier for the train
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun releasePath(trainId: String): List<DynamicTrackBlock>

	/**
	 * Emit [BlockEvent.ReservationConflictDetected] for every blocked-path contention
	 * that is still unresolved when the simulation ends, without ever having looked like
	 * a live concurrent claim while the run was in progress. This is the "unresolved by
	 * end of run" signal for genuine, never-clearing contention (e.g. a real deadlock),
	 * as distinct from routine "train waits its turn, then proceeds" contention -- however
	 * long that takes mid-run -- which always resolves (via [reservePath] `Success` or
	 * [releasePath]) before the run ends and is therefore never reported this way.
	 *
	 * Must be called exactly once, after [cz.vutbr.fit.interlockSim.context.SimulationContext.run]
	 * has fully returned (see [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext]'s
	 * post-loop cleanup). The caller is responsible for delivering the returned events to
	 * whatever is listening for [BlockEvent]s -- this method intentionally does not emit
	 * them onto the simulation's own event bus, since that bus stops delivering events once
	 * the run has ended.
	 *
	 * @param simulationEndTime Simulation clock value to stamp on the returned event(s).
	 * @return Events for contention that was never resolved and never looked like a live
	 *         race; empty if there is none.
	 * @since Issue #612 (Goal 3 SP2 follow-up)
	 */
	fun flushUnresolvedConflicts(simulationEndTime: Double): List<BlockEvent.ReservationConflictDetected>

	/**
	 * Check if a path is currently available (all blocks FREE).
	 *
	 * This is a read-only operation that does NOT reserve the path.
	 *
	 * ## Use Cases
	 *
	 * - Preview availability before committing to reservation
	 * - UI indicators (show available routes)
	 * - Network validation (check connectivity)
	 *
	 * @param start Starting path separator
	 * @param target Target path separator
	 * @param maxDepth Maximum search depth for path finding (default: 100)
	 * @return true if at least one free path exists, false otherwise
	 */
	fun isPathAvailable(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int = 100
	): Boolean

	/**
	 * Get all blocks currently reserved by a train.
	 *
	 * @param trainId Unique identifier for the train
	 * @return List of blocks reserved by this train (empty if no reservations)
	 */
	fun getReservedBlocks(trainId: String): List<DynamicTrackBlock>

	/**
	 * Find and reserve path from separator to any next semaphore via specific track section.
	 *
	 * This method navigates from the starting separator through the given track section
	 * to find ALL reachable semaphores. If the network has switches creating multiple routes,
	 * it tries to reserve a path to each semaphore until one succeeds.
	 *
	 * ## Algorithm
	 *
	 * 1. Find ALL semaphores reachable from `start` via `next` track section
	 * 2. For each semaphore, attempt `reservePath(trainId, start, semaphore)`
	 * 3. Return Success on first successful reservation
	 * 4. Return last failure result if all attempts fail
	 *
	 * ## Use Case: Train Entry (InOutWorker)
	 *
	 * When a train enters the network via an InOut point:
	 * ```kotlin
	 * val next = navigator.getNextTrackSection(inOut, null)  // First section after InOut
	 * val result = service.reservePathToAnyNextSemaphore("train1", inOut, next)
	 * when (result) {
	 *     is Success -> // Train can enter, path reserved
	 *     is NoPathExists -> // No semaphore found in this direction
	 *     is AllPathsBlocked -> // Semaphore found but path occupied
	 *     is Conflict -> // Reservation conflict (should not happen)
	 * }
	 * ```
	 *
	 * ## Multiple Path Handling
	 *
	 * If the railway network has switches creating multiple routes to different semaphores,
	 * this method will try each route in order. The first free path is reserved, using the
	 * same multi-path fallback logic as `reservePath()` (BFS = shortest first).
	 *
	 * @param trainId Unique identifier for the train
	 * @param start Starting path separator (typically InOut)
	 * @param next First track section after the start (direction to search)
	 * @return ReservationResult indicating success or failure reason
	 * @see reservePath
	 * @see isPathToAnyNextSemaphoreAvailable
	 */
	fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: DynamicPathSeparator,
		next: TrackSection
	): ReservationResult

	/**
	 * Find and reserve path from oriented separator to any next semaphore.
	 *
	 * This method is similar to `reservePathToAnyNextSemaphore()` but works with
	 * oriented separators (e.g., semaphores) that have a defined direction.
	 * It automatically determines the next track section based on the orientation.
	 *
	 * ## Algorithm
	 *
	 * 1. Determine `next` track section based on `start` orientation
	 * 2. Find ALL semaphores reachable from `start` via `next` track section
	 * 3. For each semaphore, attempt ` reservePath(trainId, start, semaphore)`
	 * 4. Return Success on first successful reservation
	 * 5. Return last failure result if all attempts fail
	 *
	 * ## Use Case: Semaphore Forward Path
	 *
	 * When a train approaches a semaphore and needs a forward path:
	 * ```kotlin
	 * val result = service.reservePathToAnyNextSemaphore("train1", semaphore)
	 * when (result) {
	 *     is Success -> // Train can proceed, path reserved
	 *     is NoPathExists -> // No semaphore found in this direction
	 *     is AllPathsBlocked -> // Semaphore found but path occupied
	 *     is Conflict -> // Reservation conflict (should not happen)
	 * }
	 * ```
	 * ## Automatic Next Section Discovery
	 *
	 * This method automatically finds the next track section based on the orientation
	 * of the starting separator. It uses [OrientedPathSeparator.direction] to determine
	 * the forward segment, then queries the graph for the track section connected to that
	 * segment. This ensures path discovery follows the separator's intended direction.
	 *
	 * This simplifies usage for oriented elements like semaphores, where the direction
	 * is inherent to the element itself.
	 *
	 * @param trainId Unique identifier for the train
	 * @param start Starting oriented path separator (typically a semaphore)
	 * @return ReservationResult indicating success or failure reason
	 * @see reservePath
	 * @see isPathToAnyNextSemaphoreAvailable
	 */
	fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: OrientedPathSeparator
	): ReservationResult

	/**
	 * Check if a path from separator to any next semaphore is currently available.
	 *
	 * This is a read-only operation that does NOT reserve the path. It's used as a
	 * polling condition in InOutWorker to wait until a path becomes free.
	 *
	 * ## Algorithm
	 *
	 * 1. If `next` is null, return false (no direction to search)
	 * 2. Find ALL semaphores reachable from `start` via `next` track section
	 * 3. For each semaphore, check `isPathAvailable(start, semaphore)`
	 * 4. Return true if ANY semaphore has a free path, false otherwise
	 *
	 * ## Use Case: kDisco Condition Polling
	 *
	 * ```kotlin
	 * private val pathFree = Condition {
	 *     service.isPathToAnyNextSemaphoreAvailable(inOut, next)
	 * }
	 * waitUntil(pathFree)  // Wait until path becomes free
	 * ```
	 *
	 * ## Null Handling
	 *
	 * Unlike `reservePathToAnyNextSemaphore()`, this method accepts nullable `next`:
	 * - `next == null` → returns false (no direction to search)
	 * - This matches the pattern from the working tag where null next → no path
	 *
	 * @param start Starting path separator
	 * @param next First track section after start (null if none exists)
	 * @return true if path exists and is available (all blocks FREE), false otherwise
	 * @see isPathAvailable
	 * @see reservePathToAnyNextSemaphore
	 */
	fun isPathToAnyNextSemaphoreAvailable(
		start: PathSeparator,
		next: TrackSection?
	): Boolean

	/**
	 * Find and reserve path from separator to ANY available target.
	 *
	 * Discovers all potential targets (semaphores and InOuts) and tries each
	 * until a successful reservation is made. Returns the first successful path.
	 *
	 * ## Algorithm
	 *
	 * 1. Discover all potential targets (InOuts and semaphores) from the context
	 * 2. For each target, attempt `reservePath(trainId, start, target)`
	 * 3. Return Success with first available path
	 * 4. If all targets fail, return NoPath (all blocked or no targets exist)
	 *
	 * ## Use Case: Dispatcher Logic (ShuntingLoop)
	 *
	 * When a train approaches a semaphore and needs a forward path:
	 * ```kotlin
	 * val result = service.reservePathToAny("train1", semaphore)
	 * when (result) {
	 *     is Success -> // Train can proceed, path reserved
	 *     is NoPath -> // No available targets
	 *     is AllPathsBlocked -> // Targets exist but all blocked
	 *     is Conflict -> // Reservation conflict (should not happen)
	 * }
	 * ```
	 *
	 * ## Target Discovery
	 *
	 * Targets are discovered automatically from the context:
	 * - All InOut elements except the start
	 * - All oriented semaphores (RailSemaphore) except the start
	 *
	 * ## Advantages Over Manual Iteration
	 *
	 * - No hardcoded grid dimensions (50×20)
	 * - No type casting to SimulationContext
	 * - Single responsibility (all target discovery in service layer)
	 * - Automatic semaphore signal configuration
	 *
	 * @param trainId Unique identifier for the train
	 * @param start Starting path separator (typically a semaphore)
	 * @return ReservationResult indicating success or failure reason
	 * @see reservePath
	 */
	fun reservePathToAny(
		trainId: String,
		start: DynamicPathSeparator
	): ReservationResult

	/**
	 * Unregister all block reservations for a train.
	 *
	 * Removes all blocks owned by the specified train from the registry,
	 * freeing them for subsequent trains. This is called when a train
	 * completes its journey and reaches its destination.
	 *
	 * ## Use Case
	 *
	 * Called by train cleanup when journey completes:
	 * ```kotlin
	 * pathService.unregister("train1")
	 * ```
	 *
	 * @param trainId The train identifier to unregister
	 * @return List of blocks that were released
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock>

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry mappings if it is FREE (no occupant).
	 * This is called automatically when a train's Tail leaves a block, ensuring
	 * blocks are cleaned up as soon as they become available for subsequent trains.
	 *
	 * ## Use Case
	 *
	 * Called by Train's Tail process after calling block.leave():
	 * ```kotlin
	 * current.leave(this@Train)
	 * pathService.unregisterBlock(trainId, current)
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if block is still occupied or not owned
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean

	/**
	 * Subscribe an external agent to block occupancy/release events.
	 *
	 * Events are emitted whenever a block's reservation or occupancy state changes.
	 */
	fun addBlockOccupancyListener(listener: BlockOccupancyListener)

	/**
	 * Unsubscribe a previously registered external agent.
	 */
	fun removeBlockOccupancyListener(listener: BlockOccupancyListener)
}
