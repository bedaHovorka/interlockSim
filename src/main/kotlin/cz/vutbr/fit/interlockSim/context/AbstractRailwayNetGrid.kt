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
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Map.Entry;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.util.Array2DMap;

/**
 * Implementation of {@link RailwayNetGrid}
 *
 */
public abstract class AbstractRailwayNetGrid implements RailwayNetGrid {
	private final int cols;
	private final int rows;
	private final Map<Cell, Point> reverseTable = new WeakHashMap<Cell, Point>();
	private final Array2DMap<Cell> cells = new Array2DMap<Cell>();

	/**
	 * @param cols count of colums
	 * @param rows count of rows
	 */
	public AbstractRailwayNetGrid(int cols, int rows) {
		this.cols = cols;
		this.rows = rows;
	}

	public Cell getCellAt(int x, int y) {
		if (x < 0 || y < 0 || x >= cols || y >= rows) throw new IndexOutOfBoundsException("Grid bounds");
		return cells.get(x,y);
	}

	public Cell get(Point point) {
		return getCellAt(point.x, point.y);
	}

	public int getCols() {
		return cols;
	}

	public int getRows() {
		return rows;
	}

	public Iterator<Entry<Point, Cell>> iterator() {
		return Collections.unmodifiableSet(cells.entrySet()).iterator();
	}

	protected Array2DMap<Cell> getCells() {
		return cells;
	}

	protected Map<Cell, Point> getReverseTable() {
		return reverseTable;
	}

	public Point getLocation(Cell value) {
		return reverseTable.get(value);
	}
}
