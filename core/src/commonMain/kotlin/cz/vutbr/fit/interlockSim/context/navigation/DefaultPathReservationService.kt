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
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackReservationException
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFree
import cz.vutbr.fit.interlockSim.objects.tracks.areAllFreeOrOwnedBy
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
 * @property pathInfoBuilder Builder for PathInfo metadata (Issue #295/#296 Phase 4)
 * @since Issue #294 (Phase 2 of Issue #292)
 */
class DefaultPathReservationService(
	private val navigator: TopologyNavigator,
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry,
	private val pathInfoBuilder: PathInfoBuilder
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
	@Suppress("LongMethod")
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

			// Step 2a.5: Bug fix (Issue #296) - Filter out blocks already owned by this train
			// Blocks already owned (RESERVED or OCCUPIED) by this train should be excluded because:
			// 1. OCCUPIED: Train has already been there (blocks behind the train)
			// 2. RESERVED: Already reserved from a (possibly different) separator
			// Including owned blocks causes TOCTOU conflicts when trying to re-reserve from different separator.
			val forwardBlocks =
				blocks.filterNot { block ->
					block.trainName == trainId
				}

			if (forwardBlocks.isEmpty()) {
				// All blocks in this path are already owned by this train
				// Configure START semaphore before returning (may be from different position)
				// configureSemaphoreSignal is idempotent, safe to call multiple times
				if (blocks.isNotEmpty()) {
					when {
						start is DynamicRailSemaphore -> {
							environment.configureSemaphoreSignal(start, blocks.first())
						}
						start is DynamicInOut -> {
							val firstBlock = blocks.first()
							val maxSpeed = firstBlock.maxSpeed(start)
							start.inSemaphore.setUpSpeed(
								from = start.direction(),
								to =
									cz.vutbr.fit.interlockSim.objects.core
										.anti(start.direction()),
								allowedSpeed = maxSpeed
							)
						}
					}
				}
				// FIX (Issue #296): Register PathInfo for already-owned blocks
				val pathInfo = pathInfoBuilder.buildPathInfo(start, target, path)
				registry.registerPathInfo(trainId, pathInfo)

				return PathReservationService.ReservationResult.Success(blocks)
			}

			// Step 2b: Check if all forward blocks are available (FREE or RESERVED by THIS train)
			if (!forwardBlocks.areAllFreeOrOwnedBy(trainId)) {
				continue
			}

			// TOCTOU Note: Small window between areAllBlocksFree() check and tryAtomicReservation() use.
			// This is acceptable because:
			// - Single-threaded access to simulation state (documented in class KDoc)
			// - tryAtomicReservation() has rollback logic if state changed
			// - Worst case: false positive on free check, caught during reservation, try next path
			//
			// Step 2c: Attempt atomic reservation with rollback (only forward blocks)
			val reservationResult = tryAtomicReservation(trainId, start, forwardBlocks)
			if (reservationResult != null) {
				// Reservation failed, try next path
				if (reservationResult is PathReservationService.ReservationResult.Conflict) {
					// Conflict indicates serious error, don't try other paths
					return reservationResult
				}
				continue
			}

			// Step 2d: Register ownership in registry (atomic operation, only forward blocks)
			return when (val result = registry.registerAtomic(trainId, forwardBlocks)) {
				is PathReservationRegistry.RegistrationResult.Success -> {
					// Success - path reserved and registered

					// Step 2e: Build PathInfo with entry directions (Issue #295/#296 Phase 4)
					logger.debug {
						"reservePath: Building PathInfo for $trainId from $start to $target with ${path.size} track sections"
					}
					val pathInfo =
						pathInfoBuilder.buildPathInfo(
							start = start,
							target = target,
							trackSections = path // path is List<TrackSection> here
						)

					// Step 2f: Register PathInfo metadata (Issue #295/#296 Phase 4)
					registry.registerPathInfo(trainId, pathInfo)
					logger.debug {
						"reservePath: Registered PathInfo for $trainId with ${pathInfo.entryDirections.size} entry directions, " +
							"reserved path has ${pathInfo.reservedPath.length()} elements"
					}

					// Step 2f.1: Register switches (Tier 2 - Issue #291)
					val switches = extractUniqueSwitches(pathInfo)
					if (switches.isNotEmpty()) {
						registry.registerSwitches(trainId, switches)
						logger.debug {
							"reservePath: Registered ${switches.size} switches for $trainId"
						}
					}

					// Step 2f.2: Configure switches based on path topology (Issue #300)
					// Switches must be configured BEFORE semaphore signals are set up
					// This ensures switches are in correct position (MAIN/BRANCH) for the reserved route
					if (switches.isNotEmpty()) {
						val configuredCount = configureSwitchesInPath(trainId, pathInfo)
						logger.debug {
							"reservePath: Configured $configuredCount of ${switches.size} switches for $trainId"
						}
					}

					// Step 2g: Configure semaphore signal after successful reservation
					// Use forwardBlocks (blocks we just reserved) for semaphore configuration
					if (forwardBlocks.isNotEmpty()) {
						val signalConfigured =
							when {
								// Case 1: START is a semaphore -> configure it (train departing from semaphore)
								start is DynamicRailSemaphore -> {
									try {
										environment.configureSemaphoreSignal(start, forwardBlocks.first())
										logger.debug {
											"reservePath: Configured START semaphore ${start.name} to ${start.signal}"
										}
										true
									} catch (e: Exception) {
										logger.warn(e) {
											"reservePath: Semaphore signal configuration failed - rolling back reservation"
										}
										false
									}
								}
								// Case 2: START is InOut -> configure inSemaphore (train entering from external network)
								// Path is conceptually: inOut.inSemaphore → blocks → target
								// inSemaphore.direction() == anti(InOut.direction()) per InOut.kt line 33
								start is DynamicInOut -> {
									try {
										val firstBlock = forwardBlocks.first()
										val maxSpeed = firstBlock.maxSpeed(start)
										// Call setUpSpeed directly - inSemaphore is not a track end, it's embedded
										// For valid direction: from=anti(inSem.dir), to=inSem.dir
										// Since inSem.dir=anti(InOut.dir), this becomes: from=InOut.dir, to=anti(InOut.dir)
										start.inSemaphore.setUpSpeed(
											from = start.direction(), // InOut's direction
											to =
												cz.vutbr.fit.interlockSim.objects.core
													.anti(start.direction()),
											// Anti = inSemaphore's direction
											allowedSpeed = maxSpeed
										)
										logger.debug {
											"reservePath: Configured InOut ${start.name} inSemaphore to ${start.inSemaphore.signal}"
										}
										true
									} catch (e: Exception) {
										logger.warn(e) {
											"reservePath: InOut inSemaphore configuration failed - rolling back reservation"
										}
										false
									}
								}
								else -> false
							}

						// Rollback reservation if signal configuration failed
						// This prevents trains from waiting indefinitely at STOP signals
						if (!signalConfigured) {
							rollbackCompleteReservation(trainId, blocks, start)
							return PathReservationService.ReservationResult.AllPathsBlocked(1)
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
	 * Registry cleanup is guaranteed even if block release fails (try-finally).
	 */
	override fun releasePath(trainId: String): List<DynamicTrackBlock> {
		val blocks = registry.getBlocks(trainId)
		if (blocks.isEmpty()) {
			return emptyList()
		}

		try {
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

			// Tier 2: Unlock switches atomically with blocks (Issue #291)
			val switches = registry.getSwitches(trainId)
			switches.forEach { switch ->
				try {
					switch.unlock()
					logger.debug { "releasePath: Unlocked switch ${switch.hashCode()} for $trainId" }
				} catch (e: Exception) {
					logger.warn(e) { "releasePath: Failed to unlock switch $switch" }
				}
			}

			return blocks
		} finally {
			// Unregister blocks and switches from registry - ALWAYS executed
			// This prevents memory leaks and stale reservations if block release fails
			registry.unregister(trainId)
			registry.unregisterSwitches(trainId)
		}
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
			"reservePathToAnyNextSemaphore: ENTRY POINT for $trainId - " +
				"start=$start (${start::class.simpleName}), " +
				"next=$next (${next::class.simpleName})"
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

		// Extract the DynamicTrackBlock from the 'next' parameter for validation
		// next is a TrackSection, which could be a DynamicTrackBlock or other type
		val nextBlock = next.getTrackBlock() as? DynamicTrackBlock
		if (nextBlock == null) {
			logger.warn {
				"reservePathToAnyNextSemaphore: next parameter is not a DynamicTrackBlock: $next"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		// Try to reserve path to each semaphore until one succeeds
		var lastResult: PathReservationService.ReservationResult? = null
		var attemptCount = 0
		for (semaphore in semaphores) {
			attemptCount++
			logger.trace {
				"reservePathToAnyNextSemaphore: Attempting reservation to $semaphore (attempt $attemptCount/${semaphores.size})"
			}

			val result = reservePath(trainId, start, semaphore)

			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					// FIX: Validate that the reserved path actually goes through the 'next' block
					// This prevents alternative routes that bypass the intended track section
					if (!result.reservedBlocks.contains(nextBlock)) {
						logger.debug {
							"reservePathToAnyNextSemaphore: Path to $semaphore rejected (doesn't use required next block)"
						}
						// Release the wrongly reserved path
						result.reservedBlocks.forEach { block ->
							try {
								block.cancelPathSetup(start)
								registry.unregisterBlock(trainId, block)
							} catch (e: Exception) {
								logger.warn(e) {
									"reservePathToAnyNextSemaphore: Failed to release block during rollback: ${block.staticRef}"
								}
							}
						}
						lastResult = PathReservationService.ReservationResult.AllPathsBlocked(attemptCount)
						continue
					}

					// Success! Path reserved and validated to use the required 'next' block

					// Configure semaphore signal after successful reservation
					// Only configure for RailSemaphore start (InOut semaphores are constant)
					if (start is DynamicRailSemaphore && result.reservedBlocks.isNotEmpty()) {
						environment.configureSemaphoreSignal(start, result.reservedBlocks.first())
						logger.debug {
							"reservePathToAnyNextSemaphore: Configured START semaphore ${start.name} to ${start.signal}"
						}
					}

					logger.debug {
						"reservePathToAnyNextSemaphore: Successfully reserved path to $semaphore via required next block"
					}
					return result
				}
				is PathReservationService.ReservationResult.AllPathsBlocked -> {
					// This semaphore blocked, try next one
					logger.trace {
						"reservePathToAnyNextSemaphore: Path to $semaphore blocked, trying next"
					}
					lastResult = PathReservationService.ReservationResult.AllPathsBlocked(attemptCount)
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
					lastResult = PathReservationService.ReservationResult.NoPathExists
					continue
				}
			}
		}

		// All semaphores tried, all paths blocked
		logger.debug {
			"reservePathToAnyNextSemaphore: All $attemptCount path(s) blocked"
		}
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(attemptCount)
	}

	override fun reservePathToAnyNextSemaphore(
		trainId: String,
		start: OrientedPathSeparator
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAnyNextSemaphore: Finding path from oriented separator $start for $trainId"
		}

		// Step 1: Convert to dynamic if needed (toDynamic is idempotent)
		val dynamicStart = environment.toDynamic(start)

		// Step 2: Get next track section based on separator's orientation
		// For oriented separators, use the direction() method to get the forward segment
		// SPECIAL CASE: InOut connects bidirectionally at direction() (not anti-direction)
		val forwardSegment =
			when (start) {
				is InOut -> start.getTrackConnectionDirection() // Track connection at direction()
				is DynamicInOut -> start.getTrackConnectionDirection() // Track connection at direction()
				else -> start.direction() // Semaphores: direction is forward travel
			}
		logger.debug {
			"reservePathToAnyNextSemaphore: START=$start orientation=${start.getOrientation()} forwardSegment=$forwardSegment"
		}

		// Step 3: Find the track section connected to the forward segment
		// Use interface methods (added to SimulationEnvironment for navigation services)
		val location = environment.getRailWayNetGrid().getLocation(start)
		if (location == null) {
			logger.warn {
				"reservePathToAnyNextSemaphore: No location found for $start"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.info {
			"reservePathToAnyNextSemaphore: Location=$location"
		}

		val next = environment.getGraph().assignedEdges(location)[forwardSegment]
		if (next == null) {
			logger.warn {
				"reservePathToAnyNextSemaphore: No outgoing track section from $start at $location in direction $forwardSegment"
			}
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.info {
			"reservePathToAnyNextSemaphore: Selected next track section: $next"
		}

		// Step 4: Delegate to existing overload
		logger.debug {
			"reservePathToAnyNextSemaphore: Delegating to existing overload with next=$next"
		}
		return reservePathToAnyNextSemaphore(trainId, dynamicStart, next)
	}

	override fun isPathToAnyNextSemaphoreAvailable(
		start: PathSeparator,
		next: TrackSection?
	): Boolean {
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

	/**
	 * Implementation of reservePathToAny with intelligent target prioritization.
	 *
	 * ## Algorithm Complexity
	 *
	 * This method is intentionally complex to optimize path selection. The complexity comes from:
	 * 1. **Target discovery** - O(n) grid scan for semaphores + O(m) InOut iteration
	 * 2. **Smart sorting** - O(k log k) sorting with path length calculation
	 * 3. **Reservation attempts** - O(k) tries until first success
	 *
	 * Where:
	 * - n = grid size (cols × rows)
	 * - m = number of InOuts (typically 2-4)
	 * - k = total number of targets (m + number of semaphores)
	 *
	 * ## Why This Complexity Is Necessary
	 *
	 * **Naive approach** (try random targets until one works):
	 * - May try long paths before short paths
	 * - May try same-side targets (backward) before opposite-side (forward)
	 * - Results in inefficient train movements and longer simulation times
	 *
	 * **Smart approach** (this implementation):
	 * - Prioritizes InOuts over semaphores (reaching exit is better than stopping mid-network)
	 * - Prioritizes opposite-side targets (forward progress across shunting loop)
	 * - Sorts by path length (shorter paths tried first)
	 * - Results in realistic, efficient train routing
	 *
	 * ## Four-Step Algorithm
	 *
	 * **Step 1: Target Discovery**
	 * - Collect all InOuts from environment (excluding start)
	 * - Scan grid to find all semaphores (excluding start)
	 * - Complexity: O(grid size + InOuts)
	 *
	 * **Step 2: Intelligent InOut Sorting**
	 * - If start has orientation (e.g., semaphore):
	 *   1. Partition InOuts by orientation (opposite-side vs same-side)
	 *   2. Sort each partition by path length (shortest first)
	 *   3. Combine: [opposite-side InOuts] + [same-side InOuts]
	 * - If start has no orientation:
	 *   1. Sort all InOuts by path length only
	 * - Complexity: O(m log m × path_finding_cost)
	 *
	 * **Step 3: Target Prioritization**
	 * - Combine sorted InOuts + unsorted semaphores
	 * - InOuts tried first (exit points preferred over mid-network stops)
	 * - Complexity: O(1) list concatenation
	 *
	 * **Step 4: Reservation Attempts**
	 * - Try each target in priority order
	 * - Return first Success
	 * - Return immediately on Conflict (serious error)
	 * - If all fail: return NoPathExists or AllPathsBlocked
	 * - Complexity: O(k × reservation_cost)
	 *
	 * ## Performance Considerations
	 *
	 * **When is this method called?**
	 * - Infrequently: only when train needs new path (not every simulation tick)
	 * - Typical scenario: train approaches semaphore, needs forward path
	 * - Frequency: ~once per train per network traversal
	 *
	 * **Optimization opportunities NOT taken:**
	 * - Pre-compute all paths at initialization → rejected because paths are dynamic (occupation changes)
	 * - Cache sorted targets → rejected because network state changes continuously
	 * - Use spatial indexing for semaphores → rejected because grid scan is fast enough
	 *
	 * **Why these optimizations are unnecessary:**
	 * - Method called rarely (not in hot path)
	 * - Grid scan is O(n) but n is small (typical: 100×100 = 10,000 cells)
	 * - Path finding is fast (TopologyNavigator uses graph traversal, not A*)
	 * - Premature optimization would complicate code without measurable benefit
	 *
	 * ## Example: Shunting Loop Scenario
	 *
	 * Network: A ← zA ← vA ← (doA1/doA2) ↔ (doB1/doB2) → vB → zB → B
	 *
	 * Train at semaphore zA (orientation=false, points toward A):
	 * 1. Discover targets: InOuts [A, B], Semaphores [doA1, doA2, doB1, doB2, zB]
	 * 2. Sort InOuts by orientation:
	 *    - Opposite-side (orientation=true): [B] (forward progress)
	 *    - Same-side (orientation=false): [A] (backward)
	 *    - Result: [B, A]
	 * 3. Final order: [B, A, doA1, doA2, doB1, doB2, zB]
	 * 4. Try B first (most efficient: cross entire network to exit)
	 * 5. If B blocked, try A (backward to entry)
	 * 6. If both InOuts blocked, try semaphores (stop mid-network)
	 *
	 * This ensures trains prefer forward progress and exiting over stopping mid-network.
	 *
	 * ## Design Decision: Why Not Move Sorting to Navigator?
	 *
	 * The sorting logic is tightly coupled to **reservation semantics**:
	 * - TopologyNavigator handles pure graph traversal (no state)
	 * - PathReservationService handles state-aware routing (occupation, orientation preference)
	 * - Mixing these concerns would violate Single Responsibility Principle
	 * - Current design: Navigator = stateless pathfinding, Service = stateful routing
	 *
	 * @param trainId Unique identifier for the train
	 * @param start Starting path separator (typically a semaphore)
	 * @return ReservationResult indicating success or failure reason
	 */
	override fun reservePathToAny(
		trainId: String,
		start: DynamicPathSeparator
	): PathReservationService.ReservationResult {
		logger.debug {
			"reservePathToAny: Searching for available targets from $start for $trainId"
		}

		// ========================================
		// STEP 1: Target Discovery
		// ========================================
		// Collect all potential targets (InOuts and semaphores) excluding start itself.
		// Why two separate lists? InOuts will be sorted differently than semaphores.
		val inouts = mutableListOf<DynamicInOut>()
		val semaphores = mutableListOf<DynamicPathSeparator>()

		// Add InOuts (except start)
		// Note: toDynamic() converts static InOut to DynamicInOut for state tracking
		environment.getInOuts().forEach { inout ->
			val dynamicInOut = environment.toDynamic(inout)
			if (dynamicInOut != start) {
				inouts.add(dynamicInOut as DynamicInOut)
			}
		}

		// Add semaphores (except start)
		// Note: getAllSemaphores() scans the grid to find all DynamicRailSemaphore instances
		getAllSemaphores().forEach { semaphore ->
			if (semaphore != start) {
				semaphores.add(semaphore)
			}
		}

		logger.debug {
			"reservePathToAny: Found ${inouts.size} InOut(s) and ${semaphores.size} semaphore(s) from $start"
		}

		// ========================================
		// STEP 2: Intelligent InOut Sorting
		// ========================================
		// Sort InOuts with preference for opposite-side targets (forward progress).
		// This is the most complex part of the algorithm.
		//
		// **Why orientation matters:**
		// - Semaphores have orientation: true = points forward, false = points backward
		// - InOuts also have orientation indicating which side of the network they're on
		// - Opposite orientation = crossing the network (forward progress)
		// - Same orientation = returning to same side (backward movement)
		//
		// **Why path length matters:**
		// - Among targets with same orientation preference, choose shortest path
		// - Reduces travel time and track occupation duration
		//
		// **Why partition before sorting:**
		// - Ensures ALL opposite-side targets tried before ANY same-side target
		// - Even if same-side target is closer, opposite-side is preferred
		val sortedInOuts =
			when (start) {
				is OrientedPathSeparator -> {
					val startOrientation = start.getOrientation()

					// Partition InOuts by orientation: opposite-side first, same-side last
					// Example: if start.orientation = false (points left/backward)
					//   - oppositeSide = InOuts with orientation = true (right/forward)
					//   - sameSide = InOuts with orientation = false (left/backward)
					val (oppositeSide, sameSide) = inouts.partition { it.getOrientation() != startOrientation }

					// Sort each partition by path length (shortest first)
					// Note: findAllTopologicalPaths returns empty list if no path exists
					// Using firstOrNull() gets shortest path (navigator returns sorted by length)
					// Int.MAX_VALUE ensures unreachable targets sorted last
					val sortedOpposite =
						oppositeSide.sortedBy { target ->
							navigator.findAllTopologicalPaths(start, target).firstOrNull()?.size ?: Int.MAX_VALUE
						}
					val sortedSame =
						sameSide.sortedBy { target ->
							navigator.findAllTopologicalPaths(start, target).firstOrNull()?.size ?: Int.MAX_VALUE
						}

					// Combine: [shortest opposite-side, ..., longest opposite-side,
					//           shortest same-side, ..., longest same-side]
					sortedOpposite + sortedSame
				}
				else -> {
					// Start has no orientation info (shouldn't happen for semaphores, but handle gracefully)
					// Just sort by distance - no orientation preference
					inouts.sortedBy { target ->
						navigator.findAllTopologicalPaths(start, target).firstOrNull()?.size ?: Int.MAX_VALUE
					}
				}
			}

		// ========================================
		// STEP 3: Target Prioritization
		// ========================================
		// Combine sorted InOuts with unsorted semaphores.
		// InOuts tried first because reaching an exit point is better than stopping mid-network.
		// Semaphores are not sorted because:
		// - They represent mid-network stopping points (less desirable than InOuts)
		// - Sorting cost not justified for secondary targets
		// - If all InOuts blocked, any semaphore is acceptable
		val sortedTargets: List<DynamicPathSeparator> = sortedInOuts + semaphores

		logger.debug {
			"reservePathToAny: Target order (InOuts first): ${sortedTargets.joinToString(", ")}"
		}

		// ========================================
		// STEP 4: Reservation Attempts
		// ========================================
		// Try each target in priority order until a reservation succeeds.
		// This is a greedy algorithm: return first success, don't try to find "optimal" path.
		//
		// **Why greedy is correct:**
		// - Targets are already sorted by preference (opposite-side, short paths first)
		// - First success is guaranteed to be the best available option
		// - Trying all paths to find "optimal" would be wasteful (dynamic state changes anyway)
		var lastResult: PathReservationService.ReservationResult? = null

		for (target in sortedTargets) {
			val result = reservePath(trainId, start, target)

			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					logger.debug {
						"reservePathToAny: Successfully reserved path from $start to $target for $trainId"
					}
					return result
				}
				is PathReservationService.ReservationResult.Conflict -> {
					// Conflict indicates serious error (race condition or registry corruption)
					// Abort immediately - don't try other targets
					logger.warn {
						"reservePathToAny: Conflict detected at ${result.conflictingBlock}, aborting"
					}
					return result
				}
				else -> {
					// Path not available (blocked, occupied, or no path exists)
					// Try next target
					logger.trace { "reservePathToAny: Path to $target not available, trying next target" }
					lastResult = result
				}
			}
		}

		// ========================================
		// STEP 5: All Targets Failed
		// ========================================
		// No available path found after trying all targets.
		if (sortedTargets.isEmpty()) {
			logger.warn { "reservePathToAny: No targets found from $start" }
			return PathReservationService.ReservationResult.NoPathExists
		}

		logger.warn {
			"reservePathToAny: No available path from $start for $trainId " +
				"(tried ${sortedTargets.size} targets, all blocked or unreachable)"
		}

		// Return last failure result (or AllPathsBlocked if no result available)
		return lastResult ?: PathReservationService.ReservationResult.AllPathsBlocked(sortedTargets.size)
	}

	/**
	 * Get all semaphores in the network by scanning the grid.
	 *
	 * ## Implementation
	 *
	 * Scans the grid using dynamically-obtained dimensions (getCols(), getRows())
	 * to find all DynamicRailSemaphore instances.
	 *
	 * ## Grid Dimensions
	 *
	 * No hardcoded dimensions - uses grid.cols and grid.rows for
	 * dynamic discovery. This is acceptable for reservePathToAny() which is
	 * called infrequently (only when train needs new path).
	 *
	 * ## Type Safety
	 *
	 * The environment parameter is typed as SimulationEnvironment, but at runtime
	 * it's always a SimulationContext (which extends Context). We cast to access
	 * getRailWayNetGrid() for grid scanning. This is safe because:
	 * - PathReservationService is only used in simulation mode
	 * - SimulationContext always implements Context interface
	 * - All Koin module configurations pass DefaultSimulationContext
	 *
	 * @return List of all DynamicRailSemaphore instances in the network
	 */
	private fun getAllSemaphores(): List<DynamicRailSemaphore> {
		// Use interface method (added to SimulationEnvironment for navigation services)
		val grid = environment.getRailWayNetGrid()
		val semaphores = mutableListOf<DynamicRailSemaphore>()

		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell =
					grid[
						cz.vutbr.fit.interlockSim.util
							.Point(x, y)
					]
				if (cell is DynamicRailSemaphore) {
					semaphores.add(cell)
				}
			}
		}

		logger.trace { "getAllSemaphores: Found ${semaphores.size} semaphore(s) in grid" }
		return semaphores
	}

	// ========== Private helper methods ==========

	/**
	 * Find all semaphores reachable from start separator via specific track section.
	 *
	 * This method navigates from the starting separator through the given track section
	 * to discover ALL oriented semaphores (OrientedPathSeparator instances) reachable
	 * in the network, skipping backward-facing semaphores that show RED.
	 *
	 * ## Algorithm (Modified for Multi-Path Discovery)
	 *
	 * 1. Calculate travel direction from start + next track section
	 * 2. Use BFS to explore ALL reachable separators through the network
	 * 3. At switches, explore ALL outgoing edges (enables parallel path discovery)
	 * 4. Filter separators to include only:
	 *    - InOut elements (always valid endpoints)
	 *    - Forward-facing semaphores (direction() matches travel direction)
	 * 5. Return list of all valid targets
	 *
	 * ## Rationale for Multi-Path Discovery
	 *
	 * **Why return ALL reachable semaphores instead of just the first?**
	 * - In networks with parallel paths (e.g., shunting loops with k1 and k2 tracks),
	 *   multiple semaphores may be reachable from the same starting point.
	 * - When the first path is blocked (e.g., k1 occupied by Train 1), the service
	 *   should retry alternative paths (e.g., k2 for Train 2).
	 * - This enables true parallel operations with shared switches but disjoint tracks.
	 *
	 * **Backward-facing semaphores must be skipped**: A semaphore whose direction() does NOT
	 * match the travel direction shows RED and cannot be changed. Path discovery must continue
	 * until finding a forward-facing semaphore (can show GREEN) or an InOut destination.
	 *
	 * **InOut elements are bidirectional**: InOut points represent entry/exit to external
	 * network and are always valid endpoints regardless of orientation.
	 *
	 * ## Return Value
	 *
	 * List of unique DynamicPathSeparator instances representing valid targets:
	 * - **Empty list**: No valid semaphore or InOut found (dead-end, buffer stop)
	 * - **One or more elements**: All forward-facing semaphores or InOuts reachable via next
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
	 * **Parallel paths with shared switches:**
	 * ```
	 * Travel RIGHT →
	 * zA (orient=false) → vA (switch) → {
	 *   doA1 (backward) → k1 → doB1 (forward) ← vB (switch)
	 *   doA2 (backward) → k2 → doB2 (forward) ← vB (switch)
	 * }
	 * Result: [doB1, doB2]  (both forward-facing semaphores reachable via k1 and k2)
	 * ```
	 *
	 * **Skip backward-facing semaphore:**
	 * ```
	 * Travel RIGHT →
	 * zA (orient=false, faces RIGHT) → doA1 (orient=true, faces LEFT) → doB1 (orient=false, faces RIGHT)
	 * Result: [doB1]  (skips doA1 because it's backward-facing/RED)
	 * ```
	 *
	 * **Stop at InOut:**
	 * ```
	 * Travel LEFT ←
	 * doA2 (orient=true) → zA (orient=false, faces RIGHT/backward) → A (InOut)
	 * Result: [A]  (skips zA, reaches InOut destination)
	 * ```
	 *
	 * @param start Starting path separator (typically InOut or semaphore)
	 * @param next First track section after start (direction to search)
	 * @return List of unique DynamicPathSeparator instances that are forward-facing semaphores or InOuts
	 */
	private fun findNextSemaphoresVia(
		start: PathSeparator,
		next: TrackSection
	): List<DynamicPathSeparator> {
		logger.trace {
			"findNextSemaphoresVia: Starting multi-path search from $start via $next"
		}

		val travelDirection = calculateTravelDirection(start, next)
		logger.trace { "findNextSemaphoresVia: Travel direction=$travelDirection" }

		// BFS to explore all reachable separators
		val validTargets = mutableListOf<DynamicPathSeparator>()
		val queue = mutableListOf<Pair<PathSeparator, TrackSection?>>()
		queue.add(Pair(start, next))
		val visited = mutableSetOf<PathSeparator>()
		visited.add(start)

		while (queue.isNotEmpty()) {
			val (currentSep, currentSection) = queue.removeAt(0)
			if (currentSection == null) continue

			val nextSeparator = currentSection.getSecondEnd(currentSep)
			if (!visited.add(nextSeparator)) continue

			// Check if separator is a valid target and whether to stop exploring beyond it
			val stopExploration = processReachedSeparator(nextSeparator, travelDirection, validTargets)

			// Only explore outgoing paths if we haven't reached a stopping point
			// This discovers parallel paths UP TO the first layer of forward-facing semaphores
			if (!stopExploration) {
				exploreOutgoingPaths(nextSeparator, currentSep, currentSection, start, queue)
			}
		}

		return prioritizeInOuts(validTargets)
	}

	/**
	 * Process a reached separator to check if it's a valid target.
	 *
	 * @return true if exploration should stop beyond this separator (forward-facing semaphore/InOut),
	 *         false if exploration should continue (backward-facing semaphore/junction)
	 */
	private fun processReachedSeparator(
		separator: PathSeparator,
		travelDirection: cz.vutbr.fit.interlockSim.objects.core.Cell.Segment,
		validTargets: MutableList<DynamicPathSeparator>
	): Boolean =
		when {
			// InOut is always a valid endpoint (bidirectional) - stop exploration
			separator is cz.vutbr.fit.interlockSim.objects.cells.InOut ||
				separator is DynamicInOut -> {
				val dynamicSep = environment.toDynamic(separator)
				logger.trace { "findNextSemaphoresVia: Found InOut: $dynamicSep" }
				validTargets.add(dynamicSep)
				true // Stop exploring beyond InOuts
			}

			// Oriented semaphore - check if facing forward
			separator is OrientedPathSeparator -> {
				val isFacingForward = (separator.direction() == travelDirection)
				if (isFacingForward) {
					val dynamicSep = environment.toDynamic(separator)
					logger.trace { "findNextSemaphoresVia: Found forward-facing semaphore: $dynamicSep" }
					validTargets.add(dynamicSep)
					true // Stop exploring beyond forward-facing semaphores
				} else {
					logger.trace { "findNextSemaphoresVia: Skipping backward-facing semaphore: $separator" }
					false // Continue exploring beyond backward-facing semaphores
				}
			}

			else -> false // Continue exploring
		}

	/**
	 * Explore all outgoing paths from a separator.
	 * At the FIRST junction, explores ALL branches. At subsequent junctions, uses single-path navigation.
	 */
	private fun exploreOutgoingPaths(
		separator: PathSeparator,
		currentSep: PathSeparator,
		currentSection: TrackSection,
		start: PathSeparator,
		queue: MutableList<Pair<PathSeparator, TrackSection?>>
	) {
		val grid = environment.getRailWayNetGrid()
		val graph = environment.getGraph()
		val location = grid.getLocation(separator) ?: return

		@Suppress("UNCHECKED_CAST")
		val edges = graph.assignedEdges(location) as Map<*, *>

		val outgoingEdges =
			edges.entries.filter { (_, block) ->
				val trackSection = block as? TrackSection
				trackSection != null && trackSection != currentSection
			}

		when {
			outgoingEdges.size > 1 -> {
				// At ANY junction: explore ALL branches (enables full parallel path discovery)
				logger.trace {
					"findNextSemaphoresVia: At junction $separator, exploring ${outgoingEdges.size} branches"
				}
				outgoingEdges.forEach { (_, block) ->
					queue.add(Pair(separator, block as TrackSection))
				}
			}
			outgoingEdges.size == 1 -> {
				// Single path forward
				queue.add(Pair(separator, outgoingEdges.first().value as TrackSection))
			}
		}
	}

	/**
	 * Prioritize InOuts over semaphores in result list.
	 */
	private fun prioritizeInOuts(validTargets: List<DynamicPathSeparator>): List<DynamicPathSeparator> {
		val distinctTargets = validTargets.distinct()
		val (inouts, semaphores) = distinctTargets.partition { it is DynamicInOut }
		logger.trace {
			"findNextSemaphoresVia: Returning ${inouts.size} InOut(s) + ${semaphores.size} semaphore(s)"
		}
		return inouts + semaphores
	}

	/**
	 * Calculate the travel direction from a starting separator through a track section.
	 *
	 * ## Algorithm
	 *
	 * 1. Get the location of the start separator in the grid
	 * 2. Query the graph for all edges (segment → track section) at that location
	 * 3. Find which segment connects to the 'next' track section
	 * 4. Return that segment as the travel direction
	 *
	 * ## Example
	 *
	 * ```
	 * Grid location (14,8): zA semaphore
	 * Graph edges: {Segment.A → track1, Segment.F → track2}
	 * If next=track2, return Segment.F (traveling RIGHT)
	 * ```
	 *
	 * @param start Starting path separator
	 * @param next Track section we're traveling through
	 * @return Cell.Segment indicating the direction of travel
	 * @throws IllegalStateException if start has no location or no connection to next
	 */
	private fun calculateTravelDirection(
		start: PathSeparator,
		next: TrackSection
	): cz.vutbr.fit.interlockSim.objects.core.Cell.Segment {
		val location =
			environment.getRailWayNetGrid().getLocation(start)
				?: throw IllegalStateException("No location for $start")

		@Suppress("UNCHECKED_CAST")
		val edges = environment.getGraph().assignedEdges(location) as kotlin.collections.Map<*, *>

		// Find which segment connects to 'next' track section
		// Note: edges is Map<Segment, DynamicTrackBlock> (DynamicTrackBlock implements TrackSection)
		for ((segment, block) in edges.entries) {
			if (block == next) {
				logger.trace {
					"calculateTravelDirection: start=$start, next=$next, direction=$segment"
				}
				return segment as cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
			}
		}

		throw IllegalStateException("No connection from $start to $next")
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
				block is DynamicTrackBlock && !seen.add(block) -> null // Duplicate, expected
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
	 * Extract unique railway switches from a reserved path (Tier 2).
	 *
	 * Iterates through all PathElements in the path and collects DynamicRailSwitch instances.
	 * Switches are deduplicated to ensure each switch appears only once in the result.
	 *
	 * ## Algorithm
	 *
	 * 1. Iterate through path.reservedPath elements
	 * 2. Filter for DynamicPathSeparator elements that are switches (isSwitch() == true)
	 * 3. Cast to DynamicRailSwitch
	 * 4. Return unique switches
	 *
	 * @param pathInfo The PathInfo containing the reserved path
	 * @return List of unique DynamicRailSwitch instances in the path
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	private fun extractUniqueSwitches(
		pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo
	): List<DynamicRailSwitch> {
		val seen = mutableSetOf<DynamicRailSwitch>()
		return pathInfo.reservedPath.mapNotNull { element ->
			when {
				element is DynamicPathSeparator && element.isSwitch() && element is DynamicRailSwitch -> {
					if (seen.add(element)) element else null
				}
				else -> null
			}
		}
	}

	/**
	 * Configure switches in the reserved path based on topology.
	 *
	 * For each switch in the path, determines the correct configuration (MAIN or BRANCH)
	 * based on the path topology (from/to track segments). Calls DynamicRailSwitch.setUpPath()
	 * which:
	 * 1. Calculates correct Conf based on from/to segments
	 * 2. Updates switch.conf property
	 * 3. Fires PropertyChangeSupport event for animation
	 * 4. Locks the switch
	 *
	 * ## Algorithm
	 *
	 * 1. Convert Path to indexed list for neighbor access
	 * 2. Iterate through each element with index
	 * 3. For each DynamicRailSwitch:
	 *    a. Get previous element (track or null)
	 *    b. Get next element (track or null)
	 *    c. Calculate from/to segments using environment.getSegment()
	 *    d. Call switch.setUpPath(from, to, allowedSpeed, trainOccupant)
	 *
	 * ## Railway Safety
	 *
	 * Configuration happens BEFORE semaphore signals are set, following railway
	 * interlocking principles: switches must be positioned and locked before
	 * authorizing train movement.
	 *
	 * ## Error Handling
	 *
	 * Any internal [PathSeparatorChangeException] from switch configuration
	 * is handled via logging only and is not propagated to callers. Switches that
	 * cannot be configured are skipped (this may occur when path topology doesn't
	 * actually traverse the switch).
	 *
	 * @param trainId Train identifier for logging and occupant creation
	 * @param pathInfo PathInfo containing the reserved path with switches
	 * @return Number of switches successfully configured
	 * @since Issue #300 Fix switch animation regression
	 */
	private fun configureSwitchesInPath(
		trainId: String,
		pathInfo: cz.vutbr.fit.interlockSim.objects.paths.PathInfo
	): Int {
		// Convert Path to list for indexed access
		val pathElements = pathInfo.reservedPath.toList()

		// Safe cast: environment is always SimulationContext in practice (provides getSegment())
		// Note: getSegment() is in SimulationContext interface, not SimulationEnvironment
		val context =
			environment as? cz.vutbr.fit.interlockSim.context.SimulationContext
				?: throw IllegalStateException(
					"configureSwitchesInPath requires SimulationContext for getSegment() access, " +
						"but got ${environment::class.simpleName}"
				)

		// Track count of successfully configured switches
		var configuredCount = 0

		// Iterate through path elements with index for neighbor access
		pathElements.forEachIndexed { index, element ->
			// Only process switches
			if (element is DynamicRailSwitch) {
				// Find previous Track (skip over separators)
				var previous: Track? = null
				for (i in (index - 1) downTo 0) {
					if (pathElements[i] is Track) {
						previous = pathElements[i] as Track
						break
					}
				}

				// Find next Track (skip over separators)
				var next: Track? = null
				for (i in (index + 1) until pathElements.size) {
					if (pathElements[i] is Track) {
						next = pathElements[i] as Track
						break
					}
				}

				// Skip if we don't have a next track (required for configuration)
				if (next == null) {
					logger.warn {
						"configureSwitchesInPath: Switch ${element.staticRef.getName()} " +
							"has no next track, skipping configuration"
					}
					return@forEachIndexed // Kotlin lambda: use return@label instead of continue
				}

				// Calculate from/to segments using context.getSegment()
				// from = segment the train is coming FROM
				// to = segment the train is going TO
				val from = context.getSegment(element, previous, next)
				val to = context.getSegment(element, next, previous)

				// Try to configure the switch - if it fails, skip this switch
				// Some switches in the path may not need configuration (e.g., already configured,
				// or path doesn't actually traverse the switch in a way that changes its state)
				try {
					// Get allowed speed for this switch
					val allowedSpeed = element.allowedSpeed()

					// Create minimal TrackOccupant for switch configuration
					// Switch only uses this for logging, not business logic
					val trainOccupant = MinimalTrackOccupant(trainId)

					// Configure the switch (sets conf, fires PropertyChange event, locks)
					element.setUpPath(from, to, allowedSpeed, trainOccupant)

					// Increment counter on successful configuration
					configuredCount++

					logger.info {
						"configureSwitchesInPath: Switch ${element.staticRef.getName()} " +
							"configured to ${element.conf} for train $trainId " +
							"(from=${from?.hashCode()}, to=${to?.hashCode()})"
					}
				} catch (e: PathSeparatorChangeException) {
					// Switch configuration failed - segments don't match any valid configuration
					// This is expected for switches in path that aren't actually traversed (e.g., parallel routes)
					logger.info {
						"configureSwitchesInPath: Skipped switch ${element.staticRef.getName()} " +
							"for train $trainId - path topology doesn't require configuration " +
							"(from=${from?.hashCode()}, to=${to?.hashCode()})"
					}
					logger.debug(e) { "Exception details: ${e.message}" }
				}
			}
		}

		return configuredCount
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
				// Note: OCCUPIED blocks are filtered out before calling this method.
				// RESERVED blocks are handled idempotently by setUpPath() (if from same separator).
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
		rollbackBlocks(separator, blocks)
	}

	/**
	 * Rollback block reservations only.
	 *
	 * Used when path discovery fails before registration.
	 * Only cancels block path setup via cancelPathSetup().
	 *
	 * @param separator The path separator that reserved the blocks
	 * @param blocks The blocks to rollback
	 */
	private fun rollbackBlocks(
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
				logger.warn(e) { "rollbackBlocks: Failed to rollback block $block" }
			}
		}
	}

	/**
	 * Complete rollback of path reservation including registry, PathInfo, and switches.
	 *
	 * Used when signal configuration fails after successful registration.
	 * Reverts ALL mutations:
	 * - Block reservations (cancelPathSetup)
	 * - Registry ownership (unregister from blockToTrain/trainToBlocks)
	 * - PathInfo metadata (unregister from trainToPathInfo)
	 * - Switch locks (unlock and remove from switchToTrain/trainToSwitches)
	 *
	 * @param trainId The train identifier
	 * @param blocks The blocks that were reserved
	 * @param separator The path separator that reserved the blocks (needed for cancelPathSetup)
	 */
	private fun rollbackCompleteReservation(
		trainId: String,
		blocks: List<DynamicTrackBlock>,
		separator: PathSeparator
	) {
		// Step 1: Cancel block path setup
		for (block in blocks) {
			try {
				// Only rollback if block was actually reserved from this separator
				if (block.reservedFrom === separator) {
					block.cancelPathSetup(separator)
				}
			} catch (e: Exception) {
				logger.warn(e) { "rollbackCompleteReservation: Failed to cancel block $block" }
			}
		}

		// Step 2: Unregister switches (unlock them and remove from registry)
		try {
			registry.unregisterSwitches(trainId)
		} catch (e: Exception) {
			logger.warn(e) { "rollbackCompleteReservation: Failed to unregister switches for $trainId" }
		}

		// Step 3: Unregister train from registry (removes block ownership and PathInfo)
		try {
			registry.unregister(trainId)
		} catch (e: Exception) {
			logger.warn(e) { "rollbackCompleteReservation: Failed to unregister train $trainId" }
		}

		logger.debug {
			"rollbackCompleteReservation: Completed full rollback for train $trainId"
		}
	}

	/**
	 * Unregister all block reservations for a train.
	 *
	 * Removes the train from the registry, freeing all blocks it owns.
	 * Called when a train completes its journey.
	 *
	 * @param trainId The train identifier to unregister
	 * @return List of blocks that were released
	 */
	override fun unregister(trainId: String): List<DynamicTrackBlock> {
		val releasedBlocks = registry.unregister(trainId)
		logger.info {
			"unregister: Released ${releasedBlocks.size} blocks for train '$trainId': " +
				releasedBlocks.joinToString(", ") { it.toString() }
		}
		return releasedBlocks
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry if it is FREE (no occupant).
	 * Called by Train's Tail process after leaving a block.
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if still occupied or not owned
	 */
	override fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean = registry.unregisterBlock(trainId, block)

	/**
	 * Minimal TrackOccupant implementation for switch configuration.
	 *
	 * Switches only use the `name` property for logging during setUpPath().
	 * The distance and semaphore methods are not used during configuration,
	 * so they return placeholder values.
	 *
	 * @property trainId The train identifier for logging
	 */
	private class MinimalTrackOccupant(
		private val trainId: String
	) : TrackOccupant {
		override val name: String get() = trainId

		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}
}
