/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.TextReporter
import cz.vutbr.fit.interlockSim.sim.Verbosity
import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.cinterop.staticCFunction
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import platform.posix.SIGINT
import platform.posix.fprintf
import platform.posix.signal
import platform.posix.stderr
import kotlin.concurrent.AtomicInt
import kotlin.system.exitProcess

/** Unix convention exit code for processes terminated by SIGINT (128 + 2). */
internal const val SIGINT_EXIT_CODE = 130

/**
 * Holds the atomic SIGINT flag. Encapsulated in an object because the signal handler
 * (a C static function) cannot capture closures — it accesses this singleton directly.
 */
internal object SignalState {
	val INTERRUPTED = AtomicInt(0)
}

/** Returns `true` if a SIGINT signal has been received since program start. */
internal fun isInterrupted(): Boolean = SignalState.INTERRUPTED.value != 0

/**
 * Installs a POSIX signal handler that sets the [SignalState.INTERRUPTED] flag on SIGINT.
 *
 * **Limitation — implicit assumption on ctx.run() behavior:**
 * The handler only sets an atomic flag; it does not forcibly terminate the simulation.
 * Exit code 130 is only reachable if `ctx.run()` throws an exception while the flag is set
 * (e.g., a syscall returns EINTR and kDisco propagates it). If the simulation completes
 * normally despite SIGINT (short simulation, or kDisco/libc restarts interrupted syscalls),
 * the process exits 0 with complete results — this is acceptable behavior.
 *
 * True cooperative shutdown (periodic [isInterrupted] checks inside the simulation loop)
 * is future work and would require changes in kDisco or the simulation process itself.
 *
 * **Limitation — signal() vs sigaction() SA_RESTART behavior:**
 * POSIX `signal()` has implementation-defined SA_RESTART semantics. On Linux/glibc,
 * SA_RESTART is set by default, meaning blocking syscalls (read, sleep, etc.) are
 * automatically restarted after the handler returns rather than failing with EINTR.
 * This means `ctx.run()` will likely complete normally after SIGINT — the process then
 * exits 0. Using `sigaction()` with SA_RESTART cleared would give portable control over
 * syscall interruption, but is a non-trivial change deferred to a future iteration.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun installSignalHandler() {
	// See KDoc above for SA_RESTART implications of signal() vs sigaction().
	signal(SIGINT, staticCFunction<Int, Unit> { _ -> SignalState.INTERRUPTED.value = 1 })
}

private const val MIN_ARGS_COUNT = 3
private const val CMD_VERSION = "--version"
private const val CMD_HELP = "--help"
private const val CMD_HELP_SHORT = "-h"
private const val CMD_EXAMPLE = "example"
private const val CMD_SIM = "sim"
private const val CMD_VERBOSE = "--verbose"
private const val CMD_QUIET = "--quiet"
internal const val CMD_DEBUG = "--debug"
private const val VERSION_STRING = "fast-sim 1.0"

/**
 * Custom [FormattingAppender] that writes formatted log messages to stderr.
 * Used when [CMD_DEBUG] is active so that debug output does not pollute
 * the simulation results written to stdout.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object StderrAppender : FormattingAppender() {
	override fun logFormattedMessage(
		_loggingEvent: KLoggingEvent,
		formattedMessage: Any?
	) {
		fprintf(stderr, "%s\n", formattedMessage?.toString() ?: "null")
	}
}

/**
 * The process-wide default appender captured before any call to [configureLogging].
 * Restored when debug mode is disabled so that repeated calls to [configureLogging]
 * do not permanently leak [StderrAppender] into the global configuration.
 */
internal val DEFAULT_APPENDER = KotlinLoggingConfiguration.appender

/**
 * Configures the kotlin-logging global log level.
 *
 * - [debug] = `true`: enables [Level.DEBUG] output routed to stderr via [StderrAppender].
 * - [debug] = `false`: suppresses all log output ([Level.OFF]) and restores the [DEFAULT_APPENDER].
 */
