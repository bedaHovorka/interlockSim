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

/**
 * Factory for simulation context
 */
interface SimulationContextFactory : ContextFactory {
	/**
	 * Convert editing to simulation context.
	 *
	 * Phase 6 update: Implementations should use DefaultSimulationContext.fromEditingContext()
	 * to properly transform the grid from static to dynamic cells.
	 *
	 * @param editingContext The editing context with static network
	 * @return Simulation context with transformed dynamic grid
	 * @throws ContextCreationException if editing context is invalid
	 */
	fun createContext(editingContext: EditingContext): SimulationContext
}
