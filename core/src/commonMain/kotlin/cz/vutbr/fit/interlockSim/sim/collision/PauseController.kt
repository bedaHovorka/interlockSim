/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

/**
 * Minimal pause bridge: allows collision detection to request a simulation pause
 * without depending on the full [cz.vutbr.fit.interlockSim.context.SimulationController] interface.
 *
 * [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext] implements this interface and
 * delegates to the active [cz.vutbr.fit.interlockSim.context.SimulationController] (e.g.
 * [cz.vutbr.fit.interlockSim.gui.SimulationRunner]) so that [CollisionDetectionService]
 * can pause the simulation without a compile-time dependency on the desktop-ui module.
 *
 * @since Issue #611 (Goal 3 SP1)
 */
fun interface PauseController {
	/**
	 * Request an immediate pause of the simulation.
	 *
	 * Called by [CollisionDetectionService] when a [CollisionWarning] is detected.
	 * Implementations must be thread-safe; this may be called from the simulation thread.
	 */
	fun requestPause()
}
