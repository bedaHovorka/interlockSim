/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import junit.framework.TestCase;
import cz.vutbr.fit.interlockSim.util.Array2DMap;

/**
 * Test compares {@link Array2DMap} with {@link TreeMap}
 */
public class TestArray2DMap extends TestCase {
	private static final Random RANDOM = new Random(0);
	private final Array2DMap<Integer> array2DMap = new Array2DMap<Integer>();
	private final TreeMap<Point, Integer> treeMap = new TreeMap<Point, Integer>(Array2DMap.POINT_COMPARATOR);
	private final int BOUND = 1000;

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#clear()}.
	 */
	public void testClear() {
		EQ();
		array2DMap.clear();
		treeMap.clear();
		assertEquals(0, array2DMap.size());
		EQ();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#entrySet()}.
	 */
	public void testEntrySet() {
		assertEquals(treeMap.entrySet(), array2DMap.entrySet());
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#get(int, int)}.
	 */
	public void testGetIntInt() {
		EQ();
		final Point referencer = new Point();
		for (int x = 0; x < BOUND; x++) {
			for (int y = 0; y < BOUND; y++) {
				referencer.x = x; referencer.y = y;
				final Integer integer1 = treeMap.get(referencer);
				final Integer integer2 = array2DMap.get(x,y);
				assertEquals(integer1, integer2);
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#get(java.lang.Object)}.
	 */
	public void testGetObject() {
		EQ();
		final Point referencer = new Point();
		for (int x = 0; x < BOUND; x++) {
			for (int y = 0; y < BOUND; y++) {
				referencer.x = x; referencer.y = y;
				final Integer integer1 = treeMap.get(referencer);
				final Integer integer2 = array2DMap.get(x,y);
				assertEquals(integer1, integer2);
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#put(java.awt.Point, java.lang.Object)}.
	 */
	public void testPutPointV() {
		EQ();
		final Point referencer = new Point();
		for (int x = 0; x < BOUND; x++) {
			final int y = RANDOM.nextInt(BOUND);
			final Integer newInt = new Integer(RANDOM.nextInt());
			referencer.x = x; referencer.y = y;
			final Point p = (Point) referencer.clone();
			final Integer integer1 = treeMap.put(p, newInt);
			final Integer integer2 = array2DMap.put(p, newInt);
			assertEquals(integer1, integer2);
		}
		testEntrySet();
		EQ();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#remove(java.lang.Object)}.
	 */
	public void testRemoveObject() {
		EQ();
		EQ();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#containsKey(java.lang.Object)}.
	 */
	public void testContainsKeyObject() {
		EQ();
		final Point referencer = new Point();
		for (int x = 0; x < BOUND; x++) {
			for (int y = 0; y < BOUND; y++) {
				referencer.x = x; referencer.y = y;
				assertEquals(treeMap.containsKey(referencer), array2DMap.containsKey(referencer));
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#getRow(int)}.
	 */
	public void testGetRow() {
		testPutPointV();
		final Point referencer = new Point();
		for (int y = 0; y < BOUND; y++) {
			final ArrayList<Integer> treeList = new ArrayList<Integer>();
			for (int x = 0; x < BOUND; x++) {
				referencer.x = x; referencer.y = y;
				final Integer double1 = treeMap.get(referencer);
				treeList.add(double1);
			}
			final List<Integer> row = array2DMap.getRow(y);
			final int tLsize = treeList.size();
			final int aLsize = row.size(); assert tLsize >= aLsize;
			assertEquals(treeList.subList(0, aLsize), row);
			assertEquals(Collections.nCopies(tLsize - aLsize, null), treeList.subList(aLsize, tLsize));
		}
		EQ();
	}

	private void EQ() {
		assertEquals(treeMap, array2DMap);
	}

	/**
	 * Test method for {@link Array2DMap#POINT_COMPARATOR}
	 *
	 */
	public void testComparator() {
		for (int i = 0; i < BOUND; i++) {
			final Point p1 = randomPoint();
			final Point p2 = randomPoint();
			twoTest(p1, p2);
			final Point p3 = randomPoint();
			all3Variation(p1, p2, p3);
		}
	}

	private void all3Variation(Point... points) {
		//vybirame trojice (s opakovanim) z pole - EXTENSION obecna k-tice
		if (points == null || points.length == 0) return;
		for (Point p1 : points) {
			for (Point p2 : points) {
				for (Point p3 : points) {
					threeTest(p1, p2, p3);
				}
			}
		}
	}

	private void threeTest(Point p1, Point p2, Point p3) {
		final Comparator<Point> c = Array2DMap.POINT_COMPARATOR;
		//		transitivity
		if (c.compare(p1, p2) > 0 && c.compare(p2, p3) > 0) assertTrue(c.compare(p1, p3) > 0);
		else if (c.compare(p1, p2) < 0 && c.compare(p2, p3) < 0) assertTrue(c.compare(p1, p3) < 0);
		else if (c.compare(p1, p2) == 0 && c.compare(p2, p3) == 0) assertTrue(c.compare(p1, p3) == 0);
	}

	private void twoTest(Point p1, Point p2) {
		final Comparator<Point> c = Array2DMap.POINT_COMPARATOR;
		final int fc = Integer.signum(c.compare(p1, p2));
		if (p1.equals(p2)) assertTrue(fc == 0);
		//		asymetry
		assertTrue(fc == -Integer.signum(c.compare(p2, p1)));
	}

	private Point randomPoint() {
		return new Point(RANDOM.nextInt(BOUND), RANDOM.nextInt(BOUND));
	}

	private long tst(Map<Point, Integer> map) {
		final ArrayList<Point> keys = new ArrayList<Point>();
		for (int i = 0; i < BOUND; i++) {
			final Point randomPoint = randomPoint();
			keys.add(randomPoint);
			map.put(randomPoint, i);
		}
		assert keys.size() > 0;

		final ArrayList<Long> delays = new ArrayList<Long>();

		for (int i = 0; i < 100; i++) {
			final long before = System.nanoTime();
			for (Point key : keys) {
				map.get(key);
			}
			delays.add(System.nanoTime() - before);
		}
		return avg(delays);
	}

	private long avg(Collection<Long> c) {
		long sum = 0;
		for (Long l : c) {
			sum += l.longValue();
		}
		return sum / c.size();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap}.
	 */
	public void testSpeed() {
		final long tst1 = tst(array2DMap);
		final long tst2 = tst(treeMap);
		//cil: zrychleni operace vyhledani polozky
		System.out.println(tst1 + " "+ tst2);
		assertTrue(tst1 < tst2);
		//zaver: zrychleni neni skoro zadne
		//vsak je aspon pri volani primo get(int,int) eliminovano vytvareni klicovacich objektu
	}
}
