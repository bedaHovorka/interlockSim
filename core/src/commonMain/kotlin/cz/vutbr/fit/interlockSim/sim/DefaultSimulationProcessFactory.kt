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
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of SimulationProcessFactory using jDisco-based classes.
 *
 * This factory creates concrete simulation process instances:
 * - Generator: Main process for generating trains
 * - InOutWorker: Worker processes for InOut points
 *
 * ## Implementation Note
 *
 * This class is intentionally placed in the sim/ package because:
 * - It has direct dependencies on concrete simulation classes
 * - It's the only place that needs to know about Generator and InOutWorker
 * - Keeps simulation implementation details in sim/ package
 *
 * ## Future Migration
 *
 * When migrating from jDisco to DSOL/Kalasim:
 * 1. Create DSOLSimulationProcessFactory (or similar)
 * 2. Update Koin DI configuration to use new factory
 * 3. All context code continues to work unchanged
 * 4. This class can be deprecated or kept for backwards compatibility
 *
 * @see SimulationProcessFactory
 * @see Generator
 * @see InOutWorker
 */
class DefaultSimulationProcessFactory : SimulationProcessFactory {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Create main simulation process (Generator).
	 *
	 * Generator is responsible for:
	 * - Creating trains according to schedules
	 * - Managing simulation timing
	 * - Coordinating overall simulation flow
	 *
	 * @param env The simulation environment (accepts SimulationContext via subtyping)
	 * @return Generator process instance
	 */
	override fun createMainProcess(env: SimulationEnvironment): LoopProcess {
		logger.debug { "Creating main Generator process for simulation" }
		return Generator(env)
	}

	/**
	 * Create worker process for an InOut point.
	 *
	 * InOutWorker is responsible for:
	 * - Managing trains entering/exiting at this InOut
	 * - Waiting for path availability
	 * - Coordinating with interlocking system
	 *
	 * @param env The simulation environment (accepts SimulationContext via subtyping)
	 * @param inOut The InOut point to create worker for
	 * @return InOutWorker process instance
	 */
	override fun createInOutWorker(
		env: SimulationEnvironment,
		inOut: DynamicInOut
	): InOutWorker {
		logger.debug { "Creating InOutWorker for InOut: ${inOut.name}" }
		return InOutWorker(env, inOut)
	}
}
