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

import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Metadata about a reserved path for a train.
 *
 * This class captures the complete context of a path reservation including:
 * - Start and target separators
 * - Complete reserved path
 * - Entry directions for each block (critical for navigation)
 *
 * ## Direction Semantics (Issue #295/#296)
 *
 * The `entryDirections` map answers the question: "When entering this block,
 * which track section is the train coming FROM?"
 *
 * This is essential for:
 * - Train navigation through switches (which branch to take)
 * - Path validation (ensure train follows reserved route)
 * - Semaphore signal interpretation (oriented semaphores)
 *
 * ## Use Cases
 *
 * **PathReservationService**: Builds PathInfo when reserving a path, populating
 * entry directions from topology analysis.
 *
 * **TrainNavigationService**: Uses PathInfo to determine the next track section
 * a train should follow, ensuring it stays on the reserved route.
 *
 * **PathReservationRegistry**: Stores PathInfo per train for ownership tracking
 * and direction queries.
 *
 * @property start Starting separator where path reservation begins
 * @property target Target separator where path reservation ends (final semaphore or InOut)
 * @property reservedPath Complete reserved path with all blocks and separators
 * @property entryDirections For each reserved block, the track section train enters FROM
 *                           (key: block, value: track section train comes from)
 *
 * @see PathReservationService
 * @see TrainNavigationService
 * @see PathReservationRegistry
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/295">Issue #295</a>
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/296">Issue #296</a>
 */
data class PathInfo(
	val start: DynamicPathSeparator,
	val target: DynamicPathSeparator,
	val reservedPath: Path,
	val entryDirections: Map<DynamicTrackBlock, TrackSection>
)
