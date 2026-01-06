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
import java.awt.Point
import java.util.Iterator
import java.util.Map.Entry
import java.util.Set
import java.util.WeakHashMap

/**
 * Implementation of {@link RailwayNetGrid}
 *
 */
abstract class AbstractRailwayNetGrid(
	cols: Int,
	rows: Int
) : RailwayNetGrid {
	// Use private backing fields to avoid property getter clash
	private val _cols: Int = cols
	private val _rows: Int = rows

	private val reverseTable: MutableMap<Cell, Point> = WeakHashMap()
	private val cells: Array2DMap<Cell> = Array2DMap()

	protected fun getCells(): Array2DMap<Cell> = cells

	protected fun getReverseTable(): MutableMap<Cell, Point> = reverseTable

	override fun getCellAt(
		x: Int,
		y: Int
	): Cell? {
		if (x < 0 || y < 0 || x >= _cols || y >= _rows) throw IndexOutOfBoundsException("Grid bounds")
		return cells.get(x, y)
	}

	override operator fun get(point: Point): Cell? = getCellAt(point.x, point.y)

	// Implement interface methods
	override fun getCols(): Int = _cols

	override fun getRows(): Int = _rows

	override fun iterator(): kotlin.collections.Iterator<Entry<Point, Cell>> {
		// Build entries from cells and return an immutable iterator
		@Suppress("UNCHECKED_CAST")
		val entries = (cells.entries as java.util.Set<Entry<Point, Cell>>).toList()
		return entries.iterator()
	}

	override fun getLocation(value: Cell): Point? = reverseTable[value]
}
