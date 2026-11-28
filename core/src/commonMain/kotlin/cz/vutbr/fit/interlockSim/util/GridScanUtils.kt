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
 * A FALLBACK-involved collision fails the scan instead of listing two switches under one name
 * (Issue #1473 review, Copilot thread PRRT_kwDOQyJ9Cc68uzeu): a blank switch whose generated name
 * meets a name indexed earlier throws, and so does an explicit name that arrives after a generated
 * one. The diagnostic is the same [IllegalArgumentException] that
 * `DefaultNetworkActuatorPort.buildSwitchCache` rejects its own cache with —
 * the shared name, both grid positions, and the explicit-name fix — so a test asserts
 * one common message fragment for both. Plain explicit-explicit duplicates keep the scan as it was:
 * both entries stay listed in scan order, and the consumer's lookup contract resolves the name.
 *
 * @since Issue #1468 — the scan the perception port keys its switches by. The actuator port keeps
 *   its own rejecting scan (`buildSwitchCache`) and may adopt this helper in a recorded follow-up
 *   of the #1516 review round.
 */
fun RailwayNetGrid<Cell>.switchesByStableName(): List<Pair<String, DynamicRailSwitch>> {
	// The same getCellAt scan as cellsOfType, keeping each position for the fallback name and
	// for the collision diagnostic.
	val result = mutableListOf<Pair<String, DynamicRailSwitch>>()
	// The position behind every indexed name and behind the FALLBACK-generated names in particular,
	// so the diagnostic of a collision can name both parties — the same two maps
	// `buildSwitchCache` tracks for its own index (Issue #1473).
	val positionByName = HashMap<String, Point>()
	val generatedPositionByName = HashMap<String, Point>()
	for (x in 0 until cols) {
		for (y in 0 until rows) {
			val switch = getCellAt(x, y) as? DynamicRailSwitch ?: continue
			val position = Point(x, y)
			val name = GeneratedElementName.switchName(switch.name, position)
			if (switch.name.isBlank()) {
				// The incoming name is generated, so it may not overtake any name indexed
				// earlier: an explicit holder of the same name would lose its switch.
				positionByName[name]?.let { throw switchNameCollision(position, name, it) }
				generatedPositionByName[name] = position
			} else {
				// The incoming name is explicit, so it may only overtake another explicit
				// indexee; a fallback name indexed earlier loses its switch, the mirror-side
				// shape of the same collision.
				generatedPositionByName[name]?.let { throw switchNameCollision(it, name, position) }
			}
			positionByName[name] = position
			result += name to switch
		}
	}
	return result
}

/**
 * The failure for a generated name that meets the explicit name of another switch, whichever one
 * the grid scan reaches first: the two switches would share the name, so a planner would read one
 * and an actuator command move the other. The wording is verbatim the one
 * `DefaultNetworkActuatorPort.buildSwitchCache` rejects its own cache with (Issue #1473), so both
 * rejections read identically in logs and tests.
 */
private fun switchNameCollision(
	generatedAt: Point,
	name: String,
	explicitAt: Point
): IllegalArgumentException =
	IllegalArgumentException(
		"The unnamed RailSwitch at ${switchPositionLabel(generatedAt)} gets the generated name $name, " +
			"which is already the name of the element at ${switchPositionLabel(explicitAt)}; " +
			"give the switch at ${switchPositionLabel(generatedAt)} an explicit name in code or move it to another position"
	)

private fun switchPositionLabel(position: Point): String = "(${position.x},${position.y})"

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
