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
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for Array2DMap extension functions supporting pathfinding algorithms.
 */
class Array2DMapExtensionsTest {
	private lateinit var map: Array2DMap<String>

	@BeforeTest
	fun setUp() {
		map = Array2DMap()
		// Create a 3x3 grid with some cells:
		//   0   1   2   3
		// 0 A   B   -   -
		// 1 C   D   E   -
		// 2 -   F   G   H
		// 3 -   -   I   -
		map.put(Point(0, 0), "A")
		map.put(Point(1, 0), "B")
		map.put(Point(0, 1), "C")
		map.put(Point(1, 1), "D")
		map.put(Point(2, 1), "E")
		map.put(Point(1, 2), "F")
		map.put(Point(2, 2), "G")
		map.put(Point(3, 2), "H")
		map.put(Point(2, 3), "I")
	}

	@Test
	fun testFirstPoint() {
		assertThat(map.firstPoint()).isEqualTo(Point(0, 0))
		assertThat(map[map.firstPoint()!!]).isEqualTo("A")
	}

	@Test
	fun testFirstPointEmptyMap() {
		val emptyMap = Array2DMap<String>()
		assertThat(emptyMap.firstPoint()).isNull()
	}

	@Test
	fun testLastPoint() {
		assertThat(map.lastPoint()).isEqualTo(Point(2, 3))
		assertThat(map[map.lastPoint()!!]).isEqualTo("I")
	}

	@Test
	fun testLastPointEmptyMap() {
		val emptyMap = Array2DMap<String>()
		assertThat(emptyMap.lastPoint()).isNull()
	}

	@Test
	fun testFirstEntry() {
		val entry = map.firstEntry()
		assertThat(entry).isEqualTo(map.entries.first { it.key == Point(0, 0) })
		assertThat(entry!!.value).isEqualTo("A")
	}

	@Test
	fun testLastEntry() {
		val entry = map.lastEntry()
		assertThat(entry).isEqualTo(map.entries.first { it.key == Point(2, 3) })
		assertThat(entry!!.value).isEqualTo("I")
	}

	@Test
	fun testHigherPoint() {
		assertThat(map.higherPoint(Point(0, 0))).isEqualTo(Point(1, 0))
		assertThat(map.higherPoint(Point(1, 0))).isEqualTo(Point(0, 1))
		assertThat(map.higherPoint(Point(2, 3))).isNull()
	}

	@Test
	fun testLowerPoint() {
		assertThat(map.lowerPoint(Point(1, 0))).isEqualTo(Point(0, 0))
		assertThat(map.lowerPoint(Point(0, 1))).isEqualTo(Point(1, 0))
		assertThat(map.lowerPoint(Point(0, 0))).isNull()
	}

	@Test
	fun testCeilingPoint() {
		assertThat(map.ceilingPoint(Point(0, 0))).isEqualTo(Point(0, 0))
		assertThat(map.ceilingPoint(Point(0, 2))).isEqualTo(Point(1, 2))
		assertThat(map.ceilingPoint(Point(3, 0))).isEqualTo(Point(0, 1))
	}

	@Test
	fun testFloorPoint() {
		assertThat(map.floorPoint(Point(0, 0))).isEqualTo(Point(0, 0))
		assertThat(map.floorPoint(Point(3, 1))).isEqualTo(Point(2, 1))
		assertThat(map.floorPoint(Point(0, 2))).isEqualTo(Point(2, 1))
	}

	@Test
	fun testSubMap() {
		val subMap = map.subMap(Point(0, 1), Point(0, 2))
		assertThat(subMap.keys).containsExactlyInAnyOrder(Point(0, 1), Point(1, 1), Point(2, 1))
		assertThat(subMap.values).containsExactlyInAnyOrder("C", "D", "E")
	}

	@Test
	fun testHeadMap() {
		val headMap = map.headMap(Point(0, 2))
		assertThat(headMap.keys).containsExactlyInAnyOrder(
			Point(0, 0),
			Point(1, 0),
			Point(0, 1),
			Point(1, 1),
			Point(2, 1)
		)
	}

	@Test
	fun testTailMap() {
		val tailMap = map.tailMap(Point(1, 2))
		assertThat(tailMap.keys).containsExactlyInAnyOrder(
			Point(1, 2),
			Point(2, 2),
			Point(3, 2),
			Point(2, 3)
		)
	}

	// Expanded @CsvSource: neighbors4 at D(1,1) returns 4 neighbors
	@Test
	fun `neighbors4 at D 1-1 returns 4 neighbors`() {
		val neighbors = map.neighbors4(Point(1, 1)).toList()
		assertThat(neighbors.size).isEqualTo(4)
	}

	// Expanded @CsvSource: neighbors4 at A(0,0) returns 2 neighbors
	@Test
	fun `neighbors4 at A 0-0 returns 2 neighbors`() {
		val neighbors = map.neighbors4(Point(0, 0)).toList()
		assertThat(neighbors.size).isEqualTo(2)
	}

