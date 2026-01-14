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

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess

/**
 * Factory interface for creating simulation processes.
 *
 * Decouples context from concrete simulation class implementations by using
 * the Factory pattern. This allows:
 * - Different simulation engines to be plugged in
 * - Testing without concrete simulation dependencies
 * - Future migration from jDisco to DSOL/Kalasim
 *
 * ## Design Decision
 *
 * This interface is placed in the context package (not sim/) because:
 * - It's an abstraction used by contexts
 * - Only the implementation needs knowledge of concrete sim/ classes
 * - Follows Dependency Inversion Principle
 *
 * ## Future Migration
 *
 * When migrating from jDisco to DSOL/Kalasim:
 * 1. Create new factory implementation with DSOL/Kalasim classes
 * 2. Update DI configuration to use new factory
 * 3. All context code continues to work unchanged
 *
 * @see DefaultSimulationContext
 * @see SimulationContext
 */
interface SimulationProcessFactory {
	/**
	 * Create the main simulation process.
	 *
	 * The main process is responsible for generating trains and managing
	 * the overall simulation flow. Default implementation creates a Generator.
	 *
	 * @param context The simulation context
	 * @return Main process for the simulation (e.g., Generator)
	 */
	fun createMainProcess(context: SimulationContext): LoopProcess

	/**
	 * Create worker process for an InOut point.
	 *
	 * Each InOut (entry/exit point) needs a worker process to handle
	 * trains entering and leaving the railway network.
	 *
	 * @param context The simulation context
	 * @param inOut The InOut point to create worker for
	 * @return Worker process for the InOut
	 */
	fun createInOutWorker(
		context: SimulationContext,
		inOut: InOut
	): InOutWorker
}
