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
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
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

		// Step 1: Get PathInfo for this train (Issue #295/#296 Phase 5)
		val pathInfo = registry.getPathInfo(trainId)
		if (pathInfo == null) {
			logger.debug {
				"findReservedPathForTrain: no PathInfo registered for train '$trainId'"
			}
			return null
		}

		// Step 2: Determine next track section from PathInfo
		val dynamicSeparator = context.toDynamic(separator)
		val nextTrackSection = determineNextFromPathInfo(dynamicSeparator, pathInfo)
		if (nextTrackSection == null) {
			logger.debug {
				"findReservedPathForTrain: cannot determine next track section from PathInfo for $separator"
			}
			return null
		}

		logger.trace {
			"findReservedPathForTrain: determined next track section from PathInfo: $nextTrackSection"
		}

		// Step 3: Build path using known direction (based on working solution)
		val candidatePath = buildPathWithDirection(dynamicSeparator, nextTrackSection, pathInfo)
		if (candidatePath == null) {
			logger.debug {
				"findReservedPathForTrain: no path found from $separator with direction $nextTrackSection"
			}
			return null
		}

		// Step 4: Extract all track blocks from the path
		val blocks = extractDynamicTrackBlocks(candidatePath)
		logger.trace {
			"findReservedPathForTrain: candidate path has ${blocks.size} blocks: ${blocks.map { it.toString() }}"
		}

		// Step 5: Validate ALL blocks are reserved for this train
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

		// Step 6: All blocks owned by this train, return complete path
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

		// Step 1: Get PathInfo for this train (Issue #295/#296 Phase 5)
		val pathInfo = registry.getPathInfo(trainId)
		if (pathInfo == null) {
			logger.trace { "isPathReservedForTrain: no PathInfo registered for train '$trainId'" }
			return false
		}

		// Step 2: Determine next track section from PathInfo
		val dynamicSeparator = context.toDynamic(separator)
		val nextTrackSection = determineNextFromPathInfo(dynamicSeparator, pathInfo)
		if (nextTrackSection == null) {
			logger.trace { "isPathReservedForTrain: cannot determine next track section from PathInfo" }
			return false
		}

		// Step 3: Build path using known direction
		val candidatePath = buildPathWithDirection(dynamicSeparator, nextTrackSection, pathInfo)
		if (candidatePath == null) {
			logger.trace { "isPathReservedForTrain: no path found with direction" }
			return false
		}

		// Step 4: Extract blocks (reuse existing method)
		val blocks = extractDynamicTrackBlocks(candidatePath)

		// Step 5: Check ownership (early exit on first conflict)
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
	 * Determine the next track section from PathInfo based on current separator position.
	 *
	 * ## Algorithm (Issue #295/#296 Phase 5)
	 *
	 * Based on working `pathToNextSemaphore()` (commit 18108fa), which uses:
	 * ```kotlin
	 * var next: TrackSection? = initialNext  // THIS IS THE CRITICAL PARAMETER!
	 * ```
	 *
	 * The PathInfo.reservedPath contains the complete path with track sections in order.
	 * We need to:
	 * 1. Find current separator in the reserved path
	 * 2. Get the NEXT TrackSection element after it
	 * 3. Validate using entryDirections map (cross-check)
	 *
	 * ## Example
	 *
	 * ```
	 * PathInfo.reservedPath: [semaphore1] -> [track_A] -> [separator_X] -> [track_B] -> [semaphore2]
	 *
	 * If separator == separator_X:
	 *   - Find separator_X at index 2
	 *   - Return track_B at index 3
	 * ```
	 *
	 * @param separator Current position (where train is now)
	 * @param pathInfo Complete path metadata with entry directions
	 * @return Next TrackSection to follow, or null if separator not in path or at end
	 */
	private fun determineNextFromPathInfo(
		separator: PathSeparator,
		pathInfo: PathInfo
	): TrackSection? {
		val reservedPath = pathInfo.reservedPath

		logger.trace {
			"determineNextFromPathInfo: searching for $separator in path with ${reservedPath.size} elements"
		}

		// Walk path elements to find separator
		for (i in 0 until reservedPath.size) {
			val element = reservedPath.elementAt(i)

			// Check if this element matches our separator
			if (element == separator) {
				// Found separator! Get next element
				if (i + 1 < reservedPath.size) {
					val nextElement = reservedPath.elementAt(i + 1)
					if (nextElement is TrackSection) {
						logger.trace {
							"determineNextFromPathInfo: found separator at index $i, " +
								"next track section at index ${i + 1}: $nextElement"
						}
						return nextElement
					} else {
						logger.warn {
							"determineNextFromPathInfo: element after separator is not TrackSection: " +
								"${nextElement.javaClass.simpleName}"
						}
						return null
					}
				} else {
					logger.debug {
						"determineNextFromPathInfo: separator is last element in path (end of route)"
					}
					return null
				}
			}
		}

		logger.debug {
			"determineNextFromPathInfo: separator $separator not found in reserved path " +
				"(train may have passed this separator already)"
		}
		return null
	}

	/**
	 * Build path to next semaphore using KNOWN direction.
	 *
	 * ## Algorithm (Based on Working Solution - Commit 18108fa)
	 *
	 * This mirrors the working `pathToNextSemaphore()` method, but with the critical
	 * difference that we START with a known `initialNext` direction instead of guessing!
	 *
	 * Working solution (line 863-876):
	 * ```kotlin
	 * var next: TrackSection? = nxt  // Parameter provides direction!
	 * do {
	 *     path.add(separator)
	 *     if (next != null) {
	 *         path.add(next)
	 *         separator = next.getSecondEnd(separator)
	 *         previous = next
	 *         next = getNextTrackSection(separator, next)
	 *     }
	 * } while (next != null)
	 * ```
	 *
	 * @param startSeparator Starting position
	 * @param initialNext Initial direction (from PathInfo!)
	 * @param pathInfo Complete path metadata (for validation)
	 * @return Path to next semaphore, or null if path cannot be built
	 */
	private fun buildPathWithDirection(
		startSeparator: PathSeparator,
		initialNext: TrackSection,
		pathInfo: PathInfo
	): Path? {
		logger.debug {
			"buildPathWithDirection: building path from $startSeparator via $initialNext"
		}

		var separator = startSeparator
		var previous: TrackSection? = null
		var next: TrackSection? = initialNext
		val path = ArrayPath(context)

		do {
			path.add(separator)
			if (next != null) {
				path.add(next)
				val staticResult = next.getSecondEnd(separator)
				separator = context.toDynamic(staticResult)
				previous = next
				next = topologyNavigator.getNextTrackSection(separator, next)
			} else {
				break
			}

			// Check if we've reached an oriented semaphore
			if (separator is OrientedPathSeparator) {
				if (context.isSeparatorInDirection(separator, next, previous)) {
					path.add(separator)
					logger.debug {
						"buildPathWithDirection: found complete path with length ${path.length()}"
					}
					return path
				}
			}
		} while (next != null)

		logger.debug { "buildPathWithDirection: no path found (no oriented semaphore reached)" }
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