	// Expanded @CsvSource: neighbors4 at G(2,2) returns 4 neighbors
	@Test
	fun `neighbors4 at G 2-2 returns 4 neighbors`() {
		val neighbors = map.neighbors4(Point(2, 2)).toList()
		assertThat(neighbors.size).isEqualTo(4)
	}

	// Expanded @CsvSource: neighbors8 at D(1,1) returns 6 neighbors
	@Test
	fun `neighbors8 at D 1-1 returns 6 neighbors`() {
		val neighbors = map.neighbors8(Point(1, 1)).toList()
		assertThat(neighbors.size).isEqualTo(6)
	}

	// Expanded @CsvSource: neighbors8 at A(0,0) returns 3 neighbors
	@Test
	fun `neighbors8 at A 0-0 returns 3 neighbors`() {
		val neighbors = map.neighbors8(Point(0, 0)).toList()
		assertThat(neighbors.size).isEqualTo(3)
	}

	// Expanded @CsvSource: neighbors8 at G(2,2) returns 5 neighbors
	@Test
	fun `neighbors8 at G 2-2 returns 5 neighbors`() {
		val neighbors = map.neighbors8(Point(2, 2)).toList()
		assertThat(neighbors.size).isEqualTo(5)
	}

	@Test
	fun testNeighbors4() {
		val neighbors = map.neighbors4(Point(1, 1)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 0),
			Point(0, 1),
			Point(2, 1),
			Point(1, 2)
		)
	}

	@Test
	fun testNeighbors4Corner() {
		val neighbors = map.neighbors4(Point(0, 0)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 0),
			Point(0, 1)
		)
	}

	@Test
	fun testNeighbors8() {
		val neighbors = map.neighbors8(Point(1, 1)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(0, 0),
			Point(1, 0),
			Point(0, 1),
			Point(2, 1),
			Point(1, 2),
			Point(2, 2)
		)
	}

	@Test
	fun testNeighbors8WithDiagonals() {
		val neighbors = map.neighbors8(Point(2, 2)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 1),
			Point(2, 1),
			Point(3, 2),
			Point(1, 2),
			Point(2, 3)
		)
	}

	// Expanded @CsvSource: neighborEntries4 at D(1,1) returns 4 entries
	@Test
	fun `neighborEntries4 at D 1-1 returns 4 entries`() {
		val entries = map.neighborEntries4(Point(1, 1)).toList()
		assertThat(entries.size).isEqualTo(4)
	}

	// Expanded @CsvSource: neighborEntries8 at D(1,1) returns 6 entries
	@Test
	fun `neighborEntries8 at D 1-1 returns 6 entries`() {
		val entries = map.neighborEntries8(Point(1, 1)).toList()
		assertThat(entries.size).isEqualTo(6)
	}

	@Test
	fun testNeighborEntries4() {
		val entries = map.neighborEntries4(Point(1, 1)).toList()
		assertThat(entries.map { it.second }).containsExactlyInAnyOrder("B", "C", "E", "F")
	}

	@Test
	fun testNeighborEntries8() {
		val entries = map.neighborEntries8(Point(1, 1)).toList()
		assertThat(entries.map { it.second }).containsExactlyInAnyOrder("A", "B", "C", "E", "F", "G")
	}

	@Test
	fun testPointsWithinManhattan() {
		val nearby = map.pointsWithinManhattan(Point(1, 1), 1).toList()
		assertThat(nearby).containsExactlyInAnyOrder(
			Point(1, 1),
			Point(1, 0),
			Point(0, 1),
			Point(2, 1),
			Point(1, 2)
		)
	}

	@Test
	fun testPointsWithinManhattanDistance2() {
		val nearby = map.pointsWithinManhattan(Point(1, 1), 2).toList()
		assertThat(nearby).containsExactlyInAnyOrder(
			Point(1, 1),
			Point(0, 0),
			Point(1, 0),
			Point(0, 1),
			Point(2, 1),
			Point(1, 2),
			Point(2, 2)
		)
	}

	@Test
	fun testPointsInRegion() {
		val region = map.pointsInRegion(0, 0, 2, 2).toList()
		assertThat(region).containsExactlyInAnyOrder(
			Point(0, 0),
			Point(1, 0),
			Point(0, 1),
			Point(1, 1),
			Point(2, 1),
			Point(1, 2),
			Point(2, 2)
		)
	}

	@Test
	fun testPointsInRegionSmall() {
		val region = map.pointsInRegion(0, 1, 2, 1).toList()
		assertThat(region).containsExactlyInAnyOrder(
			Point(0, 1),
			Point(1, 1),
			Point(2, 1)
		)
	}

	@Test
	fun testOrderedIteration() {
		val points = map.keys.toList()
		assertThat(points).containsExactly(
			Point(0, 0),
			Point(1, 0),
			Point(0, 1),
			Point(1, 1),
			Point(2, 1),
			Point(1, 2),
			Point(2, 2),
			Point(3, 2),
			Point(2, 3)
		)
	}
}