internal fun configureLogging(debug: Boolean) {
	if (debug) {
		KotlinLoggingConfiguration.appender = StderrAppender
		KotlinLoggingConfiguration.logLevel = Level.DEBUG
	} else {
		KotlinLoggingConfiguration.appender = DEFAULT_APPENDER
		KotlinLoggingConfiguration.logLevel = Level.OFF
	}
}

/**
 * Filters out all non-positional flag arguments (`--verbose`, `--quiet`, `--debug`)
 * from [args], returning the remaining positional arguments in order.
 *
 * Extracted as an [internal] function so it can be unit-tested independently of [main].
 */
internal fun filterPositionalArgs(args: Array<String>): Array<String> =
	args.filter { it != CMD_VERBOSE && it != CMD_QUIET && it != CMD_DEBUG }.toTypedArray()

/** Writes [message] followed by a newline to stderr using POSIX [fprintf] (not Kotlin stdlib). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun eprintln(message: String?) {
	fprintf(stderr, "%s\n", message ?: "null")
}

/**
 * Handles `--version`, `--help`, and empty args before Koin is started.
 * For these early-exit arguments, prints the appropriate output and calls [exitProcess].
 * If none of these arguments are present, returns normally and the program continues.
 *
 * Note: `--version` and `--help` are checked position-independently (anywhere in args),
 * so `fast-sim --verbose --help` works correctly. As a side effect, placing them after
 * positional args (e.g. `fast-sim sim file.xml 60 --version`) also triggers early exit —
 * this is intentional: these are meta-flags, not simulation parameters.
 */
private fun handleEarlyExitArgs(args: Array<String>) {
	if (args.isEmpty()) {
		printUsage()
		exitProcess(2)
	}
	if (CMD_VERSION in args) {
		println(VERSION_STRING)
		exitProcess(0)
	}
	if (CMD_HELP in args || CMD_HELP_SHORT in args) {
		printUsage()
		exitProcess(0)
	}
}

private fun parseVerbosity(args: Array<String>): Verbosity =
	when {
		CMD_QUIET in args && CMD_VERBOSE in args -> {
			eprintln("Warning: both --quiet and --verbose specified; --quiet takes precedence")
			Verbosity.QUIET
		}
		CMD_QUIET in args -> Verbosity.QUIET
		CMD_VERBOSE in args -> Verbosity.VERBOSE
		else -> Verbosity.DEFAULT
	}

/**
 * Entry point for the :fast-sim native CLI binary.
 *
 * Supported modes:
 * - `fast-sim example <name> <endTime>` — run a built-in example
 * - `fast-sim sim <path> <endTime>` — run simulation from XML file
 * - `fast-sim --version` — print version and exit (no Koin started)
 * - `fast-sim --help` / `fast-sim -h` — print usage and exit 0 (no Koin started)
 * - `fast-sim --debug ...` — enable DEBUG logging to stderr
 * - No args or unknown command → print usage to stderr, exit 2
 *
 * Exit codes: 0 = success, 1 = simulation/runtime error, 2 = invalid arguments, 130 = interrupted (SIGINT)
 *
 * @since Issue #415 (fast-sim native CLI)
 */
@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
	handleEarlyExitArgs(args)

	installSignalHandler()
	val debug = CMD_DEBUG in args
	configureLogging(debug)
	startKoin { modules(coreModule) }

	val verbosity = parseVerbosity(args)
	val positionalArgs = filterPositionalArgs(args)

	val factory = NativeContextFactory()
	val exitCode =
		try {
			if (positionalArgs.isEmpty()) {
				printUsage()
				2
			} else {
				when (positionalArgs[0]) {
					CMD_EXAMPLE -> runExample(positionalArgs, factory, verbosity)
					CMD_SIM -> runSim(positionalArgs, factory, verbosity)
					else -> {
						printUsage()
						2
					}
				}
			}
		} catch (e: IllegalArgumentException) {
			eprintln("Error: ${e.message}")
			2
		} catch (e: Exception) {
			eprintln("Error: ${e.message}")
			1
		} finally {
			stopKoin()
		}

	exitProcess(exitCode)
}

