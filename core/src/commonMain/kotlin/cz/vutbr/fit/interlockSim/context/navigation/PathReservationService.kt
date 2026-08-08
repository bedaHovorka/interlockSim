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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
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
	 * - **NonContiguousStart**: The start separator is nowhere near the requesting train
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

		/**
		 * The requested `start` separator is not contiguous with the requesting train's current
		 * authority: it bounds none of the blocks the train holds in the
		 * [PathReservationRegistry] and none of the blocks it physically occupies.
		 *
		 * Reserving such a route would lock track somewhere the train is not. The train cannot
		 * reach it, so it never occupies and never releases those blocks; every other train is
		 * held out of them until an orphan sweeper (if any) reclaims the reservation. Observed
		 * live on `exampleGui shuntingLoopAI 333`, where a correctly *directed* but wrongly
		 * *placed* route stalled the whole station (Issue #893).
		 *
		 * Deliberately distinct from [AllPathsBlocked]: that one is ordinary contention and a
		 * caller should simply retry next tick, whereas this one will never succeed while the
		 * train stays where it is — the caller (or the LLM dispatcher behind it) has to ask for
		 * a different origin. Collapsing the two hides a dispatcher-output defect inside a
		 * routine-traffic counter.
		 *
		 * A train with **no** footprint at all (neither registered nor occupied blocks) is not
		 * subject to this check: that is a train still waiting outside the network, whose route
		 * legitimately starts at an entry InOut.
		 *
		 * ## ⚠ Half the malformation, by ruling
		 *
		 * That exemption is why this result covers only trains that are already **on** the
		 * network. The other half of Issue #893 — a route requested from a mid-station Signal for
		 * a train still **queued** for admission — has an empty footprint and passes vacuously
		 * here. It is guarded solely at the tool layer, by
		 * `RequestRouteTool.queuedOriginError`, which itself self-disables when that tool is
		 * built with no InOut-name set or with no `DispatchLoopSensorPort`. Callers reaching
		 * `reservePath` by any other route get no protection against the queued-train form.
		 *
		 * Tightening the vacuous arm to close it would reject every legitimate train-entry
		 * reservation, so the split is deliberate (binding traffic-simulation-expert ruling).
		 *
		 * @property startName Name of the offending start separator (or its `toString()` when
		 *   the separator carries no name).
		 * @property reason English explanation naming the start and, when the train has a
		 *   footprint, the separators that *would* have been legal starts.
		 * @since Issue #893 (phase alpha, task A-R1)
		 */
		data class NonContiguousStart(
			val startName: String,
			val reason: String
		) : ReservationResult()
	}

	/**
	 * Find and reserve a free path from start to target separator.
	 *
	 * ## Contiguity precondition (Issue #893)
	 *
	 * [start] must be contiguous with the requesting train's current authority: it must bound
	 * one of the blocks the train holds in the [PathReservationRegistry] or physically occupies.
	 * A request that fails this is rejected with [ReservationResult.NonContiguousStart] before
	 * any path finding happens — reserving elsewhere would lock track the train cannot reach.
	 * A train with no footprint at all (still outside the network) is exempt.
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
	 * Whether this service currently owns at least one semaphore recorded as cleared for
	 * [trainId] -- a proceed aspect [trainId] obtained through [reservePath] that [releasePath]
	 * (or [resetSemaphoresForReleasedBlocks]) has not yet returned to
	 * [cz.vutbr.fit.interlockSim.objects.cells.Signal.STOP].
	 *
	 * ## Why this exists (Issue #893, task A7)
	 *
	 * [releasePath] resets a train's cleared signals even when it has zero blocks left to give
	 * back -- a train can legitimately reach that state after a partial release reclaimed its
	 * un-travelled tail (tasks A3/A4), leaving it holding no blocks but still governed by an
	 * earlier cleared START signal. [releasePath]'s own return value (the released block list)
	 * cannot report that: the signal-clearing side effect and the block list are independent.
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.releaseRoute] calls this
	 * BEFORE [releasePath] -- which purges the bookkeeping this reads as part of its own reset --
	 * so a signals-only release can be reported truthfully instead of being masked as "nothing
	 * happened".
	 *
	 * @param trainId The train to check.
	 * @return `true` if this service currently owns at least one cleared semaphore for [trainId].
	 * @since Issue #893 (phase alpha, task A7)
	 */
	fun hasClearedSignals(trainId: String): Boolean

	/**
	 * Record that [semaphore] now shows a proceed aspect on [trainId]'s behalf via an EXTERNAL
	 * clearing path -- i.e. a caller outside this service's own [reservePath] wrote the aspect
	 * directly -- so [releasePath], [hasClearedSignals], and [resetSemaphoresForReleasedBlocks]
	 * see it exactly as though [reservePath] itself had cleared it.
	 *
	 * ## Why this exists (Issue #893, task A6 -- G6 single signal ledger)
	 *
	 * Before this method, only [reservePath]'s own internal recording populated this service's
	 * cleared-signal ledger. Any OTHER code path that lit a semaphore directly --
	 * [cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade]'s block-list form of
	 * `requestRoute` chief among them -- left this service's ledger blind to it, so a release
	 * routed through this service (the `OrphanReservationSweeper`, via
	 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.releaseRoute]) never reset a
	 * facade-granted entry signal: it stayed lit forever after a sweep. This method closes that
	 * hole by giving external callers a SUPPORTED way to register their write with the single
	 * ledger this service already maintains, instead of each caller inventing its own parallel
	 * bookkeeping.
	 *
	 * Delegates to the exact same recording [reservePath] uses internally, so the "is it
	 * actually lit as a result of this call?" filter and last-writer-wins ownership semantics
	 * are IDENTICAL: a write that leaves [semaphore] at
	 * [cz.vutbr.fit.interlockSim.objects.cells.Signal.STOP] (or a constant semaphore's no-op
	 * write) records nothing, and a semaphore already owned by a different train under this
	 * service is simply reassigned to [trainId] (mirroring [PathReservationRegistry.blockToTrain]'s
	 * last-writer-wins semantics elsewhere in this service).
	 *
	 * @param trainId The train the write was made on behalf of.
	 * @param semaphore The semaphore that was just written by the external caller.
	 * @since Issue #893 (phase alpha, task A6)
	 */
	fun recordExternalClearedSemaphore(
		trainId: String,
		semaphore: DynamicRailSemaphore
	)

	/**
	 * Emit [BlockEvent.ReservationConflictDetected] for every blocked-path contention
	 * that is still unresolved when the simulation ends. This is the "unresolved by
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
	 * @return Events for contention that was never resolved before the run ended;
	 *         empty if there is none.
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
	 * Get all blocks currently physically occupied by a train.
	 *
	 * This is a subset of [getReservedBlocks]: every occupied block is also reserved for
	 * the occupying train, but a train may hold additional blocks that are reserved-but-not-yet-occupied
	 * (the cleared path ahead of it).
	 *
	 * @param trainId Unique identifier for the train
	 * @return List of blocks currently occupied by this train (empty if none)
	 */
	fun getOccupiedBlocks(trainId: String): List<DynamicTrackBlock>

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
	 * Find the next reservation target one section ahead of [start] — the read-only
	 * twin of [reservePathToAnyNextSemaphore]: it returns the separator that the
	 * reserving overload would reserve to (the first FREE candidate), **without
	 * reserving anything**.
	 *
	 * Used by the dispatch shell ([cz.vutbr.fit.interlockSim.sim.ShuntingLoop]) to
	 * pre-compute the `to` of an explicit from→to
	 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ReservePath] so the pure
	 * [cz.vutbr.fit.interlockSim.sim.Dispatcher] can echo it and the shell can apply
	 * it with [reservePath]. The target is a semaphore, or the destination
	 * [cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut] for the final section
	 * (InOuts are always valid terminal targets — see `findNextSemaphoresVia`).
	 *
	 * Selection mirrors [reservePathToAnyNextSemaphore]: determine the forward track
	 * section from [start]'s orientation, enumerate reachable separators via that
	 * section (InOuts prioritized over semaphores), and return the first whose path
	 * is currently available ([isPathAvailable]). `null` when no FREE next separator
	 * exists — the caller then emits [cz.vutbr.fit.interlockSim.sim.DispatchDecision.NoAction]
	 * and the train waits, matching the prior `AllPathsBlocked` outcome.
	 *
	 * Note: unlike [reservePathToAnyNextSemaphore], this does not validate that the
	 * path goes through the required `next` block (that check needs a live
	 * reservation). For networks with backward alternative routes that bypass the
	 * forward section, the reserving overload may still reject a candidate this
	 * method returns — acceptable for the `vyhybna.xml` shunting loop (no such
	 * routes) and the determinism gate; general networks are deferred to SP0.8+.
	 *
	 * @param start Starting oriented path separator (typically a semaphore).
	 * @return The first FREE next separator toward which a path can be reserved, or
	 *   `null` if none is free.
	 * @see reservePathToAnyNextSemaphore
	 * @see isPathAvailable
	 * @since Issue #729 (SP0.7 — Goal 10)
	 */
	fun findNextReservationTarget(start: OrientedPathSeparator): DynamicPathSeparator?

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
	 * On a successful release this also calls [resetSemaphoresForReleasedBlocks] for the single
	 * released [block], returning any semaphore this service recorded as cleared for [trainId] and
	 * governing it back to [cz.vutbr.fit.interlockSim.objects.cells.Signal.STOP]. This is the
	 * bookkeeping safety net for the tail-clearance path: head passage
	 * ([cz.vutbr.fit.interlockSim.sim.Train]'s `semaphoreAction`) already drops the aspect a train
	 * physically read on the way through, but does not purge this service's `clearedSemaphores` /
	 * `semaphoreClearedFor` bookkeeping, and cannot reach a governing semaphore the front never
	 * read. Safe by construction for this per-block call site: every boundary of a released block
	 * is behind the train's head by definition, so the reset can never drop a signal the train
	 * still needs ahead of it.
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
	 * @since Issue #893 (phase alpha, task A4) -- added the [resetSemaphoresForReleasedBlocks] call
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean

	/**
	 * Reset (to [cz.vutbr.fit.interlockSim.objects.cells.Signal.STOP]) every semaphore this service
	 * recorded as cleared for [trainId] that governs one of [blocks]: a semaphore that is an
	 * `ends()` member of the block, the block's `reservedFrom` when that is itself a semaphore, or
	 * the `inSemaphore` of an InOut that is either an `ends()` member of the block or the block's
	 * `reservedFrom`.
	 *
	 * ## Why more than one source
	 *
	 * `reservePath`'s atomic reservation step sets every reserved block's `reservedFrom` to the
	 * ROUTE-START separator, not to the separator locally adjacent to that particular block. So for
	 * a multi-block route, only the first block's `reservedFrom` is genuinely next to it -- every
	 * later block's `reservedFrom` still points at the far-away start:
	 * - The `ends()` source recovers the correct INTERMEDIATE semaphore for those later blocks: it
	 *   is a structural property of the block (the separators that bound it), so it is always
	 *   genuinely adjacent, regardless of the `reservedFrom` behaviour above.
	 * - The `reservedFrom` source recovers the START separator itself -- including the InOut case,
	 *   where every block in the route (again due to the behaviour above) still carries the route's
	 *   origin InOut in `reservedFrom`, letting a release reach that InOut's `inSemaphore` even when
	 *   the InOut itself borders only the very first block (typically the one still retained and
	 *   occupied, not part of what is being released).
	 *
	 * ## Ownership
	 *
	 * Reuses the same last-writer-wins ownership tracking as [releasePath]/[unregister]: a semaphore
	 * since re-cleared for a different train is left alone.
	 *
	 * ## Use Case
	 *
	 * A dispatcher reclaiming the un-travelled tail of a stalled reservation: after freeing the
	 * tail's blocks (`cancelPathSetup` + [unregisterBlock]), call this once over those blocks so no
	 * released block is left reachable through a signal still showing proceed.
	 *
	 * ## Proven-safe scope
	 *
	 * This is proven safe for **suffix / rearmost releases on a non-revisiting route**: the
	 * un-travelled tail of a route the train will never traverse again. It is NOT proven safe for
	 * an arbitrary mid-route subset of [blocks] -- the semaphore governing a released block can also
	 * be the one a DIFFERENT, still-reserved downstream block on the same route depends on (e.g. an
	 * intermediate boundary shared with a block further along the route that remains reserved). A
	 * route that loops back and becomes adjacent to a released block again has the same exposure:
	 * the semaphore this call resets may be the one that governs re-entry into the loop.
	 *
	 * The failure direction is always fail-safe, never fail-unsafe:
	 * [cz.vutbr.fit.interlockSim.objects.cells.Signal.STOP] authorises nothing, so the worst outcome
	 * of an over-eager reset outside the proven-safe scope above is a train stalled behind a signal
	 * it still needed -- never a train permitted to move where it should not be.
	 *
	 * @param trainId The train the released [blocks] belonged to.
	 * @param blocks The blocks being released -- a full route, or an un-travelled tail of one.
	 * @since Issue #893 (phase alpha, task A3)
	 */
	fun resetSemaphoresForReleasedBlocks(
		trainId: String,
		blocks: Collection<DynamicTrackBlock>
	)

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
