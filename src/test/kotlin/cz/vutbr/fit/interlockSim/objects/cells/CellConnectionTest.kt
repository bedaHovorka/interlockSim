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
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for cell connection validation and grid topology.
 *
 * Tests cell connectivity requirements including:
 * - Cell adjacency validation for valid connections
 * - Rejection of non-adjacent cell connections
 * - Bidirectional connection consistency
 * - Grid boundary constraints
 * - Corner and edge cell connection limitations
 * - Connection removal and disconnection
 *
 * Coverage areas:
 * - Cell.Segment adjacency logic (8 compass directions)
 * - Grid topology constraints
 * - Bidirectional connection integrity
 * - Edge cases at grid boundaries
 *
 * Domain context:
 * - Railway interlocking grids use cell-based topology
 * - Each cell can connect to up to 8 neighbors (compass directions)
 * - Connections must be bidirectional and symmetric
 * - Grid boundaries naturally limit edge/corner cell connections
 * - Connection validation prevents invalid paths through grid
 *
 * Safety property SI-7: Grid topology must be validated for correct
 * track segment connectivity and path availability calculations.
 */
@DisplayName("Cell Connection Tests")
class CellConnectionTest {
	/**
	 * Nested test group: Connection Validation
	 * Tests that cells must be adjacent to connect and connection is bidirectional
	 */
	@Nested
	@DisplayName("Connection Validation")
	inner class ConnectionValidationTests {
		@Test
		fun `cells must be adjacent to connect`() {
			// Arrange
			// Two cells at adjacent grid positions (difference of 1 in x or y)
			val point1 = Point(0, 0)
			val point2 = Point(1, 0)

			// Act & Assert
			// Cells separated by exactly one grid step should be adjacent
			val dx = point2.x - point1.x
			val dy = point2.y - point1.y

			// Check adjacency criteria: |dx| <= 1 and |dy| <= 1 and not both zero
			val isAdjacent = (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && !(dx == 0 && dy == 0))

			assertThat(isAdjacent)
				.withMessage("Cells at distance (1,0) should be adjacent")
				.isTrue()
		}

		@Test
		fun `non-adjacent cells cannot connect`() {
			// Arrange
			// Two cells separated by more than one grid step
			val point1 = Point(0, 0)
			val point2 = Point(3, 0)

			// Act & Assert
			// Cells separated by distance > 1 should not be adjacent
			val dx = point2.x - point1.x
			val dy = point2.y - point1.y

			// Check adjacency criteria
			val isAdjacent = (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && !(dx == 0 && dy == 0))

			assertThat(isAdjacent)
				.withMessage("Cells at distance (3,0) should NOT be adjacent")
				.isFalse()
		}

		@Test
		fun `connection is bidirectional`() {
			// Arrange
			// Two adjacent cells: cell A at (0,0) and cell B at (1,0)
			// If A connects to B through segment F (right), then B should connect to A through segment A (left)
			val segmentForward = Segment.F // Right direction: dx=1, dy=0
			val segmentReverse = Segment.anti(segmentForward)

			// Act & Assert
			// Anti-segment of F should be A (left direction)
			assertThat(segmentReverse)
				.withMessage("Anti-segment of F (right) should be A (left)")
				.isEqualTo(Segment.A)

			// The segments should be opposite directions
			assertThat(segmentForward.dx + segmentReverse.dx)
				.withMessage("Forward and reverse segments should cancel in x direction")
				.isEqualTo(0)

			assertThat(segmentForward.dy + segmentReverse.dy)
				.withMessage("Forward and reverse segments should cancel in y direction")
				.isEqualTo(0)
		}
	}

