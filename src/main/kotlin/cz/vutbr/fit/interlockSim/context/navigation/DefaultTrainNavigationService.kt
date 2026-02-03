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
import cz.vutbr.fit.interlockSim.objects.paths.TransitionAwarePath
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of TrainNavigationService.
 *
 * ## Architecture
 *
 * This service ensures trains only navigate through blocks they have reserved by
 * following the PathInfo.reservedPath sequence instead of exploring topology.
 *
 * ## Dependencies
 *
 * - **SimulationContext**: Simulation environment for dynamic type conversion
 * - **PathReservationRegistry**: Provides block ownership information and PathInfo
 * - **TopologyNavigator**: ⚠️ UNUSED (kept for backward compatibility)
 *
 * All dependencies are injected via constructor (Koin DI).
 *
 * ## Algorithm (Issue #297 - Fixed Navigation)
 *
 * 1. Get PathInfo for trainId from registry (contains reservedPath)
 * 2. Use PathInfo.reservedPath.getNext() to follow RESERVED blocks only
 * 3. Extract all DynamicTrackBlocks from the built path
 * 4. For each block, validate ownership via registry
 * 5. If ANY block is not owned by trainId, return null (train must wait)
 * 6. If ALL blocks are owned by trainId, return complete path
 *
 * **Key Change:** No topology exploration! Trains follow reservedPath faithfully,
 * eliminating wrong turns at switches (fixes Issue #291 root cause).
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
 * @param topologyNavigator ⚠️ UNUSED (kept for backward compatibility, may be removed)
 * @see TrainNavigationService
 * @since Issue #295 (Phase 3 of Issue #292)
 * @since Issue #296 Phase 5 (Removed indirect recursion)
 * @since Issue #297 (Fixed navigation to use reserved blocks only)
 */
class DefaultTrainNavigationService(
	private val context: SimulationContext,
	private val registry: PathReservationRegistry,
	@Suppress("UNUSED_PARAMETER")
	// TODO(Issue #297): Remove in next major version after all DI configurations updated
	private val topologyNavigator: TopologyNavigator  // Kept for backward compatibility with Koin DI
) : TrainNavigationService {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator
	): Path? {
		logger.info {
			"findReservedPathForTrain: train '$trainId' requesting path from $separator"
		}

		// Step 1: Get PathInfo for this train (Issue #295/#296 Phase 5)
		val pathInfo = registry.getPathInfo(trainId)
		if (pathInfo == null) {
			logger.info {
				"findReservedPathForTrain: no PathInfo registered for train '$trainId'"
			}
			return null
		}

		// Step 2: Determine next track section from PathInfo
		val dynamicSeparator = context.toDynamic(separator)
		val nextTrackSection = determineNextFromPathInfo(dynamicSeparator, pathInfo)

		// If separator not in PathInfo, return null (train should wait for proper reservation)
		// Issue #296 Phase 8: Removed fallback mechanism - it returned wrong-direction blocks
		if (nextTrackSection == null) {
			logger.info {
				"findReservedPathForTrain: separator $separator not in PathInfo, " +
					"returning null (train should wait for new path reservation)"
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

		// Step 3.5: Handle path transitions (Issue #296 Phase 9)
		// When PathInfo instances are merged, train's current TrackSection may be from the old
		// segment and not present in the new path built from the separator forward.
		// Determine the previous track section (the one train just left to arrive at separator)
		val currentTrackSection = getCurrentTrackSection(dynamicSeparator, pathInfo)
		val finalPath = if (currentTrackSection != null &&
			currentTrackSection != nextTrackSection &&
			!candidatePath.contains(currentTrackSection)) {
			// Train is transitioning from old PathInfo to new merged PathInfo
			// Wrap path to handle getNext(currentTrackSection) correctly
			logger.debug {
				"findReservedPathForTrain: wrapping path for transition from $currentTrackSection"
			}
			TransitionAwarePath(candidatePath, currentTrackSection, nextTrackSection)
		} else {
			candidatePath
		}

		// Step 4: Extract all track blocks from the path
		val blocks = extractDynamicTrackBlocks(finalPath)
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
				"path length ${finalPath.length()}"
		}
		return finalPath
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
					return when (nextElement) {
						is TrackSection -> {
							logger.trace {
								"determineNextFromPathInfo: found separator at index $i, " +
									"next track section at index ${i + 1}: $nextElement"
							}
							nextElement
						}
						is PathSeparator -> {
							// Target separator reached - this is the end of the reserved path
							logger.debug {
								"determineNextFromPathInfo: Reached target separator $nextElement, " +
									"no more tracks in reserved path for train at $separator"
							}
							null  // Expected: train has reached destination
						}
						else -> {
							// Unexpected element type - path structure error
							logger.warn {
								"determineNextFromPathInfo: Unexpected element type after separator: " +
									"${nextElement.javaClass.simpleName}, path may be malformed"
							}
							null  // Unexpected structure
						}
					}
				} else {
					// Current separator is the last element in the path
					logger.debug {
						"determineNextFromPathInfo: Current separator $separator is last element " +
							"in reserved path, destination reached"
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
	 * Get the TrackSection that comes BEFORE the given separator in PathInfo.
	 *
	 * ## Purpose (Issue #296 Phase 9)
	 *
	 * When PathInfo instances are merged (old + new), the train's current position
	 * might be in the old segment. To detect transition cases, we need to know which
	 * TrackSection the train just left to arrive at the current separator.
	 *
	 * ## Algorithm
	 *
	 * Walk backward through the reservedPath from the separator:
	 * 1. Find the separator in the path
	 * 2. Look at the previous element (index - 1)
	 * 3. If it's a TrackSection, return it (this is the "current" the train is coming from)
	 * 4. Otherwise, return null (separator is first element, or path structure unexpected)
	 *
	 * ## Example
	 *
	 * ```
	 * PathInfo.reservedPath: [start] -> [trackA] -> [separator] -> [trackB] -> [end]
	 *                                      ^          ^
	 *                                      |          |
	 *                                  current    separator (where train is now)
	 *
	 * getCurrentTrackSection(separator, pathInfo) returns trackA
	 * ```
	 *
	 * @param separator Current separator where train is positioned
	 * @param pathInfo Complete path metadata
	 * @return TrackSection before separator, or null if separator is first element
	 */
	private fun getCurrentTrackSection(
		separator: PathSeparator,
		pathInfo: PathInfo
	): TrackSection? {
		val reservedPath = pathInfo.reservedPath

		// Find separator in path
		for (i in 0 until reservedPath.size) {
			val element = reservedPath.elementAt(i)
			if (element == separator) {
				// Found separator! Look at previous element
				if (i > 0) {
					val previous = reservedPath.elementAt(i - 1)
					if (previous is TrackSection) {
						logger.trace {
							"getCurrentTrackSection: found previous track section at index ${i - 1}: $previous"
						}
						return previous
					} else {
						logger.trace {
							"getCurrentTrackSection: element before separator is not TrackSection: " +
								"${previous.javaClass.simpleName}"
						}
						return null
					}
				} else {
					logger.trace {
						"getCurrentTrackSection: separator is first element in path (no previous section)"
					}
					return null
				}
			}
		}

		logger.trace {
			"getCurrentTrackSection: separator $separator not found in reserved path"
		}
		return null
	}

	/**
	 * Build path to next semaphore using reserved blocks from PathInfo.
	 *
	 * ## Algorithm (Issue #297 - Fixed Navigation)
	 *
	 * Instead of exploring ALL topological possibilities at switches (which could
	 * choose wrong branches), this method follows ONLY the reserved blocks in PathInfo.
	 *
	 * Key change: Uses `pathInfo.reservedPath.getNext(current)` to get the next
	 * TrackSection in the RESERVED path sequence, rather than exploring topology.
	 *
	 * Path structure: [Separator] → [TrackSection] → [Separator] → [TrackSection]
	 * - When current == null: returns first TrackSection (index 1)
	 * - When current != null: returns next TrackSection (index+2, skipping separator)
	 *
	 * ## Benefits
	 *
	 * - Trains follow reserved blocks faithfully (no wrong turns at switches)
	 * - No topology exploration (eliminates Issue #291 workaround)
	 * - Simpler logic (path structure defines navigation)
	 *
	 * @param startSeparator Starting position
	 * @param initialNext Initial direction (from PathInfo!)
	 * @param pathInfo Complete path metadata with reservedPath
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

		val reservedPath = pathInfo.reservedPath
		var separator = startSeparator
		var previous: TrackSection? = null
		var current: TrackSection? = initialNext
		val path = ArrayPath(context)

		do {
			path.add(separator)
			if (current != null) {
				path.add(current)
				val staticResult = current.getSecondEnd(separator)
				separator = context.toDynamic(staticResult)
				previous = current
				// NEW: Use PathInfo.reservedPath instead of topology exploration
				current = reservedPath.getNext(current)
			} else {
				break
			}

			// Check if we've reached an oriented semaphore
			if (separator is OrientedPathSeparator) {
				if (context.isSeparatorInDirection(separator, current, previous)) {
					path.add(separator)
					logger.debug {
						"buildPathWithDirection: found complete path with length ${path.length()}"
					}
					return path
				}
			}
		} while (current != null)

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
