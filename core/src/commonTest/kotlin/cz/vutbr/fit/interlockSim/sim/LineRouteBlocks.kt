/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared helper of the deadlock watchdog tests (Issues #1154, #1270).
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * The distinct track blocks of every route from in-out `A` to in-out `B` of [ctx] (a line topology),
 * in route-finder order: the blocks the watchdog tests stage as held or blocked.
 */
internal fun lineRouteBlocks(ctx: SimulationContext): List<DynamicTrackBlock> {
	val inOuts = ctx.getInOuts()
	val inIo = inOuts.first { it.name == "A" }
	val outIo = inOuts.first { it.name == "B" }
	return ctx
		.getRouteFinder()
		.findRoutes(inIo.staticRef, outIo.staticRef, ctx)
		.flatMap { extractUniqueBlocks(it.segments) }
		.distinct()
}
