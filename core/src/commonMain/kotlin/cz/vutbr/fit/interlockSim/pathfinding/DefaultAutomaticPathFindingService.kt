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

import cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of [AutomaticPathFindingService].
 *
 * The implementation delegates topology expansion to the injected
 * [DefaultTopologyNavigator] via the internal [DefaultTopologyNavigator.getSwitchConstrainedNextTrackSections]
 * primitive. This avoids polluting the public [cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator]
 * interface with a graph-expansion detail that callers such as [cz.vutbr.fit.interlockSim.sim.Train]
 * and [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService] do not need.
 *
 * The search itself is a textbook Dijkstra algorithm over states of the form
 * `(separator, incoming section)`, which is sufficient to capture direction-dependent
 * switch behaviour. Switch constraints encoded in the graph are enforced by the
 * navigator, so impossible transitions (e.g. from the straight end of a switch to
 * the branch end) are never explored.
 *
 * The queue is a plain list with O(n) `minByOrNull`. Railway networks in this
 * simulator have at most ~100 nodes, so the quadratic worst-case is irrelevant.
 */
class DefaultAutomaticPathFindingService(
	private val navigator: DefaultTopologyNavigator
) : AutomaticPathFindingService {
	/**
	 * Search state used by Dijkstra.
	 *
	 * The [incomingSection] is the track section that led into [separator].
	 * It is needed by the topology navigator to determine the outgoing
	 * direction at the next step (especially at switches and InOuts).
	 */
	private data class SearchState(
		val separator: PathSeparator,
		val incomingSection: TrackSection?
	)

	override fun findShortestPath(
		start: PathSeparator,
		target: PathSeparator,
		costFunction: PathCostFunction
	): PathFindingResult? {
		if (isSameSeparator(start, target)) {
			return PathFindingResult(start, target, emptyList(), 0.0)
		}

		val startState = SearchState(normalize(start), null)
		val distances = mutableMapOf<SearchState, Double>()
		distances[startState] = 0.0

		val predecessors = mutableMapOf<SearchState, Pair<SearchState, TrackSection>?>()
		predecessors[startState] = null

		val queue = mutableListOf(startState to 0.0)

		while (queue.isNotEmpty()) {
			val minEntry = queue.minByOrNull { it.second } ?: break
			val (current, currentCost) = minEntry
			queue.remove(minEntry)

			// Skip stale entries left over from cost updates.
			if (distances[current] != currentCost) continue

			if (isSameSeparator(current.separator, target)) {
				return buildResult(start, target, current, predecessors, currentCost)
			}

			val outgoing =
				navigator.getSwitchConstrainedNextTrackSections(
					current.separator,
					current.incomingSection
				)

			for (section in outgoing) {
				val nextSeparator = section.getSecondEnd(current.separator)
				val nextState =
					SearchState(
						normalize(nextSeparator),
						normalizeSection(section)
					)
				val edgeCost = costFunction.cost(section, current.separator)
				val newCost = currentCost + edgeCost
				val existingCost = distances[nextState] ?: Double.MAX_VALUE

				if (newCost < existingCost) {
					distances[nextState] = newCost
					predecessors[nextState] = current to section
					queue.add(nextState to newCost)
				}
			}
		}

		logger.debug { "findShortestPath: no route from $start to $target" }
		return null
	}

	override fun findAllPaths(
		start: PathSeparator,
		target: PathSeparator,
		maxPaths: Int,
		maxDepth: Int,
		costFunction: PathCostFunction
	): List<PathFindingResult> {
		val rawPaths = navigator.findAllSwitchConstrainedPaths(start, target, maxDepth)

		val results =
			rawPaths
				.map { sections ->
					val cost = computeDirectionalCost(sections, start, costFunction)
					PathFindingResult(start, target, sections, cost)
				}.sortedWith(compareBy({ it.totalCost }, { it.elementCount }))

		return if (results.size <= maxPaths) results else results.take(maxPaths)
	}

	/**
	 * Compute total path cost by walking sections in order and passing the entry
	 * separator to the cost function at each step.
	 */
	private fun computeDirectionalCost(
		sections: List<TrackSection>,
		start: PathSeparator,
		costFunction: PathCostFunction
	): Double {
		var totalCost = 0.0
		var currentSeparator = start
		for (section in sections) {
			totalCost += costFunction.cost(section, currentSeparator)
			currentSeparator = section.getSecondEnd(currentSeparator)
		}
		return totalCost
	}

	/**
	 * Reconstruct a forward-ordered list of sections from the predecessor map.
	 */
	private fun buildResult(
		start: PathSeparator,
		target: PathSeparator,
		finalState: SearchState,
		predecessors: Map<SearchState, Pair<SearchState, TrackSection>?>,
		totalCost: Double
	): PathFindingResult {
		val sections = mutableListOf<TrackSection>()
		var state: SearchState? = finalState
		while (state != null) {
			val predecessor = predecessors[state]
			if (predecessor == null) break
			sections.add(predecessor.second)
			state = predecessor.first
		}
		sections.reverse()
		return PathFindingResult(start, target, sections, totalCost)
	}

	/**
	 * Normalize a separator to its static [cz.vutbr.fit.interlockSim.objects.cells.NodeCell] reference.
	 *
	 * This makes map lookups consistent whether the service is used with an
	 * [cz.vutbr.fit.interlockSim.context.EditingContext] (static objects) or a
	 * [cz.vutbr.fit.interlockSim.context.SimulationContext] (dynamic wrappers).
	 */
	private fun normalize(separator: PathSeparator): PathSeparator = CellUtilities.assertNodeCell(separator)

	/**
	 * Normalize a track section to its static reference so that [SearchState] map
	 * lookups are consistent across static and dynamic section instances.
	 */
	private fun normalizeSection(section: TrackSection?): TrackSection? =
		section?.let { (it as? DynamicTrackBlock)?.staticRef as? TrackSection ?: it }

	/**
	 * Compare two separators by their normalized static identity.
	 */
	private fun isSameSeparator(
		a: PathSeparator,
		b: PathSeparator
	): Boolean = normalize(a) === normalize(b)
}
