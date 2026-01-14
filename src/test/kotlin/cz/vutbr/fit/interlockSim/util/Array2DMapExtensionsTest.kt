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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for Array2DMap extension functions supporting pathfinding algorithms.
 */
class Array2DMapExtensionsTest {
	private lateinit var map: Array2DMap<String>

	@BeforeEach
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
		// Points are ordered by y first, then x
		// First should be (0,0)
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
		// Last should be (2,3)
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
		// After (0,0) should be (1,0)
		assertThat(map.higherPoint(Point(0, 0))).isEqualTo(Point(1, 0))
		// After (1,0) should be (0,1)
		assertThat(map.higherPoint(Point(1, 0))).isEqualTo(Point(0, 1))
		// After last point should be null
		assertThat(map.higherPoint(Point(2, 3))).isNull()
	}

	@Test
	fun testLowerPoint() {
		// Before (1,0) should be (0,0)
		assertThat(map.lowerPoint(Point(1, 0))).isEqualTo(Point(0, 0))
		// Before (0,1) should be (1,0)
		assertThat(map.lowerPoint(Point(0, 1))).isEqualTo(Point(1, 0))
		// Before first point should be null
		assertThat(map.lowerPoint(Point(0, 0))).isNull()
	}

	@Test
	fun testCeilingPoint() {
		// Ceiling of (0,0) is itself
		assertThat(map.ceilingPoint(Point(0, 0))).isEqualTo(Point(0, 0))
		// Ceiling of non-existent (0,2) should be (1,2)
		assertThat(map.ceilingPoint(Point(0, 2))).isEqualTo(Point(1, 2))
		// Ceiling of (3,0) should be (0,1)
		assertThat(map.ceilingPoint(Point(3, 0))).isEqualTo(Point(0, 1))
	}

	@Test
	fun testFloorPoint() {
		// Floor of (0,0) is itself
		assertThat(map.floorPoint(Point(0, 0))).isEqualTo(Point(0, 0))
		// Floor of non-existent (3,1) should be (2,1)
		assertThat(map.floorPoint(Point(3, 1))).isEqualTo(Point(2, 1))
		// Floor of (0,2) should be (2,1)
		assertThat(map.floorPoint(Point(0, 2))).isEqualTo(Point(2, 1))
	}

	@Test
	fun testSubMap() {
		// Get points in row 1
		val subMap = map.subMap(Point(0, 1), Point(0, 2))
		assertThat(subMap.keys).containsExactlyInAnyOrder(Point(0, 1), Point(1, 1), Point(2, 1))
		assertThat(subMap.values).containsExactlyInAnyOrder("C", "D", "E")
	}

	@Test
	fun testHeadMap() {
		// Get all points before (0,2)
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
		// Get all points from (1,2) onwards
		val tailMap = map.tailMap(Point(1, 2))
		assertThat(tailMap.keys).containsExactlyInAnyOrder(
			Point(1, 2),
			Point(2, 2),
			Point(3, 2),
			Point(2, 3)
		)
	}

	@Test
	fun testNeighbors4() {
		// Neighbors of D(1,1) should be A(1,0), C(0,1), E(2,1), F(1,2)
		val neighbors = map.neighbors4(Point(1, 1)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 0), // up - B
			Point(0, 1), // left - C
			Point(2, 1), // right - E
			Point(1, 2) // down - F
		)
	}

	@Test
	fun testNeighbors4Corner() {
		// Neighbors of A(0,0) should only include existing neighbors
		val neighbors = map.neighbors4(Point(0, 0)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 0), // right - B
			Point(0, 1) // down - C
		)
	}

	@Test
	fun testNeighbors8() {
		// 8-neighbors of D(1,1) including diagonals
		val neighbors = map.neighbors8(Point(1, 1)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(0, 0), // up-left - A
			Point(1, 0), // up - B
			Point(0, 1), // left - C
			Point(2, 1), // right - E
			Point(1, 2), // down - F
			Point(2, 2) // down-right - G (diagonal)
			// (2,0) doesn't exist in our map, so not included
		)
	}

	@Test
	fun testNeighbors8WithDiagonals() {
		// G(2,2) has diagonal neighbor E(2,1)
		val neighbors = map.neighbors8(Point(2, 2)).toList()
		assertThat(neighbors).containsExactlyInAnyOrder(
			Point(1, 1), // up-left - D
			Point(2, 1), // up - E
			Point(3, 2), // right - H
			Point(1, 2), // left - F
			Point(2, 3) // down - I
		)
	}

	@Test
	fun testNeighborEntries4() {
		// Neighbor entries of D(1,1)
		val entries = map.neighborEntries4(Point(1, 1)).toList()
		assertThat(entries.map { it.second }).containsExactlyInAnyOrder("B", "C", "E", "F")
	}

	@Test
	fun testNeighborEntries8() {
		// Neighbor entries of D(1,1)
		val entries = map.neighborEntries8(Point(1, 1)).toList()
		assertThat(entries.map { it.second }).containsExactlyInAnyOrder("A", "B", "C", "E", "F", "G")
	}

	@Test
	fun testPointsWithinManhattan() {
		// Points within distance 1 from D(1,1)
		val nearby = map.pointsWithinManhattan(Point(1, 1), 1).toList()
		assertThat(nearby).containsExactlyInAnyOrder(
			Point(1, 1), // self - distance 0
			Point(1, 0), // up - distance 1
			Point(0, 1), // left - distance 1
			Point(2, 1), // right - distance 1
			Point(1, 2) // down - distance 1
			// Point(0, 0) is distance 2 - excluded
		)
	}

	@Test
	fun testPointsWithinManhattanDistance2() {
		// Points within distance 2 from D(1,1)
		val nearby = map.pointsWithinManhattan(Point(1, 1), 2).toList()
		assertThat(nearby).containsExactlyInAnyOrder(
			Point(1, 1), // self - distance 0
			Point(0, 0), // distance 2
			Point(1, 0), // distance 1
			Point(0, 1), // distance 1
			Point(2, 1), // distance 1
			Point(1, 2), // distance 1
			Point(2, 2) // distance 2
			// Point(3,2) distance 3 - excluded
			// Point(2,3) distance 3 - excluded
		)
	}

	@Test
	fun testPointsInRegion() {
		// Get all points in region (0,0) to (2,2)
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
		// Get single row
		val region = map.pointsInRegion(0, 1, 2, 1).toList()
		assertThat(region).containsExactlyInAnyOrder(
			Point(0, 1),
			Point(1, 1),
			Point(2, 1)
		)
	}

	@Test
	fun testOrderedIteration() {
		// Verify that points are properly ordered (y-major, then x)
		val points = map.keys.toList()
		assertThat(points).containsExactly(
			Point(0, 0),
			Point(1, 0), // row 0
			Point(0, 1),
			Point(1, 1),
			Point(2, 1), // row 1
			Point(1, 2),
			Point(2, 2),
			Point(3, 2), // row 2
			Point(2, 3) // row 3
		)
	}
}
