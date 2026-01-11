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

import cz.vutbr.fit.interlockSim.context.SimulationContext

/**
 * Control facility
 */
abstract class Interlocking(
	protected val context: SimulationContext
) : LoopProcess() {
	// GOAL 2: Automatic Path Finding with Dijkstra algorithm
	// Future: Implement automatic route calculation from entry to exit
	// Priority: Critical | Estimate: 3 months | See LONG_TERM_GOALS.md Goal 2
	// abstract fun setupWay(from: OrientedPathSeparator, to: OrientedPathSeparator)
}
