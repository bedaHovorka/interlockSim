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
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of SimulationContextFactory.
 *
 * Creates SimulationContext instances by:
 * 1. Loading EditingContext from file/stream (via EditingContextFactory)
 * 2. Transforming EditingContext to SimulationContext (via ContextTransformer)
 *
 * This factory follows the Composite and Adapter patterns, bridging the gap between
 * EditingContextFactory (loads static networks) and SimulationContextFactory (creates
 * simulation-ready contexts).
 *
 * ## Design Pattern
 *
 * This factory composes an EditingContextFactory and a SimulationProcessFactory:
 * - EditingContextFactory loads the static network from XML (or other formats)
 * - SimulationProcessFactory creates simulation processes (Generator, InOutWorker)
 * - ContextTransformer bridges between editing and simulation contexts
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Via Koin DI:
 * val factory: SimulationContextFactory by inject()
 *
 * // Load and transform from file:
 * val simulationContext = factory.createContext(File("network.xml"))
 * simulationContext.run()
 *
 * // Or transform existing editing context:
 * val editingContext: EditingContext = ... // from GUI
 * val simulationContext = factory.createContext(editingContext)
 * ```
 *
 * ## Implementation Note
 *
 * This factory requires both EditingContextFactory and SimulationProcessFactory
 * to be provided via constructor injection. The editing factory loads the network
 * structure, and the process factory creates simulation-specific components.
 *
 * ## Future Migration
 *
 * When migrating from jDisco to DSOL/Kalasim:
 * 1. Create DSOLSimulationProcessFactory (or similar)
 * 2. Update Koin DI configuration to inject the new process factory
 * 3. This factory continues to work unchanged
 *
 * @property editingFactory Factory for loading EditingContext from files/streams
 * @property processFactory Factory for creating simulation processes (Generator, InOutWorker)
 * @see SimulationContextFactory
 * @see EditingContextFactory
 * @see ContextTransformer
 * @see DefaultSimulationContext
 * @see SimulationProcessFactory
 *
 * @author Bedrich Hovorka
 * @since 2026-01-25
 */
class DefaultSimulationContextFactory(
	private val editingFactory: EditingContextFactory,
	private val processFactory: SimulationProcessFactory
) : SimulationContextFactory {
	/**
	 * Create SimulationContext from file.
	 *
	 * Loads EditingContext from file via EditingContextFactory, then transforms
	 * it to SimulationContext.
	 *
	 * @param file Source XML file
	 * @return Simulation context ready for execution
	 * @throws ContextCreationException If file cannot be loaded or is invalid
	 *
	 * @see EditingContextFactory.createContext
	 * @see createContext
	 */
	override fun createContext(file: File): Context<*, *> {
		logger.debug { "Loading EditingContext from file: ${file.absolutePath}" }
		val editingContext = editingFactory.createContext(file) as EditingContext
		return createContext(editingContext)
	}

	/**
	 * Create SimulationContext from input stream.
	 *
	 * Loads EditingContext from stream via EditingContextFactory, then transforms
	 * it to SimulationContext.
	 *
	 * @param stream Source XML stream
	 * @return Simulation context ready for execution
	 * @throws ContextCreationException If stream cannot be loaded or is invalid
	 *
	 * @see EditingContextFactory.createContext
	 * @see createContext
	 */
	override fun createContext(stream: InputStream): Context<*, *> {
		logger.debug { "Loading EditingContext from input stream" }
		val editingContext = editingFactory.createContext(stream) as EditingContext
		return createContext(editingContext)
	}

	/**
	 * Create SimulationContext from EditingContext.
	 *
	 * Transforms the editing context's static network configuration into a simulation-ready
	 * context with dynamic wrapper mappings. This method delegates to ContextTransformer
	 * for the actual transformation logic.
	 *
	 * The transformation process:
	 * 1. Copies network structure from EditingContext
	 * 2. Transforms static grid to dynamic grid (NodeCell + TrackBlockPart → Cell)
	 * 3. Initializes dynamic wrapper mappings for PathSeparators
	 * 4. Sets up simulation processes (Generator, InOutWorkers)
	 * 5. Freezes the context to prevent modifications during simulation
	 *
	 * @param editingContext The editing context with static network configuration
	 * @return New simulation context ready for simulation execution
	 * @throws ContextCreationException If editingContext is invalid or transformation fails
	 *
	 * @see ContextTransformer.createSimulationContext
	 * @see DefaultSimulationContext.fromEditingContext
	 */
	override fun createContext(editingContext: EditingContext): SimulationContext {
		logger.debug {
			"Creating SimulationContext from EditingContext via ContextTransformer: " +
				"grid ${editingContext.getRailWayNetGrid().getCols()}x${editingContext.getRailWayNetGrid().getRows()}"
		}

		val simulationContext =
			ContextTransformer.createSimulationContext(
				editingContext,
				processFactory
			)

		logger.debug {
			"Successfully created SimulationContext: " +
				"${simulationContext.getInOuts().size} InOuts, " +
				"${simulationContext.getGraph().size()} track blocks"
		}

		return simulationContext
	}

	/**
	 * Save SimulationContext to file.
	 *
	 * Delegates to EditingContextFactory for XML serialization.
	 *
	 * Note: Saves the static network structure only (not dynamic simulation state).
	 * To restore simulation state, use Goal 5 (Save/Restore State) from LONG_TERM_GOALS.md.
	 *
	 * @param context The context to save (SimulationContext or EditingContext)
	 * @param file Destination file
	 * @return true if save succeeded
	 *
	 * @see EditingContextFactory.saveContext
	 */
	override fun saveContext(
		context: Context<*, *>,
		file: File
	): Boolean {
		logger.debug { "Saving context to file: ${file.absolutePath}" }
		return editingFactory.saveContext(context, file)
	}

	/**
	 * Save SimulationContext to output stream.
	 *
	 * Delegates to EditingContextFactory for XML serialization.
	 *
	 * Note: Saves the static network structure only (not dynamic simulation state).
	 * To restore simulation state, use Goal 5 (Save/Restore State) from LONG_TERM_GOALS.md.
	 *
	 * @param context The context to save (SimulationContext or EditingContext)
	 * @param stream Destination stream
	 * @return true if save succeeded
	 *
	 * @see EditingContextFactory.saveContext
	 */
	override fun saveContext(
		context: Context<*, *>,
		stream: OutputStream
	): Boolean {
		logger.debug { "Saving context to output stream" }
		return editingFactory.saveContext(context, stream)
	}

	/**
	 * Create an empty simulation context (for testing).
	 * Converts an empty editing context to a simulation context.
	 */
	override fun createEmptyContext(): SimulationContext {
		val editingContext = editingFactory.createEmptyContext()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}
}
