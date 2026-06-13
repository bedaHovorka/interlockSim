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
import cz.vutbr.fit.interlockSim.context.EmptyContextException
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.sim.TextReporter
import cz.vutbr.fit.interlockSim.sim.Verbosity
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
	private val editingContextFactory: JvmEditingContextFactory by getKoin().inject()
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
			logger.error(e) { MSG_CONTEXT_CREATION_FAILED }
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
			rawContext.use { raw ->
				val context: SimulationContext =
					when (raw) {
						is SimulationContext -> raw
						is EditingContext -> simulationContextFactory.createContext(raw)
						else -> throw ContextCreationException(
							"Unexpected context type: ${raw::class.java.name}"
						)
					}
				// Only use context.use if it's a different instance from raw
				// (to avoid double-close when rawContext is already a SimulationContext)
				if (context === raw) {
					context.addReportTypes(*ReportType.values())
					context.run()
				} else {
					context.use {
						it.addReportTypes(*ReportType.values())
						it.run()
					} // context closed
				}
			} // rawContext closed
		} catch (e: ContextCreationException) {
			logger.error(e) { MSG_CONTEXT_CREATION_FAILED }
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
			val reporter = TextReporter(Verbosity.DEFAULT)
			context.addPropertyChangeListener(reporter)
			context.use {
				it.run()
				reporter.printSummary()
			} // context closed after simulation
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

	/**
	 * Run a simulation from an XML file with the animated GUI (Issue #189).
	 *
	 * Loads the railway network from [args][1], converts it to a [SimulationContext],
	 * then delegates to [showContextInGui] — the same entry point used by [runExampleGui].
	 * This ensures both XML-based and example-based animated simulations share identical
	 * GUI lifecycle handling (SimulationController, Stop button, EventTimelinePanel).
	 *
	 * **Command Usage:**
	 * ```
	 * java -jar interlockSim.jar simgui [xmlFile]
	 * ./gradlew runExampleGui -PxmlFile=vyhybna.xml
	 * ```
	 *
	 * @param args Command line arguments (expects optional XML file path as args[1])
	 */
	fun loadSimWithGui(args: Array<String>) {
		try {
			val rawContext = createContext(args)
			val context: SimulationContext =
				when (rawContext) {
					// Defensive: createContext() currently always returns EditingContext via
					// JvmEditingContextFactory, but if a future factory returns SimulationContext
					// directly (mirroring the defensiveness in loadSim()), handle it gracefully.
					is SimulationContext -> rawContext
					is EditingContext ->
						rawContext.use { editCtx ->
							simulationContextFactory.createContext(editCtx)
						}
					else -> {
						rawContext.close()
						throw ContextCreationException(
							"Unexpected context type: ${rawContext::class.java.name}"
						)
					}
				}

			context.addReportTypes(*ReportType.values())

			val sourceFile = if (args.size > 1) File(args[1]).canonicalFile else null
			showContextInGui(context, sourceFile)
		} catch (e: ContextCreationException) {
			logger.error(e) { MSG_CONTEXT_CREATION_FAILED }
		} catch (e: EmptyContextException) {
			logger.error(e) { "Simulation with GUI could not be started - empty context" }
		} catch (e: Exception) {
			logger.error(e) { "Simulation with GUI initialization failed" }
		}
	}

	/**
	 * Run a GUI-based animated example (Issue #206).
	 *
	 * This method creates a simulation example and displays it in the animated Frame
	 * with AnimationController, EventTimelinePanel, and ControlPanel.
	 * Delegates to [showContextInGui] — the same entry point used by [loadSimWithGui] —
	 * so both paths share identical GUI lifecycle handling via [SimulationController].
	 *
	 * **Command Usage:**
	 * ```
	 * java -jar interlockSim.jar exampleGui <name> <endTime>
	 * ./gradlew runExampleGui -PexampleName=shuntingLoop -PendTime=60
	 * ./gradlew runExampleGui -PxmlFile=vyhybna.xml
	 * ```
	 *
	 * @param args Command line arguments (expects example name as args[1], endTime as args[2])
	 */
	fun runExampleGui(args: Array<String>) {
		if (args.size == 1) {
			logger.warn {
				"Available GUI examples: ${exampleRegistry.getAvailableGuiExamples()}\n" +
					"Usage: exampleGui <name> <endTime>"
			}
			return
		}

		val name = args[1]
		val exampleFactory = exampleRegistry.guiExamples[name]

		if (exampleFactory == null) {
			logger.error { "Unknown GUI example: $name" }
			logger.warn { "Available GUI examples: ${exampleRegistry.getAvailableGuiExamples()}" }
			return
		}

		try {
			val simulationContextFactory = getKoin().get<SimulationContextFactory>()
			val context = exampleFactory(simulationContextFactory, args)

			context.addReportTypes(*ReportType.values())

			// Reuse the same GUI launch path as loadSimWithGui (no sourceFile for examples)
			showContextInGui(context)
		} catch (e: ContextCreationException) {
			logger.error(e) { "GUI example context creation failed" }
		} catch (e: EmptyContextException) {
			logger.error(e) { "GUI example simulation could not be started - empty context" }
		} catch (e: Exception) {
			logger.error(e) { "GUI example initialization failed" }
		}
	}

	/**
	 * Common GUI launch path shared by [loadSimWithGui] and [runExampleGui].
	 *
	 * Schedules on EDT: sets [context] on [Frame], optionally sets the window title from
	 * [sourceFile], shows the frame, and starts the simulation via [Frame.startSimulation]
	 * (which uses [SimulationController] — giving both paths a Stop button and lifecycle
	 * management without a raw background thread).
	 *
	 * The [SimulationContext] is intentionally not closed here; its lifetime is bound to
	 * the GUI window — [Frame.exitWithoutSaving] calls `System.exit(0)` on window close.
	 *
	 * @param context Simulation context to display; must already have report types added.
	 * @param sourceFile Optional XML file to show in the window title (null for built-in examples).
	 */
	private fun showContextInGui(
		context: SimulationContext,
		sourceFile: File? = null
	) {
		javax.swing.SwingUtilities.invokeLater {
			frame.setContext(context)
			// Do not update modificationTracker/currentFile in simulation mode:
			// File->Save uses that state to enter editing-only save flow.
			sourceFile?.let { frame.title = it.name }
			frame.isVisible = true
			frame.startSimulation()
		}
	}
}

