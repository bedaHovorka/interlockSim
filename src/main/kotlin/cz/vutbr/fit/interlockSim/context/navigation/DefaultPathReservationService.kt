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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackReservationException
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFree
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
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int
	): PathReservationService.ReservationResult {
		// Step 1: Find all topologically possible paths
		val candidatePaths = navigator.findAllTopologicalPaths(start, target, maxDepth)

		if (candidatePaths.isEmpty()) {
			return PathReservationService.ReservationResult.NoPathExists
		}

		// Step 2: Try each candidate path until we find a free one
		for ((index, path) in candidatePaths.withIndex()) {

			// Step 2a: Extract unique DynamicTrackBlocks from TrackSections
			val blocks = extractUniqueBlocks(path)
			logger.trace { "reservePath: Path has ${blocks.size} unique block(s)" }

			// Step 2b: Check if all blocks are FREE
			if (!blocks.areAllFree()) {
				continue
			}

			// TOCTOU Note: Small window between areAllBlocksFree() check and tryAtomicReservation() use.
			// This is acceptable because:
			// - Single-threaded access to simulation state (documented in class KDoc)
			// - tryAtomicReservation() has rollback logic if state changed
			// - Worst case: false positive on free check, caught during reservation, try next path
			//
			// Step 2c: Attempt atomic reservation with rollback
			val reservationResult = tryAtomicReservation(trainId, start, blocks)
			if (reservationResult != null) {
				// Reservation failed, try next path
				if (reservationResult is PathReservationService.ReservationResult.Conflict) {
					// Conflict indicates serious error, don't try other paths
					return reservationResult
				}
				continue
			}

			// Step 2d: Register ownership in registry (atomic operation)
			return when (val result = registry.registerAtomic(trainId, blocks)) {
				is PathReservationRegistry.RegistrationResult.Success -> {
					// Success - path reserved and registered

					// Step 2e: Configure semaphore signal after successful reservation
					if (start is DynamicRailSemaphore && blocks.isNotEmpty()) {
						environment.configureSemaphoreSignal(start, blocks.first())
						logger.debug {
							"reservePath: Configured semaphore signal for ${start.name}"
						}
					}

					PathReservationService.ReservationResult.Success(blocks)
				}
				is PathReservationRegistry.RegistrationResult.Conflict -> {
					// Registry conflict - rollback block reservations
					logger.warn {
						"reservePath: Registry conflict - ${result.conflictingBlock} owned by ${result.existingOwner}"
					}
					rollbackReservation(start, blocks)
					PathReservationService.ReservationResult.Conflict(
						result.conflictingBlock,
						result.existingOwner
					)
				}
			}
		}

		// All paths tried, all were blocked
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
		val blocks = registry.getBlocks(trainId)
		if (blocks.isEmpty()) {
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
				} catch (e: Exception) {
					logger.warn(e) { "releasePath: Failed to release block $block" }
				}
			}
		}

		// Unregister from registry
		registry.unregister(trainId)

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
		val candidatePaths = navigator.findAllTopologicalPaths(start, target, maxDepth)

		if (candidatePaths.isEmpty()) {
			return false
		}

		for (path in candidatePaths) {
			val blocks = extractUniqueBlocks(path)
			if (blocks.areAllFree()) {
				return true
			}
		}

		return false
	}

	override fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: DynamicPathSeparator,
		next: TrackSection
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAnyNextSemaphore: Searching for semaphores from $start via $next"
		}

		// Find ALL reachable semaphores via this track section
		val semaphores = findNextSemaphoresVia(start, next)
		if (semaphores.isEmpty()) {
			logger.debug {
				"reservePathToAnyNextSemaphore: No semaphores found from $start via $next"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.debug {
			"reservePathToAnyNextSemaphore: Found ${semaphores.size} semaphore(s): $semaphores"
		}

		// Try to reserve path to each semaphore until one succeeds
		var lastResult: PathReservationService.ReservationResult? = null
		for (semaphore in semaphores) {
			logger.trace {
				"reservePathToAnyNextSemaphore: Attempting reservation to $semaphore"
			}

			val result = reservePath(trainId, start, semaphore)

			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					// Success! Path reserved

					// Configure the START separator's semaphore (if it has one)
					// For InOut, this is the output semaphore that controls entry
					// For RailSemaphore start points, this would be the semaphore itself
					if (result.reservedBlocks.isNotEmpty()) {
						when (start) {
							is DynamicInOut -> {
								// InOut has an output semaphore that needs configuration
								// NOTE: Due to simulation timing, this configuration may not take effect
								// before the train checks the signal (race condition at t=0.0)
								// See Issue #296 for details
								val outSemaphore = environment.toDynamic(start.staticRef.getOutSemaphore())
								if (outSemaphore is DynamicRailSemaphore) {
									environment.configureSemaphoreSignal(outSemaphore, result.reservedBlocks.first())
									logger.debug {
										"reservePathToAnyNextSemaphore: Configured InOut ${start.name} output " +
											"semaphore to ${outSemaphore.signal}"
									}
								}
							}
							is DynamicRailSemaphore -> {
								// Semaphore at start - configure it
								environment.configureSemaphoreSignal(start, result.reservedBlocks.first())
								logger.debug {
									"reservePathToAnyNextSemaphore: Configured START semaphore " +
										"${start.name} to ${start.signal}"
								}
							}
						}
					}

					logger.debug {
						"reservePathToAnyNextSemaphore: Successfully reserved path to $semaphore"
					}
					return result
				}
				is PathReservationService.ReservationResult.AllPathsBlocked -> {
					// This semaphore blocked, try next one
					logger.trace {
						"reservePathToAnyNextSemaphore: Path to $semaphore blocked, trying next"
					}
					lastResult = result
					continue
				}
				is PathReservationService.ReservationResult.Conflict -> {
					// Conflict indicates serious error, don't try other paths
					logger.warn {
						"reservePathToAnyNextSemaphore: Conflict detected, aborting: $result"
					}
					return result
				}
				is PathReservationService.ReservationResult.NoPathExists -> {
					// Should not happen (we found the semaphore), but handle it
					logger.warn {
						"reservePathToAnyNextSemaphore: No topological path to $semaphore (unexpected)"
					}
					lastResult = result
					continue
				}
			}
		}

		// All semaphores tried, all paths blocked
		logger.debug {
			"reservePathToAnyNextSemaphore: All ${semaphores.size} path(s) blocked"
		}
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(semaphores.size)
	}

	override fun isPathToAnyNextSemaphoreAvailable(start: PathSeparator, next: TrackSection?): Boolean {
		// Handle null next parameter (no direction to search)
		if (next == null) {
			logger.debug { "isPathToAnyNextSemaphoreAvailable: next is null, no path exists" }
			return false
		}

		// Find ALL reachable semaphores via this track section
		val semaphores = findNextSemaphoresVia(start, next)
		if (semaphores.isEmpty()) {
			logger.debug {
				"isPathToAnyNextSemaphoreAvailable: No semaphores found from $start via $next"
			}
			return false
		}

		logger.trace {
			"isPathToAnyNextSemaphoreAvailable: Found ${semaphores.size} semaphore(s), " +
				"checking availability: $semaphores"
		}

		// Check if ANY semaphore has an available path
		for (semaphore in semaphores) {
			if (isPathAvailable(start, semaphore)) {
				logger.debug {
					"isPathToAnyNextSemaphoreAvailable: Path available to $semaphore"
				}
				return true
			}
		}

		logger.debug {
			"isPathToAnyNextSemaphoreAvailable: All ${semaphores.size} path(s) blocked"
		}
		return false
	}

	/**
	 * Get all blocks currently reserved by a train.
	 */
	override fun getReservedBlocks(trainId: String): List<DynamicTrackBlock> = registry.getBlocks(trainId)

	// ========== Private helper methods ==========

	/**
	 * Find all semaphores reachable from start separator via specific track section.
	 *
	 * This method navigates from the starting separator through the given track section
	 * to discover ALL oriented semaphores (OrientedPathSeparator instances) reachable
	 * in the network. If the network has switches creating multiple routes, all reachable
	 * semaphores are returned.
	 *
	 * ## Algorithm
	 *
	 * 1. Use TopologyNavigator to find all possible paths from start
	 * 2. Filter paths that begin with the `next` track section (direction constraint)
	 * 3. Extract endpoint separators from filtered paths
	 * 4. Filter for OrientedPathSeparator instances (semaphores, oriented InOuts)
	 * 5. Cast to DynamicPathSeparator (safe in SimulationContext)
	 * 6. Remove duplicates (multiple paths may lead to same semaphore)
	 *
	 * ## Return Value
	 *
	 * List of unique DynamicPathSeparator instances representing semaphores:
	 * - **Empty list**: No semaphores reachable via `next` (dead-end, loop, or plain junction)
	 * - **Single element**: One semaphore reachable
	 * - **Multiple elements**: Switch creates multiple routes to different semaphores
	 *
	 * ## Type Safety
	 *
	 * In SimulationContext, all PathSeparators are DynamicPathSeparator instances.
	 * The cast is safe because:
	 * - Navigator uses SimulationContext graph
	 * - All separators in simulation are dynamic wrappers
	 * - OrientedPathSeparator includes semaphores and oriented InOuts
	 *
	 * ## Example Networks
	 *
	 * **Linear path:**
	 * ```
	 * InOut -> TrackSection -> Semaphore
	 * Result: [Semaphore]
	 * ```
	 *
	 * **Switch with multiple semaphores:**
	 * ```
	 * InOut -> TrackSection -> RailSwitch --> Path A -> Semaphore1
	 *                                    \--> Path B -> Semaphore2
	 * Result: [Semaphore1, Semaphore2]
	 * ```
	 *
	 * **Dead-end:**
	 * ```
	 * InOut -> TrackSection -> BufferStop (non-oriented)
	 * Result: []
	 * ```
	 *
	 * @param start Starting path separator (typically InOut or semaphore)
	 * @param next First track section after start (direction to search)
	 * @return List of unique DynamicPathSeparator instances that are oriented semaphores
	 */
	private fun findNextSemaphoresVia(
		start: PathSeparator,
		next: TrackSection
	): List<DynamicPathSeparator> {
		logger.trace {
			"findNextSemaphoresVia: Starting search from $start via $next"
		}

		// Strategy: Navigate step-by-step from start through 'next' section until finding
		// the FIRST OrientedPathSeparator (semaphore). This matches the original
		// pathToNextSemaphore semantics.

		var currentSep: PathSeparator = start
		var currentSection: TrackSection? = next
		val visited = mutableSetOf<PathSeparator>()
		visited.add(start)

		while (currentSection != null) {
			logger.trace {
				"findNextSemaphoresVia: Navigating from separator=$currentSep through section=$currentSection"
			}

			// Get the separator at the other end of currentSection
			val nextSeparator = currentSection.getSecondEnd(currentSep)

			logger.trace {
				"findNextSemaphoresVia: Reached separator=$nextSeparator"
			}

			// Check if this separator is an oriented semaphore
			if (nextSeparator is cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator) {
				// Found the first semaphore!
				val dynamicSep = environment.toDynamic(nextSeparator)
				logger.trace {
					"findNextSemaphoresVia: Found semaphore: $dynamicSep"
				}
				return listOf(dynamicSep)
			}

			// Check for cycles
			if (!visited.add(nextSeparator)) {
				logger.warn {
					"findNextSemaphoresVia: Cycle detected at $nextSeparator, stopping search"
				}
				break
			}

			// Not a semaphore, continue to next section
			currentSep = nextSeparator
			currentSection = navigator.getNextTrackSection(currentSep, currentSection)
		}

		// No semaphore found
		logger.trace {
			"findNextSemaphoresVia: Search complete, no semaphore found"
		}
		return emptyList()
	}

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
			when {
				block is DynamicTrackBlock && seen.add(block) -> block
				block is DynamicTrackBlock && !seen.add(block) -> null  // Duplicate, expected
				else -> {
					// Should never happen in SimulationContext, but log for debugging
					logger.warn {
						"extractUniqueBlocks: Unexpected non-DynamicTrackBlock encountered: " +
							"${block::class.simpleName} from section $section. " +
							"This indicates a context type mismatch."
					}
					null
				}
			}
		}
	}

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
		separator: DynamicPathSeparator,
		blocks: List<DynamicTrackBlock>
	): PathReservationService.ReservationResult? {
		val reservedSoFar = mutableListOf<DynamicTrackBlock>()

		try {
			for (block in blocks) {
				block.setUpPath(separator, trainId)
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

			// Classify error using type-safe exception hierarchy
			return when (e) {
				is TrackReservationException.AlreadyReservedConflict -> {
					// TOCTOU race: block became reserved between check and reservation
					logger.warn {
						"tryAtomicReservation: TOCTOU race detected - " +
							"Block ${e.block} reserved by ${e.existingSeparator}"
					}
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
				is TrackReservationException.InvalidStateTransition -> {
					// State machine violation
					logger.error(e) {
						"tryAtomicReservation: State machine violation in ${e.block} - " +
							"${e.operation} from ${e.fromState}"
					}
					PathReservationService.ReservationResult.AllPathsBlocked(1)
				}
				else -> {
					// Unexpected error (should not happen)
					logger.error(e) {
						"tryAtomicReservation: Unexpected error during reservation: ${e::class.simpleName}"
					}
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
