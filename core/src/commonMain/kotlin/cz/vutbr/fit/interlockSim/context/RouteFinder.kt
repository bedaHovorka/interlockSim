/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunction
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunctions

/**
 * Public API for automatic route planning between InOut elements.
 *
 * Consumers (editor and simulation) request ranked routes without knowing the
 * underlying Dijkstra implementation or [cz.vutbr.fit.interlockSim.objects.core.PathSeparator]
 * types.
 */
interface RouteFinder {
	/**
	 * Return all valid routes from [from] to [to], sorted from cheapest to most
	 * expensive according to [costFunction].
	 *
	 * The [state] parameter is accepted now so future implementations can apply
	 * dynamic constraints (reserved blocks, occupancy, signal aspects). In this
	 * slice the state is ignored and search is purely topological.
	 *
	 * @param from starting InOut
	 * @param to target InOut
	 * @param state network state snapshot (ignored in this slice)
	 * @param maxRoutes upper bound on returned routes
	 * @param costFunction metric used for ranking
	 * @return ranked list of routes; empty when no route exists
	 */
	fun findRoutes(
		from: InOut,
		to: InOut,
		state: NetworkState,
		maxRoutes: Int = DEFAULT_MAX_ROUTES,
		costFunction: PathCostFunction = PathCostFunctions.BY_ELEMENT_COUNT
	): List<Route>

	/**
	 * Convenience check for route existence.
	 *
	 * @param from starting InOut
	 * @param to target InOut
	 * @param state network state snapshot (ignored in this slice)
	 * @return true if at least one route exists
	 */
	fun isRouteAvailable(
		from: InOut,
		to: InOut,
		state: NetworkState
	): Boolean = findRoutes(from, to, state, maxRoutes = 1).isNotEmpty()

	companion object {
		/**
		 * Default cap for returned alternative routes.
		 */
		const val DEFAULT_MAX_ROUTES: Int = 100
	}
}
