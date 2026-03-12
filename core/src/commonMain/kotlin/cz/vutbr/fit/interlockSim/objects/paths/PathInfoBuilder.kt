/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Helper utility to build PathInfo from a reserved path.
 *
 * Analyzes path topology to populate entry directions for each block.
 * This is critical for train navigation through switches and junctions.
 *
 * ## Algorithm (Based on Working Solution)
 *
 * The working `pathToNextSemaphore()` algorithm (commit 18108fa, line 858-887) shows:
 *
 * ```kotlin
 * var separator = start
 * var previous: TrackSection? = null
 * var next: TrackSection? = initialNext
 * do {
 *     path.add(separator)
 *     if (next != null) {
 *         path.add(next)
 *         separator = next.getSecondEnd(separator)  // Move to next separator
 *         previous = next                           // Track direction
 *         next = getNextTrackSection(separator, next)
 *     }
 * } while (next != null)
 * ```
 *
 * Key insight: `previous = next` tracks which TrackSection was just traversed,
 * providing direction context for the next step.
 *
 * ## Entry Direction Semantics
 *
 * For each block, `entryDirections[block] = trackSection` means:
 * "When entering this block, the train comes FROM this track section"
 *
 * Example:
 * ```
 * Path: [semaphore1] -> [track_A] -> [block1] -> [track_B] -> [semaphore2]
 *
 * entryDirections[block1] = track_A  (train enters block1 from track_A)
 * ```
 *
 * ## Implementation Strategy
 *
 * Walk the reserved path elements sequentially:
 * 1. Track current position (separator)
 * 2. When encountering TrackSection, record as "entry direction"
 * 3. When encountering block (DynamicTrackBlock), associate with last TrackSection
 * 4. Use getSecondEnd() to navigate through blocks
 *
 * @property context Simulation context (for validation and logging)
 *
 * @see PathInfo
 * @see PathReservationService
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/295">Issue #295</a>
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/296">Issue #296</a>
 */
class PathInfoBuilder(
	private val context: SimulationContext
) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Build PathInfo from a reserved path.
	 *
	 * @param start Starting separator
	 * @param target Target separator
	 * @param trackSections List of track sections in the path
	 * @return PathInfo with populated entry directions
	 */
	fun buildPathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		trackSections: List<TrackSection>
	): PathInfo {
		val entryDirections = buildEntryDirectionsFromTrackSections(start, trackSections)

		logger.debug {
			"Built PathInfo: start=$start, target=$target, " +
				"track sections=${trackSections.size}, " +
				"entry directions=${entryDirections.size}"
		}

		// Build full Path object for storage
		// This creates a proper Path including separators for compatibility
		val fullPath = buildFullPath(start, target, trackSections)

		return PathInfo(
			start = start,
			target = target,
			reservedPath = fullPath,
			entryDirections = entryDirections
		)
	}

	/**
	 * Build entry directions map from track sections list.
	 *
	 * Algorithm based on working `pathToNextSemaphore()` (commit 18108fa, line 863-876):
	 * - Walk track sections sequentially
	 * - Each track section serves as entry direction for blocks it contains
	 * - TrackSection can contain multiple DynamicTrackBlocks
	 *
	 * @param start Starting separator (for traversal context)
	 * @param trackSections List of track sections in path order
	 * @return Map of block -> entry track section
	 */
	private fun buildEntryDirectionsFromTrackSections(
		start: DynamicPathSeparator,
		trackSections: List<TrackSection>
	): Map<DynamicTrackBlock, TrackSection> {
		val result = mutableMapOf<DynamicTrackBlock, TrackSection>()

		logger.trace { "Building entry directions for ${trackSections.size} track sections" }

		// Each track section may contain multiple blocks
		// The track section itself is the "entry direction" for those blocks
		for (trackSection in trackSections) {
			// Get all blocks within this track section
			val blocks = extractBlocksFromTrackSection(trackSection)

			// Associate each block with this track section as entry direction
			blocks.forEach { block ->
				result[block] = trackSection
				logger.trace {
					"Block ${block.name} entry direction: $trackSection"
				}
			}
		}

		logger.debug {
			"Built entry directions for ${result.size} blocks from ${trackSections.size} track sections"
		}

		return result
	}

	/**
	 * Extract DynamicTrackBlocks from a TrackSection.
	 *
	 * TrackSection is a wrapper that may contain one or more DynamicTrackBlocks.
	 * For simple tracks, it's typically a single block.
	 *
	 * @param trackSection The track section to analyze
	 * @return List of blocks (may be empty if track section has no blocks)
	 */
	private fun extractBlocksFromTrackSection(trackSection: TrackSection): List<DynamicTrackBlock> {
		// TrackSection implements the TrackFacility interface
		// Check if it's a DynamicTrackBlock (most common case)
		return if (trackSection is DynamicTrackBlock) {
			listOf(trackSection)
		} else {
			// Other types: check if they contain blocks
			// For now, return empty list (can be extended if needed)
			logger.warn {
				"TrackSection is not a DynamicTrackBlock: ${trackSection::class.simpleName}"
			}
			emptyList()
		}
	}

	/**
	 * Build full Path object from track sections.
	 *
	 * Creates an ArrayPath including separators for compatibility with existing code.
	 * This reconstructs the full path structure that working solution uses.
	 *
	 * @param start Starting separator
	 * @param target Target separator
	 * @param trackSections List of track sections in order
	 * @return Complete Path object
	 */
	private fun buildFullPath(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		trackSections: List<TrackSection>
	): Path {
		val path = ArrayPath(context)

		// Add starting separator
		path.add(start)

		// Add track sections (separators are implicit in getSecondEnd() traversal)
		var currentSeparator: DynamicPathSeparator = start
		for (trackSection in trackSections) {
			path.add(trackSection)
			// Navigate to next separator via this track section
			// getSecondEnd() returns static separator, convert to dynamic
			val staticResult = trackSection.getSecondEnd(currentSeparator)
			currentSeparator = context.toDynamic(staticResult)
			path.add(currentSeparator)
		}

		logger.trace {
			"Built full path with ${path.length()} elements from ${trackSections.size} track sections"
		}

		return path
	}
}
