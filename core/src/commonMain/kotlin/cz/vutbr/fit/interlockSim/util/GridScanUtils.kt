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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName

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

/**
 * Returns every [DynamicRailSwitch] in this grid with its stable name, in scan order: the switch's
 * own name, or for a blank name (a context built in code) the generated
 * [GeneratedElementName.switchName] of its grid position. The same `getCellAt` scan as [cellsOfType],
 * keeping each position for the fallback name.
 *
 * @since Issue #1468 — the one scan the perception and actuator ports key their switches by
 */
fun RailwayNetGrid<Cell>.switchesByStableName(): List<Pair<String, DynamicRailSwitch>> {
	val result = mutableListOf<Pair<String, DynamicRailSwitch>>()
	for (x in 0 until cols) {
		for (y in 0 until rows) {
			val switch = getCellAt(x, y) as? DynamicRailSwitch ?: continue
			result += GeneratedElementName.switchName(switch.name, Point(x, y)) to switch
		}
	}
	return result
}

/**
 * Returns every cell of type [R] in this grid that has a non-blank [name], indexed by that name:
 * a [cellsOfType] scan followed by [indexByNonBlankName].
 *
 * Duplicate names follow [associateBy]: the cell scanned last wins.
 *
 * @param name reads the cell's name (the dynamic cell types expose `name` without a shared supertype)
 * @since Issue #959 — replaces the scan → filter → `associateBy` chain repeated in the network ports
 */
inline fun <reified R : Cell> RailwayNetGrid<Cell>.cellsByName(name: (R) -> String): Map<String, R> =
	cellsOfType<R>().indexByNonBlankName(name)

/**
 * Indexes these elements by [name], dropping those whose name is blank. Duplicate names follow
 * [associateBy]: the last element wins.
 *
 * @since Issue #959 — the tail of [cellsByName], for callers that already hold the scanned cells
 */
inline fun <T> Iterable<T>.indexByNonBlankName(name: (T) -> String): Map<String, T> {
	// One pass and one `name` read per element; `result[key] = element` keeps
	// [associateBy]'s last-wins rule for duplicate names.
	val result = LinkedHashMap<String, T>()
	for (element in this) {
		val key = name(element)
		if (key.isNotBlank()) result[key] = element
	}
	return result
}
