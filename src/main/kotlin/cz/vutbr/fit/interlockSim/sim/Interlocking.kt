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
	// EXTENSION hledani cest, Dikstruv algoritmus, move to control
	// abstract fun setupWay(from: OrientedPathSeparator, to: OrientedPathSeparator)
}
