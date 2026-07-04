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
 * Grouped collision-detection services facade, accessed via
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getCollisionServices].
 *
 * Mirrors the [cz.vutbr.fit.interlockSim.context.navigation.RoutingServices] segregation
 * pattern: collision-related accessors are grouped behind one sub-interface rather than
 * being flattened directly onto [cz.vutbr.fit.interlockSim.context.SimulationEnvironment].
 * Future sub-phases (SP4 predictive analysis, SP5 auto-halt, SP6 UI presentation) can grow
 * this facade without bloating the top-level environment interface.
 *
 * @since Issue #611 (Goal 3 SP1)
 */
interface CollisionServices {
	/**
	 * Get the [CollisionDetectionService] scoped to this simulation context.
	 *
	 * @return the collision detection service for this context
	 * @since Issue #611 (Goal 3 SP1)
	 */
	fun getCollisionDetectionService(): CollisionDetectionService

	/**
	 * Subscribe to collision warnings emitted by the [CollisionDetectionService].
	 *
	 * The listener is called synchronously on the simulation thread when a
	 * [CollisionWarning] is emitted. Listeners registered after
	 * [cz.vutbr.fit.interlockSim.context.SimulationContext.run] has started are silently
	 * ignored (same contract as
	 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onBlockEvent]).
	 *
	 * @param listener the callback to invoke on each detected warning
	 * @since Issue #611 (Goal 3 SP1)
	 */
	fun onCollisionWarning(listener: (CollisionWarning) -> Unit)
}
