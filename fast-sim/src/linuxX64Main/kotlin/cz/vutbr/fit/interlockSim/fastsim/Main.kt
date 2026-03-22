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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess

private const val MIN_ARGS_COUNT = 3
private const val CMD_VERSION = "--version"
private const val CMD_HELP = "--help"
private const val CMD_HELP_SHORT = "-h"
private const val CMD_EXAMPLE = "example"
private const val CMD_SIM = "sim"
private const val VERSION_STRING = "fast-sim 1.0"

/** Writes [message] followed by a newline to stderr using POSIX [fprintf] (not Kotlin stdlib). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun eprintln(message: String?) {
	fprintf(stderr, "%s\n", message ?: "null")
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
 * Exit codes: 0 = success, 1 = simulation/runtime error, 2 = invalid arguments
 *
 * @since Issue #415 (fast-sim native CLI)
 */
@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
	if (args.isEmpty()) {
		printUsage()
		exitProcess(2)
	}

	// --version and --help are handled before Koin init: they need no DI and must be near-instant
	if (args[0] == CMD_VERSION) {
		println(VERSION_STRING)
		exitProcess(0)
	}
	if (args[0] == CMD_HELP || args[0] == CMD_HELP_SHORT) {
		printUsage()
		exitProcess(0)
	}

	startKoin { modules(coreModule) }

	val factory = NativeContextFactory()
	val exitCode = try {
		when (args[0]) {
			CMD_EXAMPLE -> runExample(args, factory)
			CMD_SIM     -> runSim(args, factory)
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
private fun runExample(args: Array<String>, factory: NativeContextFactory): Int {
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
	try {
		ctx.run()
		println("Simulation complete.")
		return 0
	} finally {
		ctx.close()
	}
}

@Suppress("ReturnCount")
private fun runSim(args: Array<String>, factory: NativeContextFactory): Int {
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
	try {
		ctx.setMainProcess(ShuntingLoop(ctx, endTime))
		ctx.run()
		println("Simulation complete.")
		return 0
	} finally {
		ctx.close()
	}
}

private fun printUsage() {
	eprintln(
		"""
		Usage:
		  fast-sim $CMD_EXAMPLE <name> <endTime>   Run a built-in example (available: ${NativeExampleRegistry.AVAILABLE})
		  fast-sim $CMD_SIM <path> <endTime>       Run simulation from XML file (ShuntingLoop process; vyhybna.xml-compatible network required)
		  fast-sim $CMD_VERSION                  Print version
		  fast-sim $CMD_HELP / $CMD_HELP_SHORT             Print this help
		""".trimIndent()
	)
}
