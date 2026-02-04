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

import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Path wrapper that handles transitions between old and new PathInfo segments.
 *
 * ## Problem
 *
 * When PathInfo instances are merged (old path + new path), the train's `current`
 * TrackSection may not be present in the new path segment. Calling `path.getNext(current)`
 * where `current` is from the OLD PathInfo will fail (returns null) because ArrayPath
 * searches for `current` in its deque and doesn't find it.
 *
 * ## Solution
 *
 * This wrapper detects the transition case and explicitly maps:
 * - `getNext(transitionCurrent)` → returns `transitionNext` (first section of new path)
 * - `getNext(otherCurrent)` → delegates to underlying path
 *
 * ## Safety
 *
 * The wrapper includes a self-collision check: if `transitionCurrent` and `transitionNext`
 * belong to the same block, it returns null instead of causing a crash.
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Train navigating from old PathInfo to new merged PathInfo
 * val oldCurrent: TrackSection = train.current  // Last section from old PathInfo
 * val newPath: Path = buildPathWithDirection(separator, initialNext, pathInfo)
 *
 * // Wrap path to handle transition
 * val transitionPath = TransitionAwarePath(newPath, oldCurrent, initialNext)
 *
 * // Train calls getNext(oldCurrent) - wrapper returns initialNext
 * val next = transitionPath.getNext(oldCurrent)  // Works! Returns initialNext
 * ```
 *
 * ## Implementation Notes
 *
 * This class delegates all Path methods to the underlying `delegate` path except
 * for `getNext()`, which implements the transition logic. All other operations
 * (iteration, collection methods, track properties) pass through unchanged.
 *
 * @param delegate The underlying Path implementation (typically ArrayPath)
 * @param transitionCurrent The TrackSection from old PathInfo (not in new path)
 * @param transitionNext The first TrackSection of new path (where train should go)
 * @since Issue #296 Phase 9
 */
class TransitionAwarePath(
	private val delegate: Path,
	private val transitionCurrent: TrackSection?,
	private val transitionNext: TrackSection
) : Path by delegate {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Get next track section, handling transition case.
	 *
	 * ## Algorithm
	 *
	 * 1. If `current == transitionCurrent`, return `transitionNext` (the transition)
	 * 2. Safety check: Verify `transitionNext` is not in the same block as `current`
	 * 3. Otherwise, delegate to underlying path's `getNext(current)`
	 *
	 * ## Safety Check Rationale
	 *
	 * The fallback mechanism (removed in this fix) could return a block the train
	 * already occupies, causing self-collision. This wrapper prevents that by
	 * checking if `transitionNext` is in the same block as `transitionCurrent`.
	 *
	 * @param current Current TrackSection (may be from old PathInfo)
	 * @return Next TrackSection to enter, or null if no valid next section
	 */
	override fun getNext(current: TrackSection?): TrackSection? {
		// Handle transition case: current is from old PathInfo, not in this path
		if (current != null && current == transitionCurrent) {
			// SAFETY CHECK: Prevent self-collision
			val currentBlock = current.getTrackBlock()
			val nextBlock = transitionNext.getTrackBlock()
			if (currentBlock == nextBlock) {
				logger.warn {
					"TransitionAwarePath: transitionNext would cause self-collision, " +
						"current=$current (block=$currentBlock), " +
						"next=$transitionNext (block=$nextBlock)"
				}
				return null
			}

			logger.debug {
				"TransitionAwarePath: handling transition from $current to $transitionNext"
			}
			return transitionNext
		}

		// Normal case: delegate to underlying path
		return delegate.getNext(current)
	}

	override fun toString(): String {
		return "TransitionAwarePath(delegate=$delegate, " +
			"transitionCurrent=$transitionCurrent, transitionNext=$transitionNext)"
	}
}
