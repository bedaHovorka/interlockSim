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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform.getKoin

private val logger = KotlinLogging.logger {}

/**
 * Main class, for run program
 *
 * usage: java cz.vutbr.fit.interlockSim.Main (sim|edit) [file]
 *        java cz.vutbr.fit.interlockSim.Main example name
 *
 * or: ant start
 *
 */
class Main {
	private var frame: Frame? = null

	// Lazy injection of EditingContextFactory from Koin DI container
	// Using lazy to defer Koin access until after startKoin() is called in main()
	private val editingContextFactory: EditingContextFactory by lazy {
		getKoin().get<EditingContextFactory>()
	}

	fun loadGui(args: Array<String>) {
		frame = Frame()
		try {
			frame!!.setContext(createContext(args))
			frame!!.setVisible(true)
		} catch (e: ContextCreationException) {
			logger.error(e) { "Context creation failed" }
		}
	}

	fun createContext(args: Array<String>): Context {
		if (args.size > 1) {
			val userDir = File(".").canonicalFile
			val file = File(args[1]).canonicalFile
			if (!file.startsWith(userDir)) {
				val errorMsg = "Refusing to open file outside user directory. Requested: '${file.path}', allowed base: '${userDir.path}'"
				logger.error { errorMsg }
				throw ContextCreationException(errorMsg)
			}
			return editingContextFactory.createContext(file)
		}
		return editingContextFactory.createEmptyContext()
	}

	fun loadSim(args: Array<String>) {
		try {
			val context = createContext(args) as SimulationContext
			context.addReportTypes(*ReportType.values())
			context.run()
		} catch (e: ContextCreationException) {
			logger.error(e) { "Context creation failed" }
		} catch (e: EmptyContextException) {
			logger.error(e) { "User hasn't specified valid file" }
		} catch (e: SimulationException) {
			logger.error(e) { "Simulation failed" }
		}
	}

	fun runExample(args: Array<String>) {
		if (args.size == 1) {
			printListOfExamples()
			return
		}
		val name = args[1]
		try {
			val method = Main::class.java.getMethod(name, SimulationContextFactory::class.java, Array<String>::class.java)
			if (!method.isAnnotationPresent(Example::class.java)) throw NoSuchMethodException("Method $name isn't annotated")
			// Get injected SimulationContextFactory from Koin DI container
			val simulationContextFactory = getKoin().get<SimulationContextFactory>()
			val context = method.invoke(this, simulationContextFactory, args) as SimulationContext
			context.run()
		} catch (e: NoSuchMethodException) {
			logger.error(e) { "Example with name $name not exist" }
			printListOfExamples()
		} catch (e: SimulationException) {
			logger.error(e) { "Example simulation failed" }
		} catch (e: EmptyContextException) {
			logger.error(e) { "Example simulation could not started - empty context" }
		} catch (e: InvocationTargetException) {
			val cause = e.cause
			logger.error(cause) { "Example initialization failed" }
		} catch (e: Exception) {
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
		logger.warn {
			if (list.size > 0) {
				"You must specify valid name of example\nList of examples: $list"
			} else {
				"No Examples in program"
			}
		}
	}

	/**
	 * Get context factory from Koin DI container.
	 * This preserves backward compatibility while using dependency injection.
	 *
	 * @return current Context Factory from Koin container
	 */
	val contextFactory: ContextFactory
		get() = editingContextFactory
}

const val PROGRAM_NAME = "InterlockSim"

/**
 * Version
 */
const val PROGRAM_VERSION = "0.1-bachelor"

/**
 * Program title
 */
const val PROGRAM_FULL_NAME = "$PROGRAM_NAME $PROGRAM_VERSION"

/**
 * @param args
 */
fun main(args: Array<String>) {
	// Initialize Koin dependency injection framework with interlockSim module
	startKoin {
		modules(interlockSimModule)
	}

	// Add shutdown hook to clean up Koin when JVM exits
	Runtime.getRuntime().addShutdownHook(Thread {
		try {
			stopKoin()
		} catch (e: Exception) {
			logger.debug(e) { "Koin shutdown failed" }
		}
	})

	val main = getKoin().get<Main>()
	when {
		args.isNotEmpty() && args[0] == "sim" -> main.loadSim(args)
		args.isNotEmpty() && args[0] == "example" -> main.runExample(args)
		args.isNotEmpty() && args[0] == "edit" -> main.loadGui(args)
		else -> logger.error {
			"usage: <java> cz.vutbr.fit.interlockSim.Main (sim|edit) [file]\n\t\t example [name]"
		}
	}
}
