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

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of path reservation service.
 *
 * ## Architecture
 *
 * This service coordinates three components:
 * - **TopologyNavigator**: Finds all possible paths (static topology)
 * - **SimulationEnvironment**: Accesses dynamic block state (FREE/RESERVED/OCCUPIED)
 * - **PathReservationRegistry**: Tracks train ownership of blocks
 *
 * ## Atomic Reservation Algorithm
 *
 * For each candidate path:
 * 1. Extract unique blocks from track sections
 * 2. Check all blocks are FREE (validation phase)
 * 3. Reserve all blocks via setUpPath() (reservation phase)
 * 4. Register ownership in registry (tracking phase)
 * 5. If any step fails, rollback all changes
 *
 * ## Rollback Strategy
 *
 * When partial reservation fails:
 * ```kotlin
 * try {
 *     blocks.forEach { it.setUpPath(start) }
 * } catch (e: Exception) {
 *     // Rollback: release all blocks reserved so far
 *     blocks.forEach { it.cancelPathSetup(start) }
 *     throw e
 * }
 * ```
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** Assumes single-threaded access to simulation state.
 *
 * @property navigator Static topology navigator for path finding
 * @property environment Simulation environment for dynamic block access
 * @property registry Ownership registry for tracking train reservations
 * @since Issue #294 (Phase 2 of Issue #292)
 */
