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
import cz.vutbr.fit.interlockSim.context.DefaultContext
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
 * @since January 2026 (refactored from reflection-based system)
 */
class ExampleRegistry {
	private val myResourceBundle: MyResourceBundle by getKoin().inject()

	/**
	 * Registry of available examples. Maps example name to factory function.
	 */
	val examples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopExample
		)

	/**
	 * Returns a sorted list of available example names.
	 */
	fun getAvailableExamples(): List<String> = examples.keys.sorted()

	/**
	 * Creates a shunting loop simulation example.
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
			val context = Util.assertInstanceOf(DefaultContext::class.java, factory.createContext(it))
			val time = args[2].toLong()
			context.setMainProcess(ShuntingLoop(context, time))
			context
		}
	}
}
