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
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.sweep.AiSweepDriver
import cz.vutbr.fit.interlockSim.dispatcher.sweep.SweepGrid
import cz.vutbr.fit.interlockSim.dispatcher.sweep.SweepGridException
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.sim.TextReporter
import cz.vutbr.fit.interlockSim.sim.Verbosity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import kotlin.system.exitProcess

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

	/**
	 * Runs a console example and reports whether it actually finished.
	 *
	 * ## Why this returns an outcome (Issue #847 round 3, PR #891 defect C)
	 *
	 * Every failure below is caught and logged, and kDisco's `Simulation.run()` returns `true`
	 * whether it reached the end time or its event queue simply drained. Nothing else on this path
	 * distinguishes a healthy run from a deadlocked one:
	 * `ThrottlingSimulationController.requestPause()` is a no-op, so a CRITICAL `CollisionWarning`
	 * has no observable effect either. Round 2's third verification run ended at simTime 72.0 s of a
	 * requested 600 s and still exited 0 — and #847's unattended sweep would have counted it as a
	 * valid data point.
	 *
	 * [RunCompletionCheck] compares reached against requested simulated time, and `main` turns the
	 * result into a process exit code. Reached time is tracked with a listener of this module's own
	 * rather than read from [TextReporter], whose `lastSimTime` is private and lives in `core/`.
	 *
	 * @return [RunOutcome.COMPLETED] if simulated time reached the requested end time;
	 *   [RunOutcome.TERMINATED_EARLY] if a run started and stopped short of it;
	 *   [RunOutcome.NOT_STARTED] if no simulation ran at all (bad arguments, unknown example, or an
	 *   exception). The last is kept distinct because it is not a deadlock and must not be counted
	 *   as one, and because only [RunOutcome.TERMINATED_EARLY] forces a non-zero process exit.
	 */
	fun runExample(args: Array<String>): RunOutcome {
		if (args.size == 1) {
			logger.warn { "Available examples: ${exampleRegistry.getAvailableExamples()}\nUsage: example <name> <endTime>" }
			return RunOutcome.NOT_STARTED
		}

		val name = args[1]
		val exampleFactory = exampleRegistry.examples[name]

		if (exampleFactory == null) {
			logger.error { "Unknown example: $name" }
			logger.warn { "Available examples: ${exampleRegistry.getAvailableExamples()}" }
			return RunOutcome.NOT_STARTED
		}

		// Same argument the example factories read for their end time. A missing or unparseable
		// value leaves the factory to reject it; there is then no run to classify.
		val requestedEndTime = args.getOrNull(2)?.toDoubleOrNull()

		return try {
			val simulationContextFactory = getKoin().get<SimulationContextFactory>()
			val context = exampleFactory(simulationContextFactory, args)
			val reporter = TextReporter(Verbosity.DEFAULT)
			context.addPropertyChangeListener(reporter)
			val simTimeTracker = SimulationTimeTracker()
			context.addPropertyChangeListener(simTimeTracker)
			// Issue #847 cleanup pass: an example may declare its own SimulationController in
			// scope (e.g. createShuntingLoopAIExample's ThrottlingSimulationController, needed
			// to pace the async LLM planner). Without retrieving it here, run() would silently
			// default to NoOpSimulationController and the declared controller would never reach
			// the kDisco kernel loop it was built to pace.
			val controller = context.scope.getOrNull<SimulationController>() ?: NoOpSimulationController
			var outcome = RunOutcome.NOT_STARTED
			context.use {
				it.run(controller)
				reporter.printSummary()
				// A dispatcher that gave up (AgentDriverLoop.stoppedByFailures) leaves the kDisco
				// kernel ticking on to the horizon with nobody dispatching, so sim-time alone
				// would report COMPLETED and the aggregator would count a dead run as a passing
				// data point. Read inside `use` -- the scope is gone once the context closes.
				val dispatcherGaveUp = it.scope.getOrNull<AgentDriverLoop>()?.stoppedByFailures == true
				val reachedSimTime = resolveReachedSimTime(simTimeTracker.lastSimTime, it.lastRunEndTime)
				outcome = classifyRun(reachedSimTime, requestedEndTime, dispatcherGaveUp)
				// Issue #847 rounds 3 and 4: report what the dispatcher actually did — routes the
				// orphan sweeper reclaimed, the planner's final cycle counts, and the
				// control-tick-to-decision chain. Read inside `use`: the scope is gone once the
				// context closes. Examples that wire no dispatcher agent report nothing.
				//
				// Round 4 (R4-2): logFinalSummary() previously had exactly one caller, the GUI
				// Frame, so a headless run emitted only the modulo-10 periodic lines — two per run
				// at the observed cycle counts, and none at the end. #847's sweep is headless by
				// definition, so every per-run number it needs has to be produced here.
				DispatcherRunSummaries.log(it.scope)
				// Round 4 (R4-5): persist the run as JSON so SP2c.23's aggregator (#846) has a
				// producer. SP2c.22 (#845) deferred the headless finish() to "the sweep driver",
				// but that driver is #847 — the task blocked on this pipeline producing anything —
				// so build/reports/dispatcher-runs/ was never created by any run at all.
				//
				// TERMINATED_EARLY maps to RunEndCause.TERMINATED_EARLY: a run that stopped short
				// of its requested horizon is not a natural completion, and #846's pass criterion is
				// `completedNaturally && !terminalFallbackEngaged && c7Clean`.
				//
				// Issue #930: finishAndPersist may downgrade a NATURAL_COMPLETION to STARVED when the
				// railway achieved nothing. The process exit code is deliberately left alone — it is
				// already decided above from reached-versus-requested sim time, and a starved run did
				// reach its horizon. The sweep reads the verdict from the run JSON, not from `$?`.
				DispatcherRunSummaries.finishAndPersist(it.scope, outcome.toRunEndCause())
			} // context closed after simulation
			outcome
		} catch (e: ContextCreationException) {
			logger.error(e) { "Example context creation failed" }
			RunOutcome.NOT_STARTED
		} catch (e: SimulationException) {
			logger.error(e) { "Example simulation failed" }
			RunOutcome.NOT_STARTED
		} catch (e: EmptyContextException) {
			logger.error(e) { "Example simulation could not be started - empty context" }
			RunOutcome.NOT_STARTED
		} catch (e: Exception) {
			logger.error(e) { "Example initialization failed" }
			RunOutcome.NOT_STARTED
		}
	}

	/**
	 * Runs an unattended parameter sweep over the headless examples (SP2c.24, Issue #847).
	 *
	 * The mode itself is thin on purpose: it parses flags, loads the grid and hands both to
	 * [AiSweepDriver], which lives in `:dispatcher-agent` next to the run recorder and the
	 * aggregator it drives. The child process's main class is passed in from here rather than
	 * hardcoded there, so `:dispatcher-agent` keeps knowing nothing about `:desktop-ui`.
	 *
	 * ## Exit status
	 *
	 * A sweep whose runs *fail* is a successful measurement of a failing arm — that is precisely
	 * what A4's amended "measured success rate" asks for — so failing runs do not fail the
	 * process. Only a sweep that could not be *performed* (unreadable or invalid grid, bad flags)
	 * exits non-zero. The verdict lives in `report.md`, not in `$?`.
	 *
	 * @return `true` if the sweep ran, `false` if the command line or grid file was rejected.
	 */
	fun runAiSweep(args: Array<String>): Boolean {
		val command =
			try {
				AiSweepCommand.parse(args)
			} catch (e: AiSweepUsageException) {
				logger.error { "${e.message}\n${AiSweepCommand.USAGE}" }
				return false
			}

		val grid =
			try {
				SweepGrid.load(command.gridFile).let { loaded ->
					loaded.copy(
						repeat = command.repeatOverride ?: loaded.repeat,
						perRunTimeoutSeconds = command.timeoutOverride ?: loaded.perRunTimeoutSeconds
					)
				}
			} catch (e: SweepGridException) {
				logger.error(e) { "Cannot start sweep: ${e.message}" }
				return false
			} catch (e: IllegalArgumentException) {
				logger.error(e) { "Cannot start sweep: ${e.message}" }
				return false
			}

		AiSweepDriver(store = DefaultRunSnapshotStore(command.outputRoot)).run(
			grid = grid,
			outputRoot = command.outputRoot,
			mainClass = CHILD_MAIN_CLASS,
			dryRun = command.dryRun
		)
		return true
	}

	/**
	 * Resolves the simulated time to feed into [classifyRun], combining the two independent time
	 * signals available once a headless run finishes (Issue #929).
	 *
	 * [SimulationTimeTracker.lastSimTime] is derived purely from the report-event stream
	 * (TRAIN/NODE events). Queued-but-never-admitted trains are event-less by design, so once the
	 * last moving train finishes, the tracker's value freezes below the requested end time even
	 * though the kDisco kernel's own clock ([SimulationContext.lastRunEndTime]) kept advancing
	 * silently to the real end time. Taking the maximum of the two prevents a short-horizon run
	 * that actually completed from being misclassified as [RunOutcome.TERMINATED_EARLY].
	 *
	 * @param trackerSimTime [SimulationTimeTracker.lastSimTime] after the run finished.
	 * @param contextEndTime [SimulationContext.lastRunEndTime] after the run finished, or `null`
	 *   if the run never actually invoked the kDisco kernel.
	 */
	internal fun resolveReachedSimTime(
		trackerSimTime: Double,
		contextEndTime: Double?
	): Double = maxOf(trackerSimTime, contextEndTime ?: trackerSimTime)

	/**
	 * Classifies a finished headless run into the [RunOutcome] that becomes the process exit code.
	 *
	 * @param reachedSimTime Simulated time the kDisco kernel actually reached.
	 * @param requestedEndTime Horizon asked for on the command line, or `null` when none was given.
	 * @param dispatcherStoppedByFailures `true` when the agent driver abandoned the run after
	 *   persistent cycle failures. This outranks sim-time: the kernel keeps ticking to the horizon
	 *   with nothing dispatching, so the run reaches its end time while having measured nothing.
	 *   Reporting that as [RunOutcome.COMPLETED] would feed the sweep aggregator a passing data
	 *   point for a dispatcher that died -- exactly the silent death #847 exists to surface.
	 */
	internal fun classifyRun(
		reachedSimTime: Double,
		requestedEndTime: Double?,
		dispatcherStoppedByFailures: Boolean = false
	): RunOutcome {
		if (dispatcherStoppedByFailures) {
			logger.error {
				"Dispatcher driver stopped after persistent cycle failures; simulated time reached " +
					"${reachedSimTime}s of a requested ${requestedEndTime}s, but nothing was " +
					"dispatching. Exiting ${RunOutcome.TERMINATED_EARLY.exitCode}."
			}
			return RunOutcome.TERMINATED_EARLY
		}
		if (requestedEndTime == null || requestedEndTime <= 0.0) {
			// Nothing to compare against; the factory would already have rejected a bad argument.
			return RunOutcome.COMPLETED
		}
		val outcome = RunCompletionCheck.evaluate(reachedSimTime, requestedEndTime)
		if (outcome == RunOutcome.TERMINATED_EARLY) {
			logger.error {
				"Simulation terminated early: reached ${reachedSimTime}s of a requested " +
					"${requestedEndTime}s. The kDisco event queue drained before the end time, " +
					"which normally means the network deadlocked. Exiting ${outcome.exitCode}."
			}
		}
		return outcome
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
 * Main class the sweep driver launches each child run with (Issue #847).
 *
 * A string rather than a class reference because it is handed to `:dispatcher-agent`, which must
 * not depend on `:desktop-ui`; Kotlin compiles top-level `main` in `Main.kt` into `MainKt`.
 */
private const val CHILD_MAIN_CLASS = "cz.vutbr.fit.interlockSim.MainKt"

/** Exit status when `aiSweep` was invoked incorrectly or its grid file could not be used. */
private const val SWEEP_USAGE_EXIT_CODE = 2

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

private val VALID_MODES = setOf("sim", "simgui", "edit", "example", "exampleGui", "aiSweep")

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
		// Issue #847 round 3 (PR #891 defect C): a headless example that stopped short of its
		// requested end time — the signature of a deadlock — must not exit 0. #847's unattended
		// sweep decides from the exit status alone whether a run is a valid data point.
		//
		// TERMINATED_EARLY *only*, deliberately narrow. A completed run returns normally exactly as
		// it did before; so does every usage-error path (NOT_STARTED), which already prints its own
		// diagnosis and whose exit status nothing depends on. Exiting on those too would change the
		// behaviour of `main(arrayOf("example"))` — which MainArgumentParsingTest calls in-process —
		// from "print usage" to "kill the JVM", and would tear down still-live non-daemon threads
		// that currently keep a healthy process up.
		//
		// On a deadlocked run that teardown is exactly what is wanted: the agent-driver thread would
		// otherwise hang the process indefinitely. exitProcess still runs the Koin shutdown hook
		// registered above — it delegates to System.exit and is not a Runtime.halt.
		//
		// Headless `example` mode only: exampleGui, simgui, sim and edit are untouched.
		// Note this makes `./gradlew :desktop-ui:runExample` (a JavaExec task) fail on such a run.
		"example" -> {
			val outcome = main.runExample(args)
			if (outcome == RunOutcome.TERMINATED_EARLY) {
				exitProcess(outcome.exitCode)
			}
		}
		"exampleGui" -> main.runExampleGui(args)
		// Issue #847 (SP2c.24): unattended parameter sweep. Non-zero exit means the sweep could
		// not be performed at all — never that its runs failed, which is a measurement result.
		"aiSweep" ->
			if (!main.runAiSweep(args)) {
				exitProcess(SWEEP_USAGE_EXIT_CODE)
			}
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
