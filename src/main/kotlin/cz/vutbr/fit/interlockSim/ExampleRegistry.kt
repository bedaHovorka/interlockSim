/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.util.Util
import org.koin.mp.KoinPlatform.getKoin
import java.io.InputStream

/**
 * Registry of available simulation examples.
 *
 * This class manages the registration and creation of built-in simulation examples.
 * It replaces the legacy reflection-based @Example annotation system with a type-safe
 * map registry approach.
 *
 * Example factory functions take a SimulationContextFactory and command line arguments,
 * and return a configured SimulationContext ready to run.
 *
 * **Example Types:**
 * - **console examples**: Run simulation in console mode with text output (no GUI)
 * - **guiExamples**: Run simulation with animated GUI visualization
 *
 * @since January 2026 (refactored from reflection-based system)
 */
class ExampleRegistry {
	private val myResourceBundle: MyResourceBundle by getKoin().inject()

	/**
	 * Registry of console-based examples. Maps example name to factory function.
	 */
	val examples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopExample
		)

	/**
	 * Registry of GUI-based examples. Maps example name to factory function.
	 *
	 * GUI examples create SimulationContext instances that are designed to be displayed
	 * in the animated Frame with AnimationController, EventTimelinePanel, and ControlPanel.
	 */
	val guiExamples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopGuiExample
		)

	/**
	 * Returns a sorted list of available console example names.
	 */
	fun getAvailableExamples(): List<String> = examples.keys.sorted()

	/**
	 * Returns a sorted list of available GUI example names.
	 */
	fun getAvailableGuiExamples(): List<String> = guiExamples.keys.sorted()

	/**
	 * Creates a shunting loop simulation example for console mode.
	 *
	 * This example demonstrates a train performing shunting operations on the
	 * shunting loop railway network configuration.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 */
	private fun createShuntingLoopExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val stream: InputStream =
			myResourceBundle.getFile("vyhybna.xml")
				?: throw ContextCreationException("Resource file vyhybna.xml not found")

		// Use .use {} to ensure stream is properly closed
		return stream.use {
			val context = Util.assertInstanceOf(DefaultSimulationContext::class.java, factory.createContext(it))
			val time = args[2].toLong()
			// Initialize dynamic wrapper map by calling getInOuts()
			context.getInOuts()
			context.setMainProcess(ShuntingLoop(context, time))
			context
		}
	}

	/**
	 * Creates a shunting loop simulation example for GUI mode (Issue #206).
	 *
	 * This example is identical to [createShuntingLoopExample] but designed for display
	 * in the animated Frame with AnimationController, EventTimelinePanel, and ControlPanel.
	 *
	 * **Threading Model:**
	 * The simulation runs on a background thread (jDisco simulation thread), while the
	 * GUI updates occur on the EDT. AnimationController handles the thread marshaling.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run in GUI
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 */
	private fun createShuntingLoopGuiExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val stream: InputStream =
			myResourceBundle.getFile("vyhybna.xml")
				?: throw ContextCreationException("Resource file vyhybna.xml not found")

		// Use .use {} to ensure stream is properly closed
		return stream.use {
			val context = Util.assertInstanceOf(DefaultSimulationContext::class.java, factory.createContext(it))
			val time = args[2].toLong()
			// Initialize dynamic wrapper map by calling getInOuts()
			context.getInOuts()
			context.setMainProcess(ShuntingLoop(context, time))
			context
		}
	}
}
