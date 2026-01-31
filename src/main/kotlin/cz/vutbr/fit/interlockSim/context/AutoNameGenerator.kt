package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Generates sequential names for newly created railway elements.
 *
 * Naming scheme:
 * - RailSemaphore → "S1", "S2", "S3", ...
 * - RailSwitch → "SW1", "SW2", "SW3", ...
 * - InOut → "IO1", "IO2", "IO3", ...
 * - Other NodeCell types → "E1", "E2", "E3", ...
 *
 * Ensures uniqueness by checking against existing names in the context.
 *
 * Thread-safety: Not thread-safe. Assumes single-threaded GUI usage.
 */
object AutoNameGenerator {
	private val logger = KotlinLogging.logger {}
	private val counters = mutableMapOf<String, Int>()

	/**
	 * Generates a unique name for a new cell.
	 *
	 * @param cellClass The class of the cell being created
	 * @param context The editing context to check for existing names
	 * @return A unique sequential name
	 */
	fun generateName(cellClass: Class<out NodeCell>, context: EditingContext): String {
		val prefix = getPrefixForClass(cellClass)
		val counter = counters.getOrDefault(prefix, 0)

		var candidateNumber = counter + 1
		var candidateName = "$prefix$candidateNumber"

		// Ensure uniqueness by checking all cells in grid
		while (nameExists(candidateName, context)) {
			candidateNumber++
			candidateName = "$prefix$candidateNumber"
		}

		// Update counter for this prefix
		counters[prefix] = candidateNumber

		logger.info { "AutoNameGenerator: Generated name '$candidateName' for ${cellClass.simpleName}" }
		return candidateName
	}

	/**
	 * Returns the name prefix for a given cell class.
	 */
	private fun getPrefixForClass(cellClass: Class<out NodeCell>): String =
		when (cellClass) {
			RailSemaphore::class.java -> "S"
			RailSwitch::class.java -> "SW"
			InOut::class.java -> "IO"
			else -> "E"  // Generic element
		}

	/**
	 * Checks if a name already exists in the context.
	 */
	private fun nameExists(name: String, context: EditingContext): Boolean {
		val grid = context.getRailWayNetGrid()
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val point = cz.vutbr.fit.interlockSim.util.Point(x, y)
				val cell = grid[point]
				if (cell is NodeCell && cell.getName() == name) {
					return true
				}
			}
		}
		return false
	}

	/**
	 * Resets all counters (for testing or new context creation).
	 */
	fun reset() {
		counters.clear()
	}
}
