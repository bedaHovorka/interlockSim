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

import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.ContextFactory
import cz.vutbr.fit.interlockSim.context.DefaultContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.EmptyContextException
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.inject
import org.koin.java.KoinJavaComponent.get

/**
 * Main class, for run program
 *
 * usage: java -ea cz.vutbr.fit.interlockSim.Main (sim|edit) [file]
 *        java -ea cz.vutbr.fit.interlockSim.Main example name
 *
 * or: ant start
 *
 * !!! Please check JAVA_HOME for JDK/JRE 1.6 !!!
 */
class Main private constructor() {
	private var frame: Frame? = null

	// Inject EditingContextFactory from Koin DI container
	private val editingContextFactory: EditingContextFactory by inject(EditingContextFactory::class.java)

	private fun loadGui(args: Array<String>) {
		frame = Frame()
		frame!!.setContext(createContext(args))
		frame!!.setVisible(true)
	}

	private fun createContext(args: Array<String>): Context {
		if (args.size > 1) {
			try {
				return editingContextFactory.createContext(File(args[1]))
			} catch (e: ContextCreationException) {
				e.printStackTrace()
				System.exit(1)
			}
		}
		return editingContextFactory.createEmptyContext()
	}

	private fun loadSim(args: Array<String>) {
		val context = createContext(args) as SimulationContext
		context.addReportTypes(*ReportType.values())
		try {
			context.run()
		} catch (e: EmptyContextException) {
			out.println("You dont specify valid file")
		} catch (e: SimulationException) {
			out.println("Simulation failed")
			e.printStackTrace()
		}
	}

	private fun runExample(args: Array<String>) {
		if (args.size == 1) {
			printListOfExamples()
			return
		}
		val name = args[1]
		try {
			val method = Main::class.java.getMethod(name, SimulationContextFactory::class.java, Array<String>::class.java)
			if (!method.isAnnotationPresent(Example::class.java)) throw NoSuchMethodException("Method $name isn't annotated")
			// Get injected SimulationContextFactory from Koin DI container
			val factory = get<SimulationContextFactory>(SimulationContextFactory::class.java)
			val context = method.invoke(this, factory, args) as SimulationContext
			context.run()
		} catch (e: NoSuchMethodException) {
			out.println("Example with name $name not exist")
			printListOfExamples()
		} catch (e: SimulationException) {
			out.println("Example simulation failed")
		} catch (e: EmptyContextException) {
			out.println("Example simulation could not started - empty context")
		} catch (e: InvocationTargetException) {
			val cause = e.cause
			if (cause is ContextCreationException) {
				out.println(cause.message)
				return
			} else if (cause is NumberFormatException) {
				out.println(cause.message + " cannot convert to number")
				return
			}
			logger.error(cause) { "Example initialization failed" }
		} catch (e: Exception) {
			out.println("Example inilialization failed")
			logger.error(e) { "Example initialization failed" }
		}
	}

	/**
	 * @param factory
	 * @return loaded and initialed context
	 * @throws ContextCreationException
	 */
	@Example
	fun shuntingLoop(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not inserted")
		}
		val stream: InputStream =
			MyResourceBoundle.getInstance().getFile("vyhybna.xml")
				?: throw ContextCreationException("Resource file vyhybna.xml not found")
		val context = Util.assertInstanceOf(DefaultContext::class.java, factory.createContext(stream))
		val time = args[2].toLong()
		context.setMainProcess(ShuntingLoop(context, time))
		return context
	}

	private fun printListOfExamples() {
		val list = ArrayList<String>()
		for (m in Main::class.java.declaredMethods) {
			if (Modifier.isPublic(m.modifiers) && m.isAnnotationPresent(Example::class.java)) {
				list.add(m.name)
			}
		}
		list.sortWith(String.CASE_INSENSITIVE_ORDER)
		out.println(
			if (list.size > 0) {
				"You must specify valid name of example\nList of examples: $list"
			} else {
				"No Examples in program"
			}
		)
	}

	/**
	 * Get context factory from Koin DI container.
	 * This preserves backward compatibility while using dependency injection.
	 *
	 * @return current Context Factory from Koin container
	 */
	fun getContextFactory(): ContextFactory = editingContextFactory

	companion object {
		/**
		 * Program name
		 */
		const val PROGRAM_NAME = "InterlockSim"

		/**
		 * Version
		 */
		const val PROGRAM_VERSION = "0.1-bachelor"

		/**
		 * Program title
		 */
		const val PROGRAM_FULL_NAME = "$PROGRAM_NAME $PROGRAM_VERSION"

		private val logger = KotlinLogging.logger {}

		private val instance = Main()

		// kam poustet hlasky
		private val out: PrintStream = System.err

		/**
		 * @param args
		 */
		@JvmStatic
		fun main(args: Array<String>) {
			// Initialize Koin dependency injection framework with interlockSim module
			startKoin {
				modules(interlockSimModule)
			}

			try {
				if (isArgs(args, "sim")) {
					instance.loadSim(args)
				} else if (isArgs(args, "example")) {
					instance.runExample(args)
				} else if (isArgs(args, "edit")) {
					instance.loadGui(args)
				} else {
					out.println(
						"usage: <java> cz.vutbr.fit.interlockSim.Main (sim|edit) [file]\n" +
							"\t\t example [name]"
					)
				}
			} finally {
				// Clean up Koin context on exit
				stopKoin()
			}
		}

		private fun isArgs(
			args: Array<String>,
			firstArg: String
		): Boolean = args.isNotEmpty() && args[0] == firstArg

		/**
		 * @return Main singleton instance
		 */
		@JvmStatic
		fun getInstance(): Main { // CAUTION pouzivat jen v nejnutnejsich pripadech
			return instance
		}
	}
}
