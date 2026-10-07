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

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Returns the unique [DynamicTrackBlock]s of [path], in path order.
 *
 * A path may contain the same block more than once (switch "around" blocks appear twice), and each
 * physical block must be reserved only once, so duplicates are dropped silently.
 *
 * In a SimulationContext every block in the graph is a [DynamicTrackBlock]. A section whose block is
 * not one indicates a context type mismatch: it is dropped and logged at WARN.
 *
 * @since Issue #1123 — replaces the near-identical private copies in [DefaultPathReservationService]
 *   (which logged the mismatch), `MultiTrainLoop` and `DefaultTrainNavigationService` (which dropped
 *   it silently); the WARN is kept, and the train service pre-filters its mixed-element `Path` to
 *   sections (`filterIsInstance<TrackSection>()`) at its call site
 */
internal fun extractUniqueBlocks(path: List<TrackSection>): List<DynamicTrackBlock> {
	val seen = mutableSetOf<DynamicTrackBlock>()
	return path.mapNotNull { section ->
		when (val block = section.getTrackBlock()) {
			is DynamicTrackBlock -> if (seen.add(block)) block else null
			else -> {
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
