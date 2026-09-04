/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell

/*
 * Shared grid-content checks (Issue #1035 review round). Four test classes each scanned the
 * grid and classified its cells by type; these two functions replace all of those copies.
 *
 * Used by XMLRoundTripTest, ComplexNetworkTest and XMLContextFactoryTest.
 */

/** Cell-type keys returned by [countCellTypes]. */
const val INOUT_KEY: String = "InOut"

/** See [INOUT_KEY]. */
const val RAIL_SWITCH_KEY: String = "RailSwitch"

/** See [INOUT_KEY]. */
const val RAIL_SEMAPHORE_KEY: String = "RailSemaphore"

/**
 * Report whether the grid of [context] holds a cell of type [T], for example to assert that
 * vyhybna content (switches, semaphores) survives a round trip.
 *
 * `RailwayNetGrid` iterates its populated cells only, so this costs one pass over the cells
 * that exist, not over every coordinate of a grid that can be 500x500.
 */
inline fun <reified T : Cell> gridContainsCellType(context: Context<*, *>): Boolean =
	context.getRailWayNetGrid().any { it.value is T }

/**
 * Count the InOut, RailSwitch, and RailSemaphore cells of [grid], keyed by [INOUT_KEY],
 * [RAIL_SWITCH_KEY], and [RAIL_SEMAPHORE_KEY]. Every key is present, with 0 when the grid
 * holds no cell of that type.
 *
 * The parameter is typed `RailwayNetGrid<Cell>`: the grid is declared as
 * `RailwayNetGrid<NodeCell>` but internally holds Cell (NodeCell + TrackBlockPart), and the
 * covariant `out T` parameter lets the caller pass it without a cast, so the entries can be
 * iterated as Cell without a ClassCastException.
 */
fun countCellTypes(grid: RailwayNetGrid<Cell>): Map<String, Int> {
	val counts = mutableMapOf(INOUT_KEY to 0, RAIL_SWITCH_KEY to 0, RAIL_SEMAPHORE_KEY to 0)

	for (entry in grid) {
		val key =
			when (entry.value) {
				is InOut -> INOUT_KEY
				is RailSwitch -> RAIL_SWITCH_KEY
				is RailSemaphore -> RAIL_SEMAPHORE_KEY
				else -> null
			}
		if (key != null) counts[key] = counts.getValue(key) + 1
	}

	return counts
}