class DefaultPathReservationService(
	private val navigator: TopologyNavigator,
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry = PathReservationRegistry()
) : PathReservationService {
	/**
	 * Find and reserve a free path from start to target separator.
	 *
	 * ## Algorithm Implementation
	 *
	 * 1. Use navigator.findAllTopologicalPaths() to get all possible routes
	 * 2. For each path in priority order (BFS = shortest first):
	 *    a. Extract unique DynamicTrackBlocks from TrackSections
	 *    b. Validate all blocks are FREE
	 *    c. Atomically reserve all blocks with rollback on failure
	 *    d. Register ownership in registry
	 *    e. Return Success if all steps succeed
	 * 3. If all paths fail, return appropriate failure result
	 *
	 * ## Error Handling
	 *
	 * - TrackOperationException during setUpPath() → rollback and try next path
	 * - IllegalStateException from registry → return Conflict result
	 * - Empty paths list → return NoPathExists
	 * - All paths blocked → return AllPathsBlocked
	 */
	override fun reservePath(
		trainId: String,
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int
	): PathReservationService.ReservationResult {
		logger.debug { "reservePath: trainId=$trainId, start=$start, target=$target, maxDepth=$maxDepth" }

		// Step 1: Find all topologically possible paths
		val candidatePaths = navigator.findAllTopologicalPaths(start, target, maxDepth)

		if (candidatePaths.isEmpty()) {
			logger.info { "reservePath: No topological path exists from $start to $target" }
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.debug { "reservePath: Found ${candidatePaths.size} candidate path(s)" }

		// Step 2: Try each candidate path until we find a free one
		for ((index, path) in candidatePaths.withIndex()) {
			logger.debug { "reservePath: Attempting path ${index + 1}/${candidatePaths.size} with ${path.size} sections" }

			// Step 2a: Extract unique DynamicTrackBlocks from TrackSections
			val blocks = extractUniqueBlocks(path)
			logger.trace { "reservePath: Path has ${blocks.size} unique block(s)" }

			// Step 2b: Check if all blocks are FREE
			if (!areAllBlocksFree(blocks)) {
				logger.debug { "reservePath: Path ${index + 1} blocked (some blocks not FREE)" }
				continue
			}

			// Step 2c: Attempt atomic reservation with rollback
			val reservationResult = tryAtomicReservation(trainId, start, blocks)
			if (reservationResult != null) {
				// Reservation failed, try next path
				logger.debug { "reservePath: Path ${index + 1} reservation failed: $reservationResult" }
				if (reservationResult is PathReservationService.ReservationResult.Conflict) {
					// Conflict indicates serious error, don't try other paths
					return reservationResult
				}
				continue
			}

			// Step 2d: Register ownership in registry
			try {
				registry.register(trainId, blocks)
				logger.info {
					"reservePath: SUCCESS - Reserved path for $trainId with ${blocks.size} block(s)"
				}
				return PathReservationService.ReservationResult.Success(blocks)
			} catch (e: IllegalStateException) {
				// Registry conflict - rollback and return conflict result
				logger.error(e) { "reservePath: Registry conflict during registration" }
				rollbackReservation(start, blocks)
				val conflictingBlock = blocks.firstOrNull { registry.isRegistered(it) }
				return if (conflictingBlock != null) {
					PathReservationService.ReservationResult.Conflict(
						conflictingBlock,
						registry.getOwner(conflictingBlock) ?: "unknown"
					)
				} else {
					PathReservationService.ReservationResult.Conflict(
						blocks.first(),
						"unknown"
					)
				}
			}
		}

		// All paths tried, all were blocked
		logger.info { "reservePath: All ${candidatePaths.size} path(s) blocked" }
		return PathReservationService.ReservationResult.AllPathsBlocked(candidatePaths.size)
	}

	/**
	 * Release all blocks reserved by a train.
	 *
	 * ## Implementation
	 *
	 * 1. Get all blocks from registry
	 * 2. Cancel path setup for each block
	 * 3. Unregister train from registry
	 *
	 * This operation is idempotent - safe to call multiple times.
	 */
	override fun releasePath(trainId: String): List<DynamicTrackBlock> {
		logger.debug { "releasePath: trainId=$trainId" }

		val blocks = registry.getBlocks(trainId)
		if (blocks.isEmpty()) {
			logger.debug { "releasePath: No blocks registered for $trainId" }
			return emptyList()
		}

		// Cancel path setup for all blocks
		// Note: We don't know which separator was used for reservation,
		// but cancelPathSetup validates it matches the reservedFrom,
		// so we need to get it from the block itself
		blocks.forEach { block ->
			val reservedFrom = block.reservedFrom
			if (reservedFrom != null) {
				try {
					block.cancelPathSetup(reservedFrom)
					logger.trace { "releasePath: Released block $block" }
				} catch (e: Exception) {
					logger.warn(e) { "releasePath: Failed to release block $block" }
				}
			}
		}

		// Unregister from registry
		registry.unregister(trainId)

		logger.info { "releasePath: Released ${blocks.size} block(s) for $trainId" }
		return blocks
	}

	/**
	 * Check if a path is currently available.
	 *
	 * ## Implementation
	 *
	 * 1. Find all topological paths
	 * 2. For each path, check if all blocks are FREE
	 * 3. Return true if at least one free path exists
	 */
	override fun isPathAvailable(
		start: PathSeparator,
		target: PathSeparator,
		maxDepth: Int
	): Boolean {
		logger.debug { "isPathAvailable: start=$start, target=$target, maxDepth=$maxDepth" }

		val candidatePaths = navigator.findAllTopologicalPaths(start, target, maxDepth)

		if (candidatePaths.isEmpty()) {
			logger.debug { "isPathAvailable: No topological path exists" }
			return false
		}

		for (path in candidatePaths) {
			val blocks = extractUniqueBlocks(path)
			if (areAllBlocksFree(blocks)) {
				logger.debug { "isPathAvailable: Found free path with ${blocks.size} block(s)" }
				return true
			}
		}

		logger.debug { "isPathAvailable: All ${candidatePaths.size} path(s) blocked" }
		return false
	}

	/**
	 * Get all blocks currently reserved by a train.
	 */
	override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> = registry.getBlocks(trainId)

	// ========== Private helper methods ==========

	/**
	 * Extract unique DynamicTrackBlocks from a path of TrackSections.
	 *
	 * ## Algorithm
	 *
	 * 1. Map each TrackSection to its containing TrackBlock
	 * 2. Cast to DynamicTrackBlock (guaranteed in SimulationContext)
	 * 3. Remove duplicates (preserving order)
	 *
	 * ## Why Deduplication?
	 *
	 * A path may contain the same block multiple times:
	 * - Switch "around" blocks appear twice in path definition
	 * - We only want to reserve each physical block once
	 *
	 * ## Note on Type Safety
	 *
	 * In SimulationContext, all TrackBlocks are DynamicTrackBlock instances.
	 * The cast is safe because:
	 * - Navigator uses SimulationContext graph
	 * - SimulationContext extends Context<Cell, DynamicTrackBlock>
	 * - All blocks in the graph are DynamicTrackBlock
	 *
	 * @param path List of TrackSections in path order
	 * @return List of unique DynamicTrackBlocks in path order
	 */
	private fun extractUniqueBlocks(
		path: List<cz.vutbr.fit.interlockSim.objects.tracks.TrackSection>
	): List<DynamicTrackBlock> {
		val seen = mutableSetOf<DynamicTrackBlock>()
		return path.mapNotNull { section ->
			val block = section.getTrackBlock()
			// In SimulationContext, all TrackBlocks are DynamicTrackBlock instances
			if (block is DynamicTrackBlock && seen.add(block)) {
				block
			} else {
				null
			}
		}
	}

	/**
	 * Check if all blocks in a list are FREE.
	 *
	 * @param blocks List of blocks to check
	 * @return true if ALL blocks are FREE, false if any is RESERVED or OCCUPIED
	 */
	private fun areAllBlocksFree(blocks: List<DynamicTrackBlock>): Boolean =
		blocks.all { it.getState() == TrackFacility.State.FREE }

	/**
	 * Attempt atomic reservation of all blocks in a path.
	 *
	 * ## Algorithm
	 *
	 * 1. Try to reserve all blocks via setUpPath()
	 * 2. If any block fails, rollback all previously reserved blocks
	 * 3. Return null on success, failure result on error
	 *
	 * ## Atomic Guarantee
	 *
	 * Either all blocks are reserved, or none are (all-or-nothing semantics).
	 *
	 * @param trainId Train identifier (for logging and error messages)
	 * @param separator Starting separator for reservation
	 * @param blocks List of blocks to reserve
	 * @return null on success, ReservationResult on failure
	 */
	private fun tryAtomicReservation(
		trainId: String,
		separator: PathSeparator,
		blocks: List<DynamicTrackBlock>
	): PathReservationService.ReservationResult? {
		val reservedSoFar = mutableListOf<DynamicTrackBlock>()

		try {
			for (block in blocks) {
				block.setUpPathWithTrainId(separator, trainId)
				reservedSoFar.add(block)
			}
			// All blocks reserved successfully
			return null
		} catch (e: Exception) {
			// Partial failure - rollback all blocks reserved so far
			logger.debug(e) {
				"tryAtomicReservation: Reservation failed for $trainId, " +
					"rolling back ${reservedSoFar.size} block(s)"
			}
			rollbackReservation(separator, reservedSoFar)

			// Determine failure type
			return when {
				e.message?.contains("already reserved") == true -> {
					// This shouldn't happen if areAllBlocksFree() worked correctly
					logger.warn { "tryAtomicReservation: Block became reserved between check and reservation" }
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
				else -> {
					logger.warn(e) { "tryAtomicReservation: Unexpected error during reservation" }
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
			}
		}
	}

	/**
	 * Rollback reservation of blocks.
	 *
	 * Cancels path setup for all blocks in the list. Errors during rollback are logged
	 * but not propagated (best-effort cleanup).
	 *
	 * @param separator The separator used for reservation
	 * @param blocks List of blocks to rollback
	 */
	private fun rollbackReservation(
		separator: PathSeparator,
		blocks: List<DynamicTrackBlock>
	) {
		logger.debug { "rollbackReservation: Rolling back ${blocks.size} block(s)" }
		for (block in blocks) {
			try {
				// Only rollback if block was actually reserved from this separator
				if (block.reservedFrom === separator) {
					block.cancelPathSetup(separator)
				}
			} catch (e: Exception) {
				logger.warn(e) { "rollbackReservation: Failed to rollback block $block" }
			}
		}
	}
}
