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

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * A planned route between two InOut elements.
 *
 * This is the value type returned by automatic path finding. It is intentionally
 * separate from [Path], which represents the concrete track sequence a train
 * actually traverses during simulation (including dynamic wrappers and reservation
 * metadata).
 *
 * @property start entry/exit point where the route begins
 * @property target entry/exit point where the route ends
 * @property segments ordered track sections from [start] to [target]
 * @property cost total cost according to the cost function used to rank the route
 * @property costBreakdown per-segment cost contribution
 */
data class Route(
	val start: InOut,
	val target: InOut,
	val segments: List<TrackSection>,
	val cost: Double,
	val costBreakdown: List<SegmentCost>
) {
	/**
	 * Number of track elements in the route.
	 */
	val elementCount: Int
		get() = segments.size

	/**
	 * Total physical length of the route in meters.
	 */
	val totalLength: Double
		get() = segments.sumOf { it.length() }
}

/**
 * Per-segment cost breakdown.
 *
 * @property section the track section
 * @property cost cost contribution for this section
 */
data class SegmentCost(
	val section: TrackSection,
	val cost: Double
)
