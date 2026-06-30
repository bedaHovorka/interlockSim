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

import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * A candidate path returned by [TopologyNavigator.findCandidatePaths] with a
 * pre-computed cost breakdown.
 *
 * ## Purpose
 *
 * Allows the dispatcher (e.g. RuleBasedDispatcher / SP2b) to score and rank
 * alternative routes without re-deriving metrics from raw section lists.
 *
 * ## Cost Fields
 *
 * ### [switchMovementCount]
 * Number of [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch] separators
 * traversed along the path (including start and target if they are switches).
 * Each switch in the path may require one position change, so a lower count
 * generally means a simpler, lower-risk route.
 *
 * ### [conflictRiskWeight]
 * A hook for the dispatcher layer to attach a dynamic conflict-risk score.
 * At the static topology level this is always `0.0` (unknown).  A
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]
 * implementation can produce an updated copy via [copy] with a value derived
 * from current block-occupation state (e.g. fraction of occupied/reserved
 * blocks in the path).
 *
 * ## Usage
 *
 * ```kotlin
 * val candidates = navigator.findCandidatePaths(start, target)
 *
 * // Score by fewest switch movements first, then lowest conflict risk
 * val best = candidates
 *     .sortedWith(compareBy({ it.switchMovementCount }, { it.conflictRiskWeight }))
 *     .firstOrNull()
 * ```
 *
 * @property sections     Ordered list of track sections constituting the path.
 * @property switchMovementCount  Number of switches traversed (≥ 0).
 * @property conflictRiskWeight   Dynamic conflict-risk hook; default is `0.0`.
 *
 * @see TopologyNavigator.findCandidatePaths
 * @since Issue #567 (Goal 2 → Goal 10 prereq)
 */
data class PathCandidate(
	val sections: List<TrackSection>,
	val switchMovementCount: Int,
	val conflictRiskWeight: Double = 0.0,
)
