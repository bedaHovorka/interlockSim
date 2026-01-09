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
import cz.vutbr.fit.interlockSim.util.Point
import java.util.Iterator
import java.util.Map.Entry

/**
 * Read-only access interface to Grid...
 *
 * aby bylo rozhrani dokonale tak iterator vraceny implementaci tohoto rozhrani
 * musi u [Iterator.remove] hazet [UnsupportedOperationException]
 */
interface RailwayNetGrid : Iterable<Entry<Point, Cell>> {
	/**
	 * @param x
	 * @param y
	 * @return cell
	 */
	fun getCellAt(
		x: Int,
		y: Int
	): Cell?

	/**
	 * @param point
	 * @return cell
	 */
	fun get(point: Point): Cell?

	/**
	 * @return count of colums (width)
	 */
	fun getCols(): Int

	/**
	 * @return count of rows (height)
	 */
	fun getRows(): Int

	/**
	 * @param out
	 * @return location of cell
	 */
	fun getLocation(out: Cell): Point?
}
