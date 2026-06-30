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

import cz.vutbr.fit.interlockSim.context.NetworkState
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Default [RouteFinder] implementation.
 *
 * Delegates to the existing [AutomaticPathFindingService] and maps the engine
 * result to the domain [Route] type. Hides all [PathSeparator] and
 * [PathFindingResult] details from public callers.
 */
class DefaultRouteFinder(
	private val engine: AutomaticPathFindingService
) : RouteFinder {
	override fun findRoutes(
		from: InOut,
		to: InOut,
		state: NetworkState,
		maxRoutes: Int,
		costFunction: PathCostFunction
	): List<Route> {
		val start = normalize(from)
		val target = normalize(to)

		val results =
			engine.findAllPaths(
				start = start,
				target = target,
				maxPaths = maxRoutes,
				costFunction = costFunction
			)
		val routes =
			results.map { result ->
				Route(
					start = from,
					target = to,
					segments = result.sections,
					cost = result.totalCost,
					costBreakdown = buildBreakdown(result.sections, start, costFunction)
				)
			}
		return routes
	}

	/**
	 * Normalize an InOut or DynamicInOut to its static [PathSeparator] reference.
	 *
	 * This keeps [RouteFinder] usable from both [cz.vutbr.fit.interlockSim.context.EditingContext]
	 * (static objects) and [cz.vutbr.fit.interlockSim.context.SimulationContext]
	 * (dynamic wrappers).
	 */
	private fun normalize(separator: PathSeparator): PathSeparator = CellUtilities.assertNodeCell(separator)

	/**
	 * Compute per-segment cost contributions by walking the sections in order.
	 */
	private fun buildBreakdown(
		sections: List<TrackSection>,
		start: PathSeparator,
		costFunction: PathCostFunction
	): List<SegmentCost> {
		val breakdown = mutableListOf<SegmentCost>()
		var currentSeparator: PathSeparator = start
		for (section in sections) {
			breakdown.add(SegmentCost(section, costFunction.cost(section, currentSeparator)))
			currentSeparator = section.getSecondEnd(currentSeparator)
		}
		return breakdown
	}
}
