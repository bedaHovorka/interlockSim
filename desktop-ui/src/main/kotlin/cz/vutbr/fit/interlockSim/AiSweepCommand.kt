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

import java.nio.file.Path

/**
 * Parsed `aiSweep` command line (SP2c.24, Issue #847).
 *
 * ```
 * java -jar interlockSim.jar aiSweep --grid <file> [--out <dir>] [--repeat N]
 *                                    [--timeout <seconds>] [--dry-run]
 * ```
 *
 * `--repeat` and `--timeout` override the corresponding grid-file fields, so a grid committed for
 * the real measurement can be reused for a two-run smoke check without editing it.
 *
 * @property gridFile Path to the JSON parameter grid.
 * @property outputRoot Directory the run JSONs and `report.md` are written under.
 * @property repeatOverride Replaces the grid's `repeat`, or `null` to keep it.
 * @property timeoutOverride Replaces the grid's `perRunTimeoutSeconds`, or `null` to keep it.
 * @property dryRun Enumerate the runs and stop, launching nothing.
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
data class AiSweepCommand(
	val gridFile: Path,
	val outputRoot: Path,
	val repeatOverride: Int?,
	val timeoutOverride: Long?,
	val dryRun: Boolean
) {
	companion object {
		/**
		 * Deliberately **not** `build/reports/dispatcher-runs`.
		 *
		 * That directory accumulates every ad-hoc run ever made on the machine, and the A4 gate is
		 * computed over whatever it finds. A sweep must be judged on its own runs, so it gets its
		 * own directory and the report rendered there covers exactly the sweep that produced it.
		 */
		val DEFAULT_OUTPUT_ROOT: Path = Path.of("build", "reports", "dispatcher-sweep")

		const val USAGE: String =
			"usage: aiSweep --grid <file> [--out <dir>] [--repeat <n>] [--timeout <seconds>] [--dry-run]"

		/**
		 * Parses `args` as produced by `main` (so `args[0]` is the mode itself).
		 *
		 * @throws AiSweepUsageException if a flag is unknown, a value is missing or unparseable, or
		 *   `--grid` is absent. Failing loudly matters more here than elsewhere: the alternative is
		 *   silently sweeping a default grid for two hours and producing a report of the wrong thing.
		 */
		fun parse(args: Array<String>): AiSweepCommand {
			var gridFile: Path? = null
			var outputRoot: Path = DEFAULT_OUTPUT_ROOT
			var repeat: Int? = null
			var timeout: Long? = null
			var dryRun = false

			var index = 1
			while (index < args.size) {
				when (val flag = args[index]) {
					"--grid" -> {
						gridFile = Path.of(requireValue(args, index, flag))
						index += 2
					}

					"--out" -> {
						outputRoot = Path.of(requireValue(args, index, flag))
						index += 2
					}

					"--repeat" -> {
						repeat =
							requireValue(args, index, flag).toIntOrNull()?.takeIf { it > 0 }
								?: throw AiSweepUsageException("--repeat must be a positive integer")
						index += 2
					}

					"--timeout" -> {
						timeout =
							requireValue(args, index, flag).toLongOrNull()?.takeIf { it > 0 }
								?: throw AiSweepUsageException("--timeout must be a positive number of seconds")
						index += 2
					}

					"--dry-run" -> {
						dryRun = true
						index += 1
					}

					else -> throw AiSweepUsageException("Unknown option '$flag'")
				}
			}

			return AiSweepCommand(
				gridFile = gridFile ?: throw AiSweepUsageException("--grid is required"),
				outputRoot = outputRoot,
				repeatOverride = repeat,
				timeoutOverride = timeout,
				dryRun = dryRun
			)
		}

		private fun requireValue(
			args: Array<String>,
			index: Int,
			flag: String
		): String = args.getOrNull(index + 1) ?: throw AiSweepUsageException("$flag requires a value")
	}
}

/** Raised when the `aiSweep` command line cannot be understood. */
class AiSweepUsageException(
	message: String
) : Exception(message)
