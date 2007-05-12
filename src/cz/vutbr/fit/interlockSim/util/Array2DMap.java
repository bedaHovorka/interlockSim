/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

import java.awt.Point;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.TreeSet;

/**
 * ADT for grid
 * @param <V> type of values
 *
 */
public class Array2DMap<V> extends AbstractMap<Point, V> /* EXTENSION implements NavigableMap<Point, V> */{
	private final class Entry implements Map.Entry<Point, V> {
		private final Point key;
		/**
		 * Create new entry
		 * @param key
		 */
		public Entry(final Point key) {
			super();
			this.key = key;
		}

		public Point getKey() {
			return key;
		}

		public V getValue() {
			return Array2DMap.this.get(key);
		}

		public V setValue(V value) {
			return Array2DMap.this.put(key, value);
		}
		
	}
	
	private final class Array2DEntrySet extends AbstractSet<Map.Entry<Point, V>> {

		private final class Array2DIterator implements Iterator<Map.Entry<Point, V>> {
			private final Iterator<Point> iterator = keys.iterator();
			private Point current;

			public boolean hasNext() {
				return iterator.hasNext();
			}

			public Entry next() {
				current = iterator.next();
				return new Entry(current);
			}

			public void remove() {
				if (current == null) throw new IllegalStateException();
				Array2DMap.this.remove(current);
				current = null;
			}
		}

		@Override
		public Iterator<Map.Entry<Point, V>> iterator() {
			return new Array2DIterator();
		}

		@Override
		public int size() {
			return keys.size();
		}

		
	}
	
	//seznam s dirama
	private class RelocableList<T> extends AbstractList<T> implements RandomAccess {
		private transient T[] elements;
		
		@Override
		public T get(int index) {
			if (elements == null || index >= elements.length || index < 0) return null;
			return elements[index];
		}
		
		@Override
		public T set(int index, T element) {
			if (index < 0) throw new IndexOutOfBoundsException();
			boolean resize = elements==null || index>=elements.length;
			T prev = resize ? null : elements[index];
			if (resize) {
				Object[] p = new Object[index+1];
				if (elements != null) System.arraycopy(elements, 0, p, 0, elements.length);
				elements = (T[]) p;
			}
			elements[index] = element;
			return prev;
		}

		@Override
		public T remove(int index) {
			if (elements == null || index >= elements.length || index < 0) return null;
			T prev = elements[index];
			elements[index] = null;
			return prev;
		}
		
		@Override
		public int size() {
			return elements==null ? 0 : elements.length;
		}
		
	}

	private static final class PointComparator implements Comparator<Point> {
		public int compare(Point o1, Point o2) {
			int dy = (o1.y-o2.y);
			return dy==0 ? (o1.x-o2.x) : dy;
		}
	}
	/**
	 * Compare points in order for grid
	 */
	public static final Comparator<Point> POINT_COMPARATOR = new PointComparator();
	
	private final RelocableList<RelocableList<V>> array = new RelocableList<RelocableList<V>> ();
	private final TreeSet<Point> keys = new TreeSet<Point>(POINT_COMPARATOR);

	/* (non-Javadoc)
	 * @see java.util.AbstractMap#entrySet()
	 */
	@Override
	public Set<Map.Entry<Point, V>> entrySet() {
		return new Array2DEntrySet();
	}

	/**
	 * 
	 * @param x column
	 * @param y row
	 * @return element in map or null if 
	 */
	public V get(int x, int y) {
		RelocableList<V> iArray = array.get(y);
		if (iArray == null) return null;
		return iArray.get(x);
	}
	
	@Override
	public V get(Object key) {
		if (!(key instanceof Point)) return null;
		Point p = (Point) key;
		return get(p.x, p.y);
	}
	
	@Override
	public V put(Point key, V value) {
		RelocableList<V> iArray = array.get(key.y);
		if (iArray == null) {
			iArray = new RelocableList<V>();
			array.set(key.y, iArray);
		}
		keys.add(key);
		return iArray.set(key.x, value);
	}
	
	@Override
	public V remove(Object key) {
		keys.remove(key);
		if (!(key instanceof Point)) return null;
		Point p = (Point) key;
		RelocableList<V> iArray = array.get(p.y);
		if (iArray == null) return null;
		return iArray.remove(p.x);
	}
	
	@Override
	public boolean containsKey(Object key) {
		return keys.contains(key);
	}
	
	/**
	 * @param y
	 * @return elements at row
	 */
	public List<V> getRow(int y) {
		final RelocableList<V> list = array.get(y);
		//EXTENSION zatim unmodifieable
		return list==null ? Collections.<V>emptyList() : Collections.unmodifiableList(list);
	}
	
	@Override
	public void clear() {
		keys.clear();
		array.clear();
	}
}