// Application metadata constants moved to AppMetadata.kt
// PROGRAM_NAME, PROGRAM_VERSION, and PROGRAM_FULL_NAME are now defined in AppMetadata.kt

private const val MSG_CONTEXT_CREATION_FAILED = "Context creation failed"

/**
 * Parses the command mode from the argument list.
 *
 * Returns the first argument if it is a recognized mode string, or `null` if the
 * argument list is empty or the first argument is unrecognized. This pure function
 * can be tested without triggering any DI, context creation, or GUI code.
 *
 * @param args Command line arguments
 * @return One of "sim", "simgui", "edit", "example", "exampleGui", or `null`
 */
internal fun parseMode(args: Array<String>): String? {
	if (args.isEmpty()) return null
	return args[0].takeIf { it in VALID_MODES }
}

private val VALID_MODES = setOf("sim", "simgui", "edit", "example", "exampleGui")

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
	when (parseMode(args)) {
		"sim" -> main.loadSim(args)
		"simgui" -> main.loadSimWithGui(args)
		"example" -> main.runExample(args)
		"exampleGui" -> main.runExampleGui(args)
		"edit" -> main.loadGui(args)
		else ->
			logger.error {
				"usage: <java> cz.vutbr.fit.interlockSim.Main (sim|simgui|edit|example|exampleGui) [file]\n" +
					"\tsim [file]        - Run simulation from XML file\n" +
					"\tsimgui [file]     - Run simulation from XML file with animated GUI\n" +
					"\tedit [file]       - Launch graphical editor\n" +
					"\texample <name> <endTime> - Run console-based example\n" +
					"\texampleGui <name> <endTime> - Run GUI-based animated example"
			}
	}
}
