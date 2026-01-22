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
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.EmptyContextException
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.gui.Frame
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

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
	private val editingContextFactory: EditingContextFactory by getKoin().inject()
	private val exampleRegistry: ExampleRegistry by getKoin().inject()
	private val simulationContextFactory: SimulationContextFactory by getKoin().inject()
	private val frame: Frame by lazy { getKoin().get<Frame>() }

	fun loadGui(args: Array<String>) {
		try {
			val context = createContext(args)
			javax.swing.SwingUtilities.invokeLater {
				frame.setContext(context)
				frame.setVisible(true)
			}
		} catch (e: ContextCreationException) {
			logger.error(e) { "Context creation failed" }
		}
	}

	fun createContext(args: Array<String>): Context<*, *> {
		if (args.size > 1) {
			val userDir = File(".").canonicalFile
			val file = File(args[1]).canonicalFile
			if (!file.startsWith(userDir)) {
				val errorMsg =
					"Refusing to open file outside user directory. " +
						"Requested: '${file.path}', allowed base: '${userDir.path}'"
				logger.error { errorMsg }
				throw ContextCreationException(errorMsg)
			}
			return editingContextFactory.createContext(file)
		}
		return editingContextFactory.createEmptyContext()
	}

	fun loadSim(args: Array<String>) {
		try {
			// Get context and ensure it's a SimulationContext
			val rawContext = createContext(args)
			val context: SimulationContext =
				when (rawContext) {
					is SimulationContext -> rawContext
					is EditingContext -> simulationContextFactory.createContext(rawContext)
					else -> throw ContextCreationException(
						"Unexpected context type: ${rawContext::class.java.name}"
					)
				}
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
			logger.warn { "Available examples: ${exampleRegistry.getAvailableExamples()}\nUsage: example <name> <endTime>" }
			return
		}

		val name = args[1]
		val exampleFactory = exampleRegistry.examples[name]

		if (exampleFactory == null) {
			logger.error { "Unknown example: $name" }
			logger.warn { "Available examples: ${exampleRegistry.getAvailableExamples()}" }
			return
		}

		try {
			val simulationContextFactory = getKoin().get<SimulationContextFactory>()
			val context = exampleFactory(simulationContextFactory, args)
			context.run()
		} catch (e: ContextCreationException) {
			logger.error(e) { "Example context creation failed" }
		} catch (e: SimulationException) {
			logger.error(e) { "Example simulation failed" }
		} catch (e: EmptyContextException) {
			logger.error(e) { "Example simulation could not be started - empty context" }
		} catch (e: Exception) {
			logger.error(e) { "Example initialization failed" }
		}
	}
}

// Application metadata constants moved to AppMetadata.kt
// PROGRAM_NAME, PROGRAM_VERSION, and PROGRAM_FULL_NAME are now defined in AppMetadata.kt

/**
 * @param args
 */
fun main(args: Array<String>) {
	// Initialize Koin dependency injection framework with interlockSim module
	startKoin {
		modules(interlockSimModule)
	}

	// Load GUI module (includes Main coordinator and Frame)
	// Note: Main is always needed for all modes (sim/edit/example)
	// Frame is only created lazily when actually needed (edit mode)
	getKoin().loadModules(listOf(guiModule))

	// Add shutdown hook to clean up Koin when JVM exits
	Runtime.getRuntime().addShutdownHook(
		Thread {
			try {
				stopKoin()
			} catch (e: Exception) {
				logger.debug(e) { "Koin shutdown failed" }
			}
		}
	)

	val main = getKoin().get<Main>()
	when {
		args.isNotEmpty() && args[0] == "sim" -> main.loadSim(args)
		args.isNotEmpty() && args[0] == "example" -> main.runExample(args)
		args.isNotEmpty() && args[0] == "edit" -> main.loadGui(args)
		else ->
			logger.error {
				"usage: <java> cz.vutbr.fit.interlockSim.Main (sim|edit) [file]\n\t\t example [name]"
			}
	}
}
