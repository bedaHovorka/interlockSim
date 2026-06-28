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
 * Result of a static automatic path search.
 *
 * @property start starting separator of the route
 * @property target target separator of the route
 * @property sections ordered list of track sections from [start] to [target]
 * @property totalCost sum of [PathCostFunction.cost] over [sections]
 */
data class PathFindingResult(
	val start: PathSeparator,
	val target: PathSeparator,
	val sections: List<TrackSection>,
	val totalCost: Double
) {
	/**
	 * Number of track elements in the route.
	 */
	val elementCount: Int
		get() = sections.size

	/**
	 * Total physical length of the route in meters.
	 */
	val totalLength: Double
		get() = sections.sumOf { it.length() }
}

/**
 * Automatic path finding over static railway topology.
 *
 * This service builds on [cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator]
 * to compute valid routes from a given entry [InOut] to a given exit [InOut].
 * It is intentionally decoupled from dynamic reservation state so that it can
 * be used from both [cz.vutbr.fit.interlockSim.context.EditingContext] and
 * [cz.vutbr.fit.interlockSim.context.SimulationContext].
 *
 * ## Design notes for future slices
 *
 * - [findShortestPath] accepts an optional [PathCostFunction] so the same
 *   implementation can optimize for element count, length, or estimated travel
 *   time without structural changes.
 * - The result type is intentionally lightweight (list of [TrackSection])
 *   because [cz.vutbr.fit.interlockSim.objects.paths.Path] requires a
 *   [cz.vutbr.fit.interlockSim.context.SimulationContext]. Callers in a
 *   simulation context can convert the result using [PathInfoBuilder] or
 *   [PathReservationService] if a full [Path] is required.
 * - Dynamic constraints (reserved blocks, semaphore states) are not applied
 *   in this slice; the API is designed so that a future [PathConstraint] can
 *   be added as a parameter to the search methods without breaking existing
 *   callers.
 *
 * @see PathCostFunction
 * @see PathCostFunctions
 */
interface AutomaticPathFindingService {
	/**
	 * Find the cheapest static route from [start] to [target].
	 *
	 * Uses Dijkstra's algorithm over the topology graph exposed by the
	 * injected [cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator].
	 * The search respects track directionality and switch positions because
	 * navigation is delegated to the topology navigator.
	 *
	 * @param start starting path separator (typically an [InOut])
	 * @param target target path separator (typically an [InOut])
	 * @param costFunction cost metric to minimize; defaults to element count
	 * @return [PathFindingResult] if a route exists, `null` otherwise
	 */
	fun findShortestPath(
		start: PathSeparator,
		target: PathSeparator,
		costFunction: PathCostFunction = PathCostFunctions.BY_ELEMENT_COUNT
	): PathFindingResult?

	/**
	 * Return all topologically valid routes from [start] to [target], sorted
	 * by [costFunction] from cheapest to most expensive.
	 *
	 * The number of returned routes is capped by [maxPaths] to avoid runaway
	 * enumeration on networks with many switch permutations. For a network
	 * with a single route the returned list has exactly one element.
	 *
	 * @param start starting path separator
	 * @param target target path separator
	 * @param maxPaths upper bound on the number of returned routes
	 * @param maxDepth maximum search depth passed to the topology navigator
	 * @param costFunction cost metric used for sorting
	 * @return sorted list of routes; empty when no route exists
	 */
	fun findAllPaths(
		start: PathSeparator,
		target: PathSeparator,
		maxPaths: Int = DEFAULT_MAX_PATHS,
		maxDepth: Int = DEFAULT_MAX_DEPTH,
		costFunction: PathCostFunction = PathCostFunctions.BY_ELEMENT_COUNT
	): List<PathFindingResult>

	/**
	 * Check whether a static route exists from [start] to [target].
	 *
	 * This is a convenience wrapper around [findShortestPath] and can be used
	 * for quick validation or UI enable/disable decisions.
	 */
	fun isPathAvailable(
		start: PathSeparator,
		target: PathSeparator
	): Boolean = findShortestPath(start, target) != null

	companion object {
		/**
		 * Default cap for returned alternative routes.
		 */
		const val DEFAULT_MAX_PATHS: Int = 100

		/**
		 * Default search depth cap.
		 */
		const val DEFAULT_MAX_DEPTH: Int = 100
	}
}
