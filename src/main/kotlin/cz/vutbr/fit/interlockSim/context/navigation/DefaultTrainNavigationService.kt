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
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of TrainNavigationService.
 *
 * ## Architecture
 *
 * This service wraps the existing `pathToNextSemaphore` logic with train ownership
 * validation. It ensures trains only navigate through blocks they have reserved.
 *
 * ## Dependencies
 *
 * - **SimulationContext**: Simulation environment for dynamic type conversion
 * - **PathReservationRegistry**: Provides block ownership information
 * - **TopologyNavigator**: Pure topology navigation (no state dependencies)
 *
 * All dependencies are injected via constructor (Koin DI).
 *
 * ## Algorithm
 *
 * 1. Call `pathToNextSemaphore()` to get candidate path using topology navigation
 * 2. If no path exists topologically, return null immediately
 * 3. Extract all DynamicTrackBlocks from the path
 * 4. For each block, validate ownership via registry
 * 5. If ANY block is not owned by trainId, return null (train must wait)
 * 6. If ALL blocks are owned by trainId, return complete path
 *
 * ## Error Handling
 *
 * This service is **read-only** and does NOT modify state. It simply returns:
 * - **Path object**: All blocks are reserved for this train (success)
 * - **null**: Path doesn't exist, or blocks are reserved for different train (wait)
 *
 * No exceptions are thrown for ownership mismatches - null indicates "wait and retry".
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** Assumes single-threaded access to network state.
 *
 * @param context Simulation environment for dynamic type conversion
 * @param registry Registry for checking block ownership
 * @param topologyNavigator Pure topology navigator (no state dependencies)
 * @see TrainNavigationService
 * @since Issue #295 (Phase 3 of Issue #292)
 * @since Issue #296 Phase 5 (Removed indirect recursion)
 */
