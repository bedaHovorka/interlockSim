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

/**
 * Returns the blocks of this list that train [trainId] does not already own, in the original order.
 *
 * A block is "owned" when its [DynamicTrackBlock.trainName] equals [trainId] (RESERVED or OCCUPIED by
 * that train). What remains is the forward part of a candidate path: free blocks and blocks held by
 * other trains. Owned blocks must be left out because:
 * 1. OCCUPIED: the train has already been there (blocks behind it).
 * 2. RESERVED: the block is already reserved from a (possibly different) separator, and re-reserving it
 *    from another separator causes TOCTOU conflicts (Issue #296).
 *
 * Both callers read [DynamicTrackBlock.trainName] on the block itself, not the registry, and they must keep
 * doing so with this one form: the Step 1.6 probe in `RouteCandidateScreen.screenDivergentCandidates`
 * predicts the merge that Step 2i (in `CandidateCommitter`, called from `reservePath`) would perform, and the
 * already-owned early return of `reservePath` (empty result) matches the screen's carve-out for a
 * candidate with no forward blocks. If the two selections diverged, the probe would lie about the merge.
 *
 * @since Issue #1224 -- replaces the two hand-written copies of this exclusion (SP12 report section 3.7)
 */
internal fun List<DynamicTrackBlock>.excludingOwnedBy(trainId: String): List<DynamicTrackBlock> =
	filterNot { it.trainName == trainId }
