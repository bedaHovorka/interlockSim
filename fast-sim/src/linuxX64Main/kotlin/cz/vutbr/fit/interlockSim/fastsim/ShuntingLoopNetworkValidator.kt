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

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import kotlin.reflect.KClass

/**
 * Validates that a loaded [SimulationEnvironment] has the grid elements the
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop] process expects at fixed coordinates.
 *
 * Required by Issue #435 — previously, loading a non-vyhybna-compatible network
 * failed confusingly inside ShuntingLoop's init block: missing cells led to
 * IllegalArgumentException, while wrong-type cells failed via Util.assertInstanceOf
 * with IllegalStateException. This validator fails fast with an actionable error
 * before ShuntingLoop is constructed.
 *
 * @since Issue #435
 */
internal object ShuntingLoopNetworkValidator {

	private data class Required(val x: Int, val y: Int, val expected: KClass<*>, val label: String)

	private const val LABEL_IN_OUT = "InOut"
	private const val LABEL_SWITCH = "RailSwitch"
	private const val LABEL_SEMAPHORE = "RailSemaphore"

	// Coordinates are ShuntingLoop's hardcoded vyhybna.xml lookups (see ShuntingLoop.init).
	// They must exactly match that contract, so extracting them to named constants
	// would only duplicate labels already present in the Required.label field.
	@Suppress("MagicNumber")
	private val REQUIRED_CELLS: List<Required> = listOf(
		Required(11, 8, DynamicInOut::class, LABEL_IN_OUT),
		Required(30, 8, DynamicInOut::class, LABEL_IN_OUT),
		Required(15, 8, DynamicRailSwitch::class, LABEL_SWITCH),
		Required(26, 8, DynamicRailSwitch::class, LABEL_SWITCH),
		Required(14, 8, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
		Required(16, 8, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
		Required(17, 9, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
		Required(24, 9, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
		Required(25, 8, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
		Required(27, 8, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
	)

	/**
	 * Checks whether the environment's grid has all cells ShuntingLoop requires.
	 *
	 * @return list of human-readable issue strings; empty when the network is compatible.
	 */
	fun validateVyhybnaCompatible(context: SimulationEnvironment): List<String> {
		val grid = context.getRailWayNetGrid()
		val issues = mutableListOf<String>()
		for (req in REQUIRED_CELLS) {
			val cell = grid.getCellAt(req.x, req.y)
			if (cell == null) {
				issues.add("(${req.x}, ${req.y}): expected ${req.label}, but cell is missing")
			} else if (!req.expected.isInstance(cell)) {
				val actual = cell::class.simpleName ?: "unknown"
				issues.add("(${req.x}, ${req.y}): expected ${req.label}, but found $actual")
			}
		}
		return issues
	}
}
