/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * Outcome of one bounded switch-constrained route enumeration
 * ([DefaultTopologyNavigator.searchSwitchConstrainedPaths]).
 *
 * The outcome depends on the static topology only, never on block state, so it may be cached for
 * the lifetime of the navigator that produced it.
 *
 * @since Issue #1151
 */
internal sealed class RouteSearch {
	/** Search nodes taken from the queue, the budget-limited quantity. */
	abstract val expandedNodes: Int

	/**
	 * The enumeration finished within its node budget.
	 *
	 * @property paths every switch-legal route found, in enumeration order; empty when none exists
	 */
	data class Completed(
		val paths: List<List<TrackSection>>,
		override val expandedNodes: Int
	) : RouteSearch()

	/**
	 * The enumeration stopped because it would have expanded more than [nodeBudget] nodes. The
	 * routes found before the stop are dropped: a partial list would depend on the search order,
	 * not on the topology, so callers treat the pair as having no route.
	 *
	 * @property start the start separator of the search
	 * @property target the target separator of the search
	 * @property nodeBudget the budget that was exhausted
	 * @property expandedNodes nodes expanded before the stop; never more than [nodeBudget]
	 */
	data class BudgetExceeded(
		val start: PathSeparator,
		val target: PathSeparator,
		val nodeBudget: Int,
		override val expandedNodes: Int
	) : RouteSearch()
}
