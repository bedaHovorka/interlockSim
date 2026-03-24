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
const val SIGINT_EXIT_CODE = 130

/**
 * Holds the atomic SIGINT flag. Encapsulated in an object because the signal handler
 * (a C static function) cannot capture closures — it accesses this singleton directly.
 */
internal object SignalState {
	val INTERRUPTED = AtomicInt(0)
}

/** Returns `true` if a SIGINT signal has been received since program start. */
fun isInterrupted(): Boolean = SignalState.INTERRUPTED.value != 0

/** Installs a POSIX signal handler that sets the [SignalState.INTERRUPTED] flag on SIGINT. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun installSignalHandler() {
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
private const val VERSION_STRING = "fast-sim 1.0"

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

private fun parseVerbosity(args: Array<String>): Verbosity = when {
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
	KotlinLoggingConfiguration.logLevel = Level.OFF
	startKoin { modules(coreModule) }

	val verbosity = parseVerbosity(args)
	val positionalArgs = args.filter { it != CMD_VERBOSE && it != CMD_QUIET }.toTypedArray()

	val factory = NativeContextFactory()
	val exitCode = try {
		if (positionalArgs.isEmpty()) {
			printUsage()
			2
		} else when (positionalArgs[0]) {
			CMD_EXAMPLE -> runExample(positionalArgs, factory, verbosity)
			CMD_SIM     -> runSim(positionalArgs, factory, verbosity)
			else        -> { printUsage(); 2 }
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
private fun runExample(args: Array<String>, factory: NativeContextFactory, verbosity: Verbosity): Int {
	if (args.size < MIN_ARGS_COUNT) {
		printUsage()
		return 2
	}
	val name = args[1]
	val endTime = args[2].toLongOrNull() ?: run {
		eprintln("Error: endTime must be a number, got '${args[2]}'")
		return 2
	}
	val ctx = NativeExampleRegistry.create(name, endTime, factory)
	val reporter = TextReporter(verbosity)
	ctx.addPropertyChangeListener(reporter)
	try {
		ctx.run()
		reporter.printSummary()
		return if (isInterrupted()) SIGINT_EXIT_CODE else 0
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		if (isInterrupted()) {
			reporter.printSummary()
			return SIGINT_EXIT_CODE
		}
		throw e
	} finally {
		ctx.close()
	}
}

@Suppress("ReturnCount")
private fun runSim(args: Array<String>, factory: NativeContextFactory, verbosity: Verbosity): Int {
	if (args.size < MIN_ARGS_COUNT) {
		printUsage()
		return 2
	}
	val path = args[1]
	val endTime = args[2].toLongOrNull() ?: run {
		eprintln("Error: endTime must be a number, got '${args[2]}'")
		return 2
	}
	val ctx = factory.createFromFile(path)
	val reporter = TextReporter(verbosity)
	ctx.addPropertyChangeListener(reporter)
	try {
		ctx.setMainProcess(ShuntingLoop(ctx, endTime))
		ctx.run()
		reporter.printSummary()
		return if (isInterrupted()) SIGINT_EXIT_CODE else 0
	} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
		if (isInterrupted()) {
			reporter.printSummary()
			return SIGINT_EXIT_CODE
		}
		throw e
	} finally {
		ctx.close()
	}
}

private fun printUsage() {
	eprintln(
		"""
		Usage:
		  fast-sim [$CMD_VERBOSE|$CMD_QUIET] $CMD_EXAMPLE <name> <endTime>   Run a built-in example (available: ${NativeExampleRegistry.AVAILABLE})
		  fast-sim [$CMD_VERBOSE|$CMD_QUIET] $CMD_SIM <path> <endTime>       Run simulation from XML file (ShuntingLoop process; vyhybna.xml-compatible network required)
		  fast-sim $CMD_VERSION                                Print version
		  fast-sim $CMD_HELP / $CMD_HELP_SHORT                          Print this help
		""".trimIndent()
	)
}
