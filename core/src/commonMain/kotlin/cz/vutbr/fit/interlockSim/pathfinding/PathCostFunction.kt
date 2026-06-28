/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.pathfinding

import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Cost function used by [AutomaticPathFindingService] to weight track sections.
 *
 * Implementations must be pure functions: they must depend only on the static
 * properties of the supplied [TrackSection] and the known entry direction, and
 * must not access simulation state (reservations, occupancy, semaphore aspects).
 *
 * [entryFrom] is the [PathSeparator] from which the train enters [section].
 * It is required for direction-dependent properties such as speed limits.
 *
 * This keeps pathfinding decoupled from dynamic state, matching the static
 * topology responsibility of [cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator].
 */
fun interface PathCostFunction {
	/**
	 * Compute the cost of traversing [section] when entered from [entryFrom].
	 *
	 * @param section track section to evaluate
	 * @param entryFrom the separator from which the section is entered (for directional cost)
	 * @return non-negative cost value
	 */
	fun cost(
		section: TrackSection,
		entryFrom: PathSeparator
	): Double
}

/**
 * Canonical cost functions for automatic path finding.
 */
object PathCostFunctions {
	/**
	 * Count each track section as one unit.
	 *
	 * This is the default cost function for the first slice of Goal 2 and
	 * corresponds to a graph search that minimizes the number of traversed
	 * elements (Dijkstra degenerates to BFS when all edges have equal cost).
	 */
	val BY_ELEMENT_COUNT: PathCostFunction =
		PathCostFunction { _, _ -> 1.0 }

	/**
	 * Use the physical track length in meters as the edge cost.
	 *
	 * Produces shortest geometric routes, which is a natural next step beyond
	 * element-count minimization.
	 */
	val BY_LENGTH: PathCostFunction =
		PathCostFunction { section, _ -> section.length() }

	/**
	 * Use estimated traversal time (length / max speed) as the edge cost.
	 *
	 * Uses [entryFrom] for the directional speed limit query so that the result
	 * is correct at switches and oriented separators.
	 *
	 * This is a coarse time-based cost that does not account for train
	 * acceleration or braking; it is intended for static topology routing only.
	 */
	val BY_TRAVEL_TIME: PathCostFunction =
		PathCostFunction { section, entryFrom ->
			val maxSpeed = section.maxSpeed(entryFrom)
			if (maxSpeed > 0.0) section.length() / maxSpeed else Double.POSITIVE_INFINITY
		}
}
