/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.core.Cell

/**
 * Returns every cell of type [R] currently placed in this grid, scanning each `x`/`y`
 * coordinate via [RailwayNetGrid.getCellAt].
 *
 * @since Goal 10 code-review fix — replaces the near-identical grid-scan loops previously
 *   duplicated across [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] and
 *   [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort]
 */
inline fun <reified R : Cell> RailwayNetGrid<Cell>.cellsOfType(): List<R> {
	val result = mutableListOf<R>()
	for (x in 0 until cols) {
		for (y in 0 until rows) {
			val cell = getCellAt(x, y)
			if (cell is R) result.add(cell)
		}
	}
	return result
}
