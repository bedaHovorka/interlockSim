/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;
import java.util.Iterator;
import java.util.Map.Entry;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;

/**
 * Read-only access interface to Grid...
 *
 * aby bylo rozhrani dokonale tak iterator vraceny implementaci tohoto rozhrani
 * musi u {@link Iterator#remove()} hazet {@link UnsupportedOperationException}
 */
public interface RailwayNetGrid extends Iterable<Entry<Point, Cell>> {
	/**
	 * @param x
	 * @param y
	 * @return cell
	 */
	public Cell getCellAt(int x, int y);

	/**
	 * @param point
	 * @return cell
	 */
	public Cell get(Point point);

	/**
	 * @return count of colums (width)
	 */
	public int getCols();

	/**
	 * @return count of rows (height)
	 */
	public int getRows();

	/**
	 * @param out
	 * @return location of cell
	 */
	public Point getLocation(Cell out);
}
