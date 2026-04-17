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
interface SimulationContextFactory : ContextFactory, CommonSimulationContextFactory {
	/**
	 * Convert editing to simulation context.
	 *
	 * Grid parameterization: Implementations should use DefaultSimulationContext.fromEditingContext()
	 * to properly transform the grid from static to dynamic cells.
	 *
	 * @param editingContext The editing context with static network
	 * @return Simulation context with transformed dynamic grid
	 * @throws ContextCreationException if editing context is invalid
	 */
	override fun createContext(editingContext: EditingContext): SimulationContext

	/**
	 * Create an empty simulation context (for testing).
	 * Converts an empty editing context to a simulation context.
	 */
	override fun createEmptyContext(): SimulationContext
}
