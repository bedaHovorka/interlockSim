/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart;

/**
 * Grid represantation
 */
public class DefaultRailWayNetGrid extends AbstractRailwayNetGrid {
	/**
	 * @see AbstractRailwayNetGrid
	 * @param cols
	 * @param rows
	 */
	public DefaultRailWayNetGrid(int cols, int rows) {
		super(cols, rows);
	}

	private class KeySet extends AbstractSet<Point> {
		private final Set<Entry<Point, Cell>> set;

		KeySet(final Set<Entry<Point, Cell>> set) {
			this.set = set;
		}

		@Override
		public Iterator<Point> iterator() {
			return new Iterator<Point>() {
				private Iterator<Entry<Point, Cell>> i = set.iterator();
				private Entry<Point, Cell> next;
			
				public void remove() {
					if (next == null) throw new IllegalStateException();
					i.remove();
					getReverseTable().remove(next.getValue());
					getReverseTable().values().remove(next.getKey());
				}
			
				public Point next() {
					next = i.next();
					return next.getKey();
				}
			
				public boolean hasNext() {
					return i.hasNext();
				}
			
			};
		}

		@Override
		public int size() {
			return set.size();
		}
	}
	
	/**
	 * @param key
	 * @param cell 
	 * @return previous cell in place
	 */
	public final Cell put(final Point key,final Cell cell) {
		if (getCells().get(key) == cell) return cell;
		getCells().values().remove(cell);
		final Cell prev = getCells().put(key, cell);
		getReverseTable().remove(prev);
		getReverseTable().put(cell, key);
		return prev;
	}

	/**
	 * @param map of point to trackblock part
	 */
	public void putMap(final Map<Point, TrackBlockPart> map) {
		for (Entry<Point, TrackBlockPart> e : map.entrySet()) {
			put(e.getKey(), e.getValue());
		}
	}

	/**
	 * @param newPoint
	 * @return true if point is present 
	 */
	public boolean containsKey(Point newPoint) {
		if (getCells().containsKey(newPoint)) {
			assert getReverseTable().containsValue(newPoint) : newPoint;
			return true;
		}
		assert !getReverseTable().containsValue(newPoint) : newPoint;
		return false;
	}

	/**
	 * Remove cell from grid
	 * @param key
	 */
	public void remove(final Point key) {
		assert key!=null;
		final Cell removed = getCells().remove(key);
		final Point remove2 = getReverseTable().remove(removed);
		assert key.equals(remove2);
	}

	/**
	 * @return true if grid is empty
	 */
	public boolean isEmpty() {
		assert getReverseTable().size() == getCells().size();
		return getCells().size() == 0;
	}

	/**
	 * All cell in grid
	 * @return set of cells
	 */
	public Set<Point> keySet() {
		return new KeySet(getCells().entrySet());
	}
}