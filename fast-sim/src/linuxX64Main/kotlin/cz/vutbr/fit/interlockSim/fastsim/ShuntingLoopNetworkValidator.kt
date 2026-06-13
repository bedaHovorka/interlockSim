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
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
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
	private data class Required(
		val x: Int,
		val y: Int,
		val expected: KClass<*>,
		val label: String
	)

	private const val LABEL_IN_OUT = "InOut"
	private const val LABEL_SWITCH = "RailSwitch"
	private const val LABEL_SEMAPHORE = "RailSemaphore"

	// Coordinates are sourced from ShuntingLoop.Companion constants so there is
	// exactly one authoritative list of the vyhybna network contract.
	private val REQUIRED_CELLS: List<Required> =
		listOf(
			Required(ShuntingLoop.COORD_IN_A_X, ShuntingLoop.COORD_IN_A_Y, DynamicInOut::class, LABEL_IN_OUT),
			Required(ShuntingLoop.COORD_IN_B_X, ShuntingLoop.COORD_IN_B_Y, DynamicInOut::class, LABEL_IN_OUT),
			Required(ShuntingLoop.COORD_SW_A_X, ShuntingLoop.COORD_SW_A_Y, DynamicRailSwitch::class, LABEL_SWITCH),
			Required(ShuntingLoop.COORD_SW_B_X, ShuntingLoop.COORD_SW_B_Y, DynamicRailSwitch::class, LABEL_SWITCH),
			Required(ShuntingLoop.COORD_SEM_ZA_X, ShuntingLoop.COORD_SEM_ZA_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
			Required(ShuntingLoop.COORD_SEM_DOA1_X, ShuntingLoop.COORD_SEM_DOA1_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
			Required(ShuntingLoop.COORD_SEM_DOA2_X, ShuntingLoop.COORD_SEM_DOA2_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
			Required(ShuntingLoop.COORD_SEM_DOB2_X, ShuntingLoop.COORD_SEM_DOB2_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
			Required(ShuntingLoop.COORD_SEM_DOB1_X, ShuntingLoop.COORD_SEM_DOB1_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE),
			Required(ShuntingLoop.COORD_SEM_ZB_X, ShuntingLoop.COORD_SEM_ZB_Y, DynamicRailSemaphore::class, LABEL_SEMAPHORE)
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
