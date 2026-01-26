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
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of TrainNavigationService.
 *
 * ## Architecture
 *
 * This service wraps the existing `pathToNextSemaphore` logic from SimulationEnvironment
 * with train ownership validation. It ensures trains only navigate through blocks they
 * have reserved.
 *
 * ## Dependencies
 *
 * - **SimulationEnvironment**: Provides pathToNextSemaphore for path finding
 * - **PathReservationRegistry**: Provides block ownership information
 *
 * Both dependencies are injected via constructor (Koin DI).
 *
 * ## Algorithm
 *
 * 1. Call `environment.pathToNextSemaphore(separator, next)` to get candidate path
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
 * @param environment Simulation environment for path finding
 * @param registry Registry for checking block ownership
 * @see TrainNavigationService
 * @since Issue #295 (Phase 3 of Issue #292)
 */
class DefaultTrainNavigationService(
	private val environment: SimulationEnvironment,
	private val registry: PathReservationRegistry
) : TrainNavigationService {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator,
		next: TrackSection
	): Path? {
		logger.debug {
			"findReservedPathForTrain: train '$trainId' requesting path from $separator via $next"
		}

		// Step 1: Get candidate path using existing pathToNextSemaphore logic
		val candidatePath = environment.pathToNextSemaphore(separator, next)
		if (candidatePath == null) {
			logger.debug {
				"findReservedPathForTrain: no topological path exists from $separator via $next"
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
		separator: PathSeparator,
		next: TrackSection
	): Boolean {
		logger.trace {
			"isPathReservedForTrain: checking availability for train '$trainId' from $separator via $next"
		}

		// Use findReservedPathForTrain and convert result to boolean
		// This reuses the same validation logic without code duplication
		val path = findReservedPathForTrain(trainId, separator, next)
		val available = path != null

		logger.trace {
			"isPathReservedForTrain: path ${if (available) "IS" else "NOT"} available for train '$trainId'"
		}
		return available
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