class DefaultTrainNavigationService(
	private val context: SimulationContext,
	private val registry: PathReservationRegistry,
	private val topologyNavigator: TopologyNavigator
) : TrainNavigationService {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator
	): Path? {
		logger.debug {
			"findReservedPathForTrain: train '$trainId' requesting path from $separator"
		}

		// Step 1: Build candidate path using topology navigation
		val candidatePath = buildPathToNextSemaphore(separator, "findReservedPathForTrain")
		if (candidatePath == null) {
			logger.debug {
				"findReservedPathForTrain: no topological path exists from $separator"
			}
			return null
		}

		// Step 2: Extract all track blocks from the path
		val blocks = extractDynamicTrackBlocks(candidatePath)
		logger.trace {
			"findReservedPathForTrain: candidate path has ${blocks.size} blocks: ${blocks.map { it.toString() }}"
		}

		// Step 3: Validate ALL blocks are reserved for this train
		for (block in blocks) {
			val owner = registry.getOwner(block)
			if (owner != trainId) {
				logger.info {
					"findReservedPathForTrain: block $block is not reserved for train '$trainId' " +
						"(owner: ${owner ?: "none"}), path not available"
				}
				return null  // Block not owned by this train, return null (train waits)
			}
		}

		// Step 4: All blocks owned by this train, return complete path
		logger.debug {
			"findReservedPathForTrain: train '$trainId' owns all ${blocks.size} blocks in path, " +
				"path length ${candidatePath.length()}"
		}
		return candidatePath
	}

	override fun isPathReservedForTrain(
		trainId: String,
		separator: PathSeparator
	): Boolean {
		logger.trace {
			"isPathReservedForTrain: checking availability for train '$trainId' from $separator"
		}

		// Step 1: Build candidate path using topology navigation
		val candidatePath = buildPathToNextSemaphore(separator, "isPathReservedForTrain")
		if (candidatePath == null) {
			logger.trace { "isPathReservedForTrain: no topological path exists" }
			return false
		}

		// Step 2: Extract blocks (reuse existing method)
		val blocks = extractDynamicTrackBlocks(candidatePath)

		// Step 3: Check ownership (early exit on first conflict)
		for (block in blocks) {
			val owner = registry.getOwner(block)
			if (owner != trainId) {
				logger.trace {
					"isPathReservedForTrain: block $block not owned by train '$trainId' (owner: ${owner ?: "none"})"
				}
				return false  // Early exit on first conflict
			}
		}

		logger.trace { "isPathReservedForTrain: path IS available for train '$trainId'" }
		return true
	}

	/**
	 * Build path to next semaphore using pure topology navigation.
	 *
	 * Constructs a path from the given separator to the next oriented semaphore by:
	 * 1. Converting static separators to dynamic wrappers
	 * 2. Using TopologyNavigator to find next track sections
	 * 3. Building path incrementally until reaching final semaphore
	 *
	 * This method contains the core path-building logic that was previously duplicated
	 * in both findReservedPathForTrain() and isPathReservedForTrain().
	 *
	 * ## Returns
	 *
	 * - **Path**: Complete path to next oriented semaphore
	 * - **null**: No topological path exists (dead-end, disconnected network, etc.)
	 *
	 * @param separator Starting path separator (InOut, switch, semaphore)
	 * @param logPrefix Prefix for log messages (e.g., "findReservedPathForTrain")
	 * @return Path to next semaphore, or null if no path exists
	 */
	private fun buildPathToNextSemaphore(
		separator: PathSeparator,
		logPrefix: String
	): Path? {
		logger.debug { "$logPrefix: searching path from $separator via track section" }
		var dynamicSeparator = context.toDynamic(separator)
		logger.trace { "Converted input separator to dynamic: ${dynamicSeparator.javaClass.simpleName}" }
		var previous: TrackSection? = null
		// Get initial track section from the separator (using topologyNavigator with previous=null)
		var next: TrackSection? = topologyNavigator.getNextTrackSection(dynamicSeparator, null)
		val candidatePath = ArrayPath(context)

		do {
			// Add dynamic separator to path
			candidatePath.add(dynamicSeparator)
			if (next != null) {
				candidatePath.add(next)
				// getSecondEnd() accepts both static and dynamic (unwraps internally)
				// Returns static separator, so convert to dynamic wrapper
				val staticResult = next.getSecondEnd(dynamicSeparator)
				dynamicSeparator = context.toDynamic(staticResult)
				logger.trace {
					"Converted separator from track to dynamic: ${dynamicSeparator.javaClass.simpleName}"
				}
				previous = next
				// Use TopologyNavigator for pure topology traversal (no recursion)
				next = topologyNavigator.getNextTrackSection(dynamicSeparator, next)
			} else {
				break
			}
			// Check if we've reached the final semaphore AFTER getting next section
			// This check is outside the if block to match baseline behavior
			if (dynamicSeparator is OrientedPathSeparator) {
				// Direction check for oriented semaphores
				if (context.isSeparatorInDirection(dynamicSeparator, next, previous)) {
					// Add dynamic separator to path
					candidatePath.add(dynamicSeparator)
					logger.debug {
						"$logPrefix: found complete path to $dynamicSeparator with length ${candidatePath.length()}"
					}
					return candidatePath
				}
			}
		} while (next != null)

		logger.debug { "$logPrefix: no path found from $separator" }
		return null
	}

	/**
	 * Extract all DynamicTrackBlock instances from a path.
	 *
	 * ## Implementation
	 *
	 * Paths contain PathElements which can be:
	 * - PathSeparator (semaphores, switches, InOuts)
	 * - TrackSection (track segments)
	 *
	 * We need to extract TrackSection instances that are also DynamicTrackBlocks,
	 * filtering out TrackBlockParts (which are not reservable).
	 *
	 * ## Deduplication
	 *
	 * Paths may contain the same block multiple times (e.g., switch "around" blocks).
	 * We use a LinkedHashSet to preserve order while eliminating duplicates.
	 *
	 * @param path Path to extract blocks from
	 * @return List of unique DynamicTrackBlocks in path order
	 */
	private fun extractDynamicTrackBlocks(path: Path): List<DynamicTrackBlock> {
		val seen = LinkedHashSet<DynamicTrackBlock>()

		for (element in path) {
			// Only process TrackSection instances
			if (element !is TrackSection) continue

			// Extract the track block from the section
			// TrackSection.getTrackBlock() returns the underlying TrackBlock
			val block = element.getTrackBlock()

			// Only include DynamicTrackBlock instances (not TrackBlockPart)
			if (block is DynamicTrackBlock) {
				seen.add(block)  // LinkedHashSet ensures uniqueness
			}
		}

		logger.trace {
			"extractDynamicTrackBlocks: extracted ${seen.size} unique blocks from path with ${path.size} elements"
		}
		return seen.toList()
	}

}
