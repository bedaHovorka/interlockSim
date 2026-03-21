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

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Factory for transforming EditingContext to SimulationContext.
 *
 * Enables workflow: edit network → save → load → simulate.
 *
 * This transformer creates a new SimulationContext from an existing EditingContext
 * by:
 * 1. Copying all cells from editing grid to simulation grid (NodeCell + TrackBlockPart)
 * 2. Copying the graph structure (track block connections)
 * 3. Copying InOut elements list
 * 4. Copying configuration properties (currentMaxSpeed, currentTrackLength, currentNameString)
 * 5. Transforming static grid to dynamic grid using GridTransformer
 * 6. Initializing dynamic wrapper mappings for PathSeparators (InOut, RailSemaphore, RailSwitch)
 *
 * The transformation preserves:
 * - Network structure (all cells and connections)
 * - Track properties (length, speed limits)
 * - Grid dimensions
 * - Graph topology
 *
 * Usage example:
 * ```kotlin
 * // User edits network in GUI
 * val editingContext: EditingContext = ...
 *
 * // User wants to simulate
 * val processFactory = get<SimulationProcessFactory>()
 * val simulationContext = ContextTransformer.createSimulationContext(editingContext, processFactory)
 * simulationContext.run()
 * ```
 *
 * Thread Safety: This is a stateless singleton object, thread-safe for transformations.
 * However, the created SimulationContext instances are NOT thread-safe (kDisco single-threaded).
 *
 * Koin Integration: Register as singleton in simulationModule for dependency injection.
 *
 * @see EditingContext
 * @see SimulationContext
 * @see DefaultSimulationContext.fromEditingContext
 * @see GridTransformer
 *
 * @author Bedrich Hovorka
 * @since 2026-01-20
 */
object ContextTransformer {
	/**
	 * Create SimulationContext from EditingContext.
	 *
	 * Transforms the editing context's static network configuration into a simulation-ready
	 * context with dynamic wrapper mappings.
	 *
	 * Implementation:
	 * - Delegates to DefaultSimulationContext.fromEditingContext()
	 * - Uses GridTransformer for static-to-dynamic grid transformation
	 * - Preserves all network structure and configuration properties
	 *
	 * @param editingContext The editing context with static network configuration
	 * @param processFactory Factory for creating simulation processes (Generator, InOutWorker)
	 * @return New simulation context ready for simulation execution
	 * @throws SimulationException If editingContext is not DefaultEditingContext implementation
	 * @throws ContextCreationException If transformation fails (invalid network structure)
	 *
	 * @see DefaultSimulationContext.fromEditingContext
	 * @see GridTransformer.transformGrid
	 */
	fun createSimulationContext(
		editingContext: EditingContext,
		processFactory: SimulationProcessFactory
	): SimulationContext {
		logger.info {
			"Transforming EditingContext to SimulationContext: " +
				"grid ${editingContext.getRailWayNetGrid().cols}x${editingContext.getRailWayNetGrid().rows}, " +
				"${editingContext.getGraph().size()} track blocks"
		}

		val simulationContext =
			DefaultSimulationContext.fromEditingContext(
				editingContext,
				processFactory
			)

		// Animation and testing code paths access dynamic wrappers before run(), so
		// eagerly initialize the mapping here to guarantee consistent state snapshots.
		simulationContext.initializeDynamicMapping()

		logger.info {
			"Successfully transformed EditingContext to SimulationContext: " +
				"${simulationContext.getInOuts().size} InOuts, " +
				"ready for simulation"
		}

		return simulationContext
	}
}