@Suppress("ReturnCount")
private fun runExample(
	args: Array<String>,
	factory: NativeContextFactory,
	verbosity: Verbosity
): Int {
	if (args.size < MIN_ARGS_COUNT) {
		printUsage()
		return 2
	}
	val name = args[1]
	val endTime =
		args[2].toLongOrNull() ?: run {
			eprintln("Error: endTime must be a number, got '${args[2]}'")
			return 2
		}
	val ctx = NativeExampleRegistry.create(name, endTime, factory)
	val reporter = TextReporter(verbosity)
	ctx.addPropertyChangeListener(reporter)
	try {
		ctx.run(cz.vutbr.fit.interlockSim.context.NoOpSimulationController)
		reporter.printSummary()
		return 0
	} catch (
		@Suppress("TooGenericExceptionCaught") e: Exception
	) {
		return handleInterruptedRun(reporter, e)
	} finally {
		ctx.close()
	}
}

@Suppress("ReturnCount")
private fun runSim(
	args: Array<String>,
	factory: NativeContextFactory,
	verbosity: Verbosity
): Int {
	if (args.size < MIN_ARGS_COUNT) {
		printUsage()
		return 2
	}
	val path = args[1]
	val endTime =
		args[2].toLongOrNull() ?: run {
			eprintln("Error: endTime must be a number, got '${args[2]}'")
			return 2
		}
	val ctx = factory.createFromFile(path)
	val issues = ShuntingLoopNetworkValidator.validateVyhybnaCompatible(ctx)
	if (issues.isNotEmpty()) {
		ctx.close()
		eprintln("Error: network is not vyhybna-compatible (required by ShuntingLoop process)")
		eprintln("  Issues:")
		for (issue in issues) {
			eprintln("    - $issue")
		}
		eprintln("  Canonical example: core/src/jvmMain/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
		return 2
	}
	val reporter = TextReporter(verbosity)
	ctx.addPropertyChangeListener(reporter)
	try {
		ctx.setMainProcess(ShuntingLoop(ctx, endTime))
		ctx.run(cz.vutbr.fit.interlockSim.context.NoOpSimulationController)
		reporter.printSummary()
		return 0
	} catch (
		@Suppress("TooGenericExceptionCaught") e: Exception
	) {
		return handleInterruptedRun(reporter, e)
	} finally {
		ctx.close()
	}
}

/**
 * Handles exceptions thrown during simulation execution.
 * If SIGINT was received (mid-run interruption), prints partial summary and returns 130.
 * Otherwise, re-throws the original exception.
 */
private fun handleInterruptedRun(
	reporter: TextReporter,
	e: Exception
): Int {
	if (isInterrupted()) {
		// Safe: TextReporter captures data at PropertyChangeEvent time, not at print time,
		// so printSummary() does not depend on live context state.
		reporter.printSummary()
		return SIGINT_EXIT_CODE
	}
	throw e
}

private fun printUsage() {
	eprintln(
		"""
		Usage:
		  fast-sim [$CMD_VERBOSE|$CMD_QUIET] $CMD_EXAMPLE <name> <endTime>   Run a built-in example (available: ${NativeExampleRegistry.AVAILABLE})
		  fast-sim [$CMD_VERBOSE|$CMD_QUIET] $CMD_SIM <path> <endTime>       Run simulation from XML file (ShuntingLoop process; vyhybna.xml-compatible network required)
		  fast-sim $CMD_VERSION                                Print version
		  fast-sim $CMD_HELP / $CMD_HELP_SHORT                          Print this help

		Flags (combinable with any mode):
		  $CMD_DEBUG                                          Enable DEBUG-level logging output to stderr
		""".trimIndent()
	)
}