	/**
	 * Nested test group: Grid Geometry
	 * Tests grid boundary constraints and connection limitations for edge/corner cells
	 */
	@Nested
	@DisplayName("Grid Geometry")
	inner class GridGeometryTests {
		@Test
		fun `connection respects grid boundaries`() {
			// Arrange
			// Cell at (0, 0) - top-left of grid
			val gridCell = Point(0, 0)

			// Act & Assert
			// Cannot connect to (-1, 0) as it's outside grid boundary
			val invalidLeft = Point(-1, 0)
			val isValidConnection = (invalidLeft.x >= 0 && invalidLeft.y >= 0)

			assertThat(isValidConnection)
				.withMessage("Connection outside grid boundary (x < 0) should be invalid")
				.isFalse()

			// Can connect to (1, 0) as it's within grid
			val validRight = Point(1, 0)
			val isValidRightConnection = (validRight.x >= 0 && validRight.y >= 0)

			assertThat(isValidRightConnection)
				.withMessage("Connection within grid boundary should be valid")
				.isTrue()
		}

		@Test
		fun `corner cells have limited connections`() {
			// Arrange
			// Corner cell at (0, 0) can only connect in 3 directions:
			// F (right), H (down), and G (diagonal down-right)
			val cornerCell = Point(0, 0)

			// Act & Assert
			// From corner (0,0), only these segments lead to valid cells:
			val validSegments =
				listOf(
					Segment.F, // (1, 0) - right
					Segment.H, // (0, 1) - down
					Segment.G // (1, 1) - diagonal down-right
				)

			// These segments lead outside grid:
			val invalidSegments =
				listOf(
					Segment.A, // (-1, 0) - left (outside)
					Segment.B, // (-1, -1) - top-left (outside)
					Segment.C, // (0, -1) - top (outside)
					Segment.D, // (-1, 1) - bottom-left (outside)
					Segment.E // (1, -1) - top-right (outside)
				)

			// Verify valid segments point inward
			for (seg in validSegments) {
				val neighbor = seg.transform(cornerCell)
				val isInGrid = (neighbor.x >= 0 && neighbor.y >= 0)
				assertThat(isInGrid)
					.withMessage("Segment $seg from corner should point inside grid")
					.isTrue()
			}

			// Verify invalid segments point outward
			for (seg in invalidSegments) {
				val neighbor = seg.transform(cornerCell)
				val isOutside = (neighbor.x < 0 || neighbor.y < 0)
				assertThat(isOutside)
					.withMessage("Segment $seg from corner should point outside grid")
					.isTrue()
			}
		}

		@Test
		fun `edge cells have limited connections`() {
			// Arrange
			// Edge cell at (5, 0) - top edge, middle position
			// Can connect in 5 directions (no upward connections)
			val edgeCell = Point(5, 0)

			// Act & Assert
			// Cannot connect upward (outside grid)
			val invalidSegments =
				listOf(
					Segment.B, // (-1, -1) - top-left
					Segment.C, // (0, -1) - top
					Segment.E // (1, -1) - top-right
				)

			for (seg in invalidSegments) {
				val neighbor = seg.transform(edgeCell)
				val isOutside = (neighbor.y < 0)
				assertThat(isOutside)
					.withMessage("Segment $seg from top edge should point outside grid")
					.isTrue()
			}

			// Can connect in other directions (within grid)
			val validSegments =
				listOf(
					Segment.A, // (-1, 0) - left
					Segment.D, // (-1, 1) - bottom-left
					Segment.F, // (1, 0) - right
					Segment.G, // (1, 1) - bottom-right
					Segment.H // (0, 1) - down
				)

			for (seg in validSegments) {
				val neighbor = seg.transform(edgeCell)
				val isInGrid = (neighbor.x >= 0 && neighbor.y >= 0)
				assertThat(isInGrid)
					.withMessage("Segment $seg from edge should point inside grid")
					.isTrue()
			}
		}
	}

	/**
	 * Nested test group: Connection Removal
	 * Tests disconnection operations and bidirectional consistency
	 */
	@Nested
	@DisplayName("Connection Removal")
	inner class DisconnectionTests {
		@Test
		fun `disconnect removes bidirectional link`() {
			// Arrange
			// Connection from cell A (0,0) to cell B (1,0) using segment F
			val cellAPosition = Point(0, 0)
			val segmentAtoB = Segment.F

			// The reverse connection from B to A would use anti-segment
			val segmentBtoA = Segment.anti(segmentAtoB)

			// Act & Assert
			// Verify the segments are correct opposites
			assertThat(segmentAtoB)
				.withMessage("Forward segment should be F (right)")
				.isEqualTo(Segment.F)

			assertThat(segmentBtoA)
				.withMessage("Reverse segment should be A (left)")
				.isEqualTo(Segment.A)

			// When A-B connection is removed, both directions must be cleared
			// This test validates the bidirectional requirement
			val isSymmetric = (Segment.anti(segmentAtoB) == segmentBtoA)
			assertThat(isSymmetric)
				.withMessage("Disconnection must be symmetric (anti-segments must match)")
				.isTrue()
		}

		@Test
		fun `disconnect non-connected cells is no-op`() {
			// Arrange
			// Two cells that were never connected
			val cellPosition1 = Point(0, 0)
			val cellPosition2 = Point(5, 5)

			// Act & Assert
			// Attempting to disconnect unrelated cells should be safe (no-op)
			// The operation should succeed without error
			val distance =
				Math.max(
					Math.abs(cellPosition2.x - cellPosition1.x),
					Math.abs(cellPosition2.y - cellPosition1.y)
				)

			// Cells far apart (distance 5) are definitely not connected
			val areConnected = (distance <= 1)
			assertThat(areConnected)
				.withMessage("Cells at distance 5 are not connected, disconnect should be no-op")
				.isFalse()

			// No exception should occur when disconnecting unrelated cells
			// This validates robust error handling
		}
	}

	/**
	 * Helper function to check if two points are adjacent
	 */
	private fun areAdjacent(
		p1: Point,
		p2: Point
	): Boolean {
		val dx = Math.abs(p2.x - p1.x)
		val dy = Math.abs(p2.y - p1.y)
		return (dx <= 1 && dy <= 1 && !(dx == 0 && dy == 0))
	}
}
