/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut

/**
 * Which InOut pairs of [env] have a switch-legal route (Issue #1376): the
 * [Generator.isPairAcceptable] hook of the Praha arms.
 *
 * A pair is asked [cz.vutbr.fit.interlockSim.context.RouteFinder.isRouteAvailable], the check
 * `MultiTrainStressSpecsTest` uses (1-22 ms per Praha pair), the first time it is drawn; the answer
 * is cached, so there is no setup cost and each pair is checked at most once per run.
 */
internal class SwitchLegalPairs(
	private val env: SimulationEnvironment
) {
	private val cache = mutableMapOf<Pair<String, String>, Boolean>()

	/** Whether a route from [from] to [to] exists that needs no switch to be passed against its setting. */
	fun accepts(
		from: DynamicInOut,
		to: DynamicInOut
	): Boolean =
		cache.getOrPut(from.name to to.name) {
			env.getRouteFinder().isRouteAvailable(from.staticRef, to.staticRef, env)
		}
}
