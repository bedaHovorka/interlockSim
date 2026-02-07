/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import org.junit.jupiter.api.Test
import java.util.ArrayList
import java.util.Collections
import java.util.List
import java.util.Map
import java.util.Random
import java.util.TreeMap

/**
 * Test compares {@link Array2DMap} with {@link TreeMap}
 */
class Array2DMapTest {
	private val array2DMap: Array2DMap<Int> = Array2DMap()
	private val treeMap: TreeMap<Point, Int> = TreeMap(Array2DMap.POINT_COMPARATOR)
	private val BOUND: Int = 1000

	companion object {
		private val RANDOM = Random(0)
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#clear()}.
	 */
	@Test
	fun testClear() {
		EQ()
		array2DMap.clear()
		treeMap.clear()
		assertThat(array2DMap.size).isEqualTo(0)
		EQ()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#entrySet()}.
	 */
	@Test
	fun testEntrySet() {
		assertThat(array2DMap.entries).isEqualTo(treeMap.entries)
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#get(Int, Int)}.
	 */
	@Test
	fun testGetIntInt() {
		EQ()
		for (x in 0 until BOUND) {
			for (y in 0 until BOUND) {
				val p = Point(x, y)
				val integer1 = treeMap.get(p)
				val integer2 = array2DMap.get(x, y)
				assertThat(integer2).isEqualTo(integer1)
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#get(java.lang.Object)}.
	 */
	@Test
	fun testGetObject() {
		EQ()
		for (x in 0 until BOUND) {
			for (y in 0 until BOUND) {
				val p = Point(x, y)
				val integer1 = treeMap.get(p)
				val integer2 = array2DMap.get(x, y)
				assertThat(integer2).isEqualTo(integer1)
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#put(java.awt.Point, java.lang.Object)}.
	 */
	@Test
	fun testPutPointV() {
		EQ()
		for (x in 0 until BOUND) {
			val y = RANDOM.nextInt(BOUND)
			val newInt = RANDOM.nextInt()
			val p = Point(x, y)
			val integer1 = treeMap.put(p, newInt)
			val integer2 = array2DMap.put(p, newInt)
			assertThat(integer2).isEqualTo(integer1)
		}
		testEntrySet()
		EQ()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#remove(java.lang.Object)}.
	 */
	@Test
	fun testRemoveObject() {
		EQ()
		EQ()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#containsKey(java.lang.Object)}.
	 */
	@Test
	fun testContainsKeyObject() {
		EQ()
		for (x in 0 until BOUND) {
			for (y in 0 until BOUND) {
				val p = Point(x, y)
				assertThat(array2DMap.containsKey(p)).isEqualTo(treeMap.containsKey(p))
			}
		}
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.util.Array2DMap#getRow(Int)}.
	 */
	@Test
	fun testGetRow() {
		testPutPointV()
		for (y in 0 until BOUND) {
			val treeList = ArrayList<Int?>()
			for (x in 0 until BOUND) {
				val p = Point(x, y)
				val double1 = treeMap.get(p)
				treeList.add(double1)
			}
			val row = array2DMap.getRow(y)
			val tLsize = treeList.size
			val aLsize = row.size
			assertThat(tLsize).isGreaterThanOrEqualTo(aLsize)
			assertThat(row).isEqualTo(treeList.subList(0, aLsize))
			assertThat(treeList.subList(aLsize, tLsize)).isEqualTo(Collections.nCopies(tLsize - aLsize, null))
		}
		EQ()
	}

	private fun EQ() {
		assertThat(array2DMap).isEqualTo(treeMap)
	}

	/**
	 * Test method for {@link Array2DMap#POINT_COMPARATOR}
	 *
	 */
	@Test
	fun testComparator() {
		for (i in 0 until BOUND) {
			val p1 = randomPoint()
			val p2 = randomPoint()
			twoTest(p1, p2)
			val p3 = randomPoint()
			all3Variation(p1, p2, p3)
		}
	}

	private fun all3Variation(vararg points: Point) {
		// vybirame trojice (s opakovanim) z pole - EXTENSION obecna k-tice
		if (points.isEmpty()) return
		for (p1 in points) {
			for (p2 in points) {
				for (p3 in points) {
					threeTest(p1, p2, p3)
				}
			}
		}
	}

	private fun threeTest(
		p1: Point,
		p2: Point,
		p3: Point
	) {
		val c = Array2DMap.POINT_COMPARATOR
		// transitivity
		if (c.compare(p1, p2) > 0 && c.compare(p2, p3) > 0) {
			assertThat(c.compare(p1, p3)).isGreaterThan(0)
		}
		// antisymmetry (if p1 < p2 then p2 > p1)
		if (c.compare(p1, p2) < 0 && c.compare(p2, p3) < 0) {
			assertThat(c.compare(p1, p3)).isLessThan(0)
		}
	}

	private fun twoTest(
		p1: Point,
		p2: Point
	) {
		val c = Array2DMap.POINT_COMPARATOR
		val fc = Integer.signum(c.compare(p1, p2))
		if (p1 == p2) {
			assertThat(fc).isEqualTo(0)
		}
		// asymmetry
		assertThat(fc).isEqualTo(-Integer.signum(c.compare(p2, p1)))
	}

	private fun randomPoint(): Point = Point(RANDOM.nextInt(BOUND), RANDOM.nextInt(BOUND))

	private fun tst(map: Map<Point, Int>): Long {
		val keys = ArrayList<Point>()
		for (i in 0 until BOUND) {
			val randomPoint = randomPoint()
			keys.add(randomPoint)
			@Suppress("UNCHECKED_CAST")
			(map as MutableMap<Point, Int>).put(randomPoint, i)
		}
		assertThat(keys.size).isGreaterThan(0)

		val delays: MutableList<Long> = ArrayList()

		for (i in 0 until 100) {
			val before = System.nanoTime()
			for (key in keys) {
				map.get(key)
			}
			delays.add(System.nanoTime() - before)
		}
		return avg(delays as List<Long>)
	}

	private fun avg(c: List<Long>): Long {
		var sum = 0L
		for (l in c) {
			sum += l
		}
		return sum / c.size
	}

	// Note: testSpeed() performance benchmark removed in Issue #216
	// See src/jmh/kotlin/cz/vutbr/fit/interlockSim/benchmarks/GridStoragePerformance.kt
	// Run benchmarks with: ./gradlew jmh
}
