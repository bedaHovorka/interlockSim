/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.Point
import java.util.WeakHashMap

/**
 * Implementation of {@link RailwayNetGrid}
 *
 * @param T the type of cells stored in this grid (must extend [Cell])
 */
abstract class AbstractRailwayNetGrid<out T : Cell>(
	cols: Int,
	rows: Int
) : RailwayNetGrid<T> {
	// Use private backing fields to avoid property getter clash
	private val _cols: Int = cols
	private val _rows: Int = rows

	private val reverseTable: MutableMap<@UnsafeVariance T, Point> = WeakHashMap()
	private val cells: Array2DMap<@UnsafeVariance T> = Array2DMap()

	protected fun getCells(): Array2DMap<@UnsafeVariance T> = cells

	protected fun getReverseTable(): MutableMap<@UnsafeVariance T, Point> = reverseTable

	@Synchronized
	override fun getCellAt(
		x: Int,
		y: Int
	): T? {
		if (x < 0 || y < 0 || x >= _cols || y >= _rows) throw IndexOutOfBoundsException("Grid bounds")
		return cells.get(x, y)
	}

	override operator fun get(point: Point): T? = getCellAt(point.x, point.y)

	// Implement interface methods
	override fun getCols(): Int = _cols

	override fun getRows(): Int = _rows

	override fun iterator(): kotlin.collections.Iterator<Map.Entry<Point, T>> {
		// Build entries from cells and return an immutable iterator
		return cells.entries.toList().iterator()
	}

	override fun getLocation(value: @UnsafeVariance T): Point? = reverseTable[value]

	override fun containsKey(point: Point): Boolean = cells.containsKey(point)
}
