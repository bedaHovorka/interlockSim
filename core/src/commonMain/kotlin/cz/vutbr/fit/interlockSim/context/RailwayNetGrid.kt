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

import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Read-only access interface to Grid...
 *
 * aby bylo rozhrani dokonale tak iterator vraceny implementaci tohoto rozhrani
 * musi u [Iterator.remove] hazet [UnsupportedOperationException]
 *
 * @param T the type of cells stored in this grid (must extend [Cell])
 */
interface RailwayNetGrid<out T : Cell> : Iterable<Map.Entry<Point, T>> {
	/**
	 * @param x
	 * @param y
	 * @return cell
	 */
	fun getCellAt(
		x: Int,
		y: Int
	): T?

	/**
	 * @param point
	 * @return cell
	 */
	operator fun get(point: Point): T?

	/**
	 * Count of columns (width)
	 */
	val cols: Int

	/**
	 * Count of rows (height)
	 */
	val rows: Int

	/**
	 * @param out
	 * @return location of cell
	 */
	fun getLocation(out: @UnsafeVariance T): Point?

	/**
	 * Check if a point contains a cell
	 * @param point the point to check
	 * @return true if the point contains a cell
	 */
	fun containsKey(point: Point): Boolean
}
