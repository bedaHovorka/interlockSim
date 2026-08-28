/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: grid lookups shared by the navigation test suite
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Every cell of type [T] in this context's grid, in column-major sweep order.
 *
 * The navigation suite needed "all semaphores", "all switches", "all free blocks" over and over,
 * and wrote the same nested `for (x) for (y) { if (cell is X) … }` sweep each time — seven named
 * copies plus ten anonymous ones (Issue #955, cluster N3). This is that sweep, once.
 *
 * A simulation context's grid holds the dynamic wrappers, so ask for the dynamic type
 * (`cellsOfType<DynamicRailSemaphore>()`), not the static one.
 */
inline fun <reified T : Any> SimulationContext.cellsOfType(): List<T> {
	val grid = getRailWayNetGrid()
	val found = mutableListOf<T>()
	for (x in 0 until grid.cols) {
		for (y in 0 until grid.rows) {
			val cell = grid[Point(x, y)]
			if (cell is T) {
				found.add(cell)
			}
		}
	}
	return found
}

/**
 * The single cell of type [T] whose `name` is [name].
 *
 * @throws IllegalStateException when the grid holds no such cell — the message names the type and
 *   the name asked for, which is what a mistyped station element looks like in a test failure.
 */
inline fun <reified T : NodeCell> SimulationContext.cellNamed(name: String): T =
	cellsOfType<T>().firstOrNull { it.getName() == name }
		?: throw IllegalStateException("No ${T::class.simpleName} named '$name' in the loaded grid")

/**
 * The dynamic separator sitting at grid position ([x], [y]).
 *
 * Five navigation tests each declared a private `separatorAt` doing exactly this — read the cell,
 * check it is a [PathSeparator], map it to its dynamic wrapper (Issue #955, cluster N2).
 *
 * @throws IllegalStateException when that position holds no path separator
 */
fun SimulationContext.separatorAt(
	x: Int,
	y: Int
): DynamicPathSeparator {
	val cell = getRailWayNetGrid()[Point(x, y)]
	val separator =
		cell as? PathSeparator
			?: throw IllegalStateException("Grid position ($x, $y) holds no path separator (was: $cell)")
	return toDynamic(separator)
}
