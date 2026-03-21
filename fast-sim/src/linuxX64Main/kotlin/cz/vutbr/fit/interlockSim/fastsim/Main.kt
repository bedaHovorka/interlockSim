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
import kotlin.system.exitProcess

/**
 * Entry point for the :fast-sim native CLI binary.
 *
 * Supported modes:
 * - `fast-sim example <name> <endTime>` — run a built-in example
 * - `fast-sim sim <path> <endTime>` — run simulation from XML file
 * - `fast-sim --version` — print version and exit
 * - No args or unknown command → print usage to stderr, exit 2
 *
 * Exit codes: 0 = success, 1 = simulation/runtime error, 2 = invalid arguments
 *
 * @since Issue #415 (fast-sim native CLI)
 */
fun main(args: Array<String>) {
	if (args.isEmpty()) {
		printUsage()
		exitProcess(2)
	}

	startKoin { modules(coreModule) }

	val exitCode = try {
		when (args[0]) {
			"--version" -> { println("fast-sim 1.0"); 0 }
			"example" -> runExample(args)
			"sim" -> runSim(args)
			else -> { printUsage(); 2 }
		}
	} catch (e: IllegalArgumentException) {
		System.err.println("Error: ${e.message}")
		2
	} catch (e: Exception) {
		System.err.println("Error: ${e.message}")
		1
	} finally {
		stopKoin()
	}

	exitProcess(exitCode)
}

private fun runExample(args: Array<String>): Int {
	if (args.size < 3) {
		printUsage()
		return 2
	}
	val name = args[1]
	val endTime = args[2].toLongOrNull() ?: run {
		System.err.println("Error: endTime must be a number, got '${args[2]}'")
		return 2
	}
	val ctx = NativeExampleRegistry.create(name, endTime)
	ctx.run()
	println("Simulation complete.")
	return 0
}

private fun runSim(args: Array<String>): Int {
	if (args.size < 3) {
		printUsage()
		return 2
	}
	val path = args[1]
	val endTime = args[2].toLongOrNull() ?: run {
		System.err.println("Error: endTime must be a number, got '${args[2]}'")
		return 2
	}
	val ctx = NativeContextFactory().createFromFile(path)
	ctx.setMainProcess(ShuntingLoop(ctx, endTime))
	ctx.run()
	println("Simulation complete.")
	return 0
}

private fun printUsage() {
	System.err.println(
		"""
		Usage:
		  fast-sim example <name> <endTime>   Run a built-in example (available: ${NativeExampleRegistry.available})
		  fast-sim sim <path> <endTime>        Run simulation from XML file
		  fast-sim --version                   Print version
		""".trimIndent()
	)
}
