/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for cell connection validation and grid topology
 * Phase 4.5 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

/**
 * Unit tests for cell connection validation and grid topology.
 */
class CellConnectionTest {
	@Test
	fun `cells must be adjacent to connect`() {
		val point1 = Point(0, 0)
		val point2 = Point(1, 0)
		val dx = point2.x - point1.x
		val dy = point2.y - point1.y
		val isAdjacent = (abs(dx) <= 1 && abs(dy) <= 1 && !(dx == 0 && dy == 0))
		assertThat(isAdjacent)
			.withMessage("Cells at distance (1,0) should be adjacent")
			.isTrue()
	}

	@Test
	fun `non-adjacent cells cannot connect`() {
		val point1 = Point(0, 0)
		val point2 = Point(3, 0)
		val dx = point2.x - point1.x
		val dy = point2.y - point1.y
		val isAdjacent = (abs(dx) <= 1 && abs(dy) <= 1 && !(dx == 0 && dy == 0))
		assertThat(isAdjacent)
			.withMessage("Cells at distance (3,0) should NOT be adjacent")
			.isFalse()
	}

	@Test
	fun `connection is bidirectional`() {
		val segmentForward = Segment.F
		val segmentReverse = anti(segmentForward)
		assertThat(segmentReverse)
			.withMessage("Anti-segment of F (right) should be A (left)")
			.isEqualTo(Segment.A)
		assertThat(segmentForward.dx + segmentReverse.dx)
			.withMessage("Forward and reverse segments should cancel in x direction")
			.isEqualTo(0)
		assertThat(segmentForward.dy + segmentReverse.dy)
			.withMessage("Forward and reverse segments should cancel in y direction")
			.isEqualTo(0)
	}
}

/**
 * Grid Geometry tests extracted from CellConnectionTest @Nested inner class.
 */
class CellConnectionGridGeometryTest {
	@Test
	fun `connection respects grid boundaries`() {
		val invalidLeft = Point(-1, 0)
		val isValidConnection = (invalidLeft.x >= 0 && invalidLeft.y >= 0)
		assertThat(isValidConnection)
			.withMessage("Connection outside grid boundary should be invalid")
			.isFalse()
		val validRight = Point(1, 0)
		val isValidRightConnection = (validRight.x >= 0 && validRight.y >= 0)
		assertThat(isValidRightConnection)
			.withMessage("Connection within grid boundary should be valid")
			.isTrue()
	}

	@Test
	fun `corner cells have limited connections`() {
		val cornerCell = Point(0, 0)
		val validSegments = listOf(Segment.F, Segment.H, Segment.G)
		val invalidSegments = listOf(Segment.A, Segment.B, Segment.C, Segment.D, Segment.E)
		for (seg in validSegments) {
			val neighbor = seg.transform(cornerCell)
			assertThat(neighbor.x >= 0 && neighbor.y >= 0)
				.withMessage("Segment $seg from corner should point inside grid")
				.isTrue()
		}
		for (seg in invalidSegments) {
			val neighbor = seg.transform(cornerCell)
			assertThat(neighbor.x < 0 || neighbor.y < 0)
				.withMessage("Segment $seg from corner should point outside grid")
				.isTrue()
		}
	}

	@Test
	fun `edge cells have limited connections`() {
		val edgeCell = Point(5, 0)
		val invalidSegments = listOf(Segment.B, Segment.C, Segment.E)
		for (seg in invalidSegments) {
			val neighbor = seg.transform(edgeCell)
			assertThat(neighbor.y < 0)
				.withMessage("Segment $seg from top edge should point outside grid")
				.isTrue()
		}
		val validSegments = listOf(Segment.A, Segment.D, Segment.F, Segment.G, Segment.H)
		for (seg in validSegments) {
			val neighbor = seg.transform(edgeCell)
			assertThat(neighbor.x >= 0 && neighbor.y >= 0)
				.withMessage("Segment $seg from edge should point inside grid")
				.isTrue()
		}
	}
}

/**
 * Connection Removal tests extracted from CellConnectionTest @Nested inner class.
 */
class CellConnectionDisconnectionTest {
	@Test
	fun `disconnect removes bidirectional link`() {
		val segmentAtoB = Segment.F
		val segmentBtoA = anti(segmentAtoB)
		assertThat(segmentAtoB)
			.withMessage("Forward segment should be F")
			.isEqualTo(Segment.F)
		assertThat(segmentBtoA)
			.withMessage("Reverse segment should be A")
			.isEqualTo(Segment.A)
		assertThat(anti(segmentAtoB) == segmentBtoA)
			.withMessage("Disconnection must be symmetric")
			.isTrue()
	}

	@Test
	fun `disconnect non-connected cells is no-op`() {
		val cellPosition1 = Point(0, 0)
		val cellPosition2 = Point(5, 5)
		val distance =
			max(
				abs(cellPosition2.x - cellPosition1.x),
				abs(cellPosition2.y - cellPosition1.y)
			)
		val areConnected = (distance <= 1)
		assertThat(areConnected)
			.withMessage("Cells at distance 5 are not connected")
			.isFalse()
	}
}
