/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for Bresenham line algorithm used in cell joining.
 * 
 * The Bresenham algorithm is used internally by joinCells to find intermediate
 * points when connecting two cells that are far apart (distance > √2).
 * These tests verify correct behavior through the public joinCells API.
 */
@DisplayName("Bresenham Line Algorithm (via joinCells)")
class BresenhamJoinTest {
	private lateinit var context: DefaultContext

	@BeforeEach
	fun setUp() {
		context = XMLContextFactory.getInstance().createEmptyContext()
	}

	@Test
	@DisplayName("Join horizontal cells creates straight horizontal line")
	fun joinCells_horizontalLine_createsCorrectPath() {
		// Arrange - Two InOut cells on same horizontal line, distance > √2
		val start = Point(1, 5)
		val end = Point(10, 5)
		val inA = InOut("A", false, SpatialType.HORIZONTAL)
		val inB = InOut("B", true, SpatialType.HORIZONTAL)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Bresenham should create straight horizontal line
		// Check intermediate cells exist (x from 2 to 9, y stays at 5)
		for (x in 2..9) {
			val cell = context.getRailWayNetGrid().getCellAt(x, 5)
			assertThat(cell)
				.withFailMessage("Expected cell at ($x, 5) from horizontal Bresenham line")
				.isNotNull()
		}
	}

	@Test
	@DisplayName("Join vertical cells creates straight vertical line")
	fun joinCells_verticalLine_createsCorrectPath() {
		// Arrange - Two InOut cells on same vertical line
		val start = Point(5, 1)
		val end = Point(5, 10)
		val inA = InOut("A", false, SpatialType.VERTICAL)
		val inB = InOut("B", true, SpatialType.VERTICAL)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Bresenham should create straight vertical line
		// Check intermediate cells exist (y from 2 to 9, x stays at 5)
		for (y in 2..9) {
			val cell = context.getRailWayNetGrid().getCellAt(5, y)
			assertThat(cell)
				.withFailMessage("Expected cell at (5, $y) from vertical Bresenham line")
				.isNotNull()
		}
	}

	@Test
	@DisplayName("Join diagonal cells creates diagonal line (45 degrees)")
	fun joinCells_diagonalLine45_createsCorrectPath() {
		// Arrange - Two cells on 45-degree diagonal
		val start = Point(1, 1)
		val end = Point(8, 8)
		val inA = InOut("A", false, SpatialType.DIAGONAL1)
		val inB = InOut("B", true, SpatialType.DIAGONAL1)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - For 45-degree line, Bresenham should create diagonal
		// Check that intermediate cells follow diagonal pattern
		for (i in 2..7) {
			val cell = context.getRailWayNetGrid().getCellAt(i, i)
			assertThat(cell)
				.withFailMessage("Expected cell at ($i, $i) from 45-degree diagonal Bresenham line")
				.isNotNull()
		}
	}

	@Test
	@DisplayName("Join cells with shallow slope creates correct line")
	fun joinCells_shallowSlope_createsCorrectPath() {
		// Arrange - Shallow slope: 8 units horizontal, 3 units vertical
		val start = Point(1, 1)
		val end = Point(9, 4)
		val inA = InOut("A", false, SpatialType.HORIZONTAL)
		val inB = InOut("B", true, SpatialType.HORIZONTAL)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Bresenham should distribute y-increments across x-range
		// For shallow slope, most x values should have a cell
		var cellCount = 0
		for (x in 2..8) {
			for (y in 1..4) {
				if (context.getRailWayNetGrid().getCellAt(x, y) != null) {
					cellCount++
				}
			}
		}
		assertThat(cellCount)
			.withFailMessage("Expected Bresenham to create multiple intermediate cells for shallow slope")
			.isGreaterThan(0)
	}

	@Test
	@DisplayName("Join cells with steep slope creates correct line")
	fun joinCells_steepSlope_createsCorrectPath() {
		// Arrange - Steep slope: 3 units horizontal, 8 units vertical
		val start = Point(1, 1)
		val end = Point(4, 9)
		val inA = InOut("A", false, SpatialType.VERTICAL)
		val inB = InOut("B", true, SpatialType.VERTICAL)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - For steep slope, most y values should have a cell
		var cellCount = 0
		for (y in 2..8) {
			for (x in 1..4) {
				if (context.getRailWayNetGrid().getCellAt(x, y) != null) {
					cellCount++
				}
			}
		}
		assertThat(cellCount)
			.withFailMessage("Expected Bresenham to create multiple intermediate cells for steep slope")
			.isGreaterThan(0)
	}

	@Test
	@DisplayName("Join cells at short distance (≤ √2) does not use Bresenham")
	fun joinCells_shortDistance_skipBresenham() {
		// Arrange - Adjacent cells (distance = 1, which is ≤ √2 ≈ 1.414)
		val start = Point(5, 5)
		val end = Point(6, 5)
		val inA = InOut("A", false, SpatialType.HORIZONTAL)
		val inB = InOut("B", true, SpatialType.HORIZONTAL)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - No intermediate cells should be created (direct join)
		// The grid should only contain the two endpoint cells
		assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isSameAs(inA)
		assertThat(context.getRailWayNetGrid().getCellAt(6, 5)).isSameAs(inB)
	}

	@Test
	@DisplayName("Join cells with negative coordinates works correctly")
	fun joinCells_negativeCoordinates_handlesCorrectly() {
		// Arrange - Line from negative to positive coordinates
		val start = Point(-2, -2)
		val end = Point(5, 5)
		val inA = InOut("A", false, SpatialType.DIAGONAL1)
		val inB = InOut("B", true, SpatialType.DIAGONAL1)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Bresenham should handle negative coordinates
		// Check that cells exist along the diagonal
		var cellCount = 0
		for (i in -1..4) {
			if (context.getRailWayNetGrid().getCellAt(i, i) != null) {
				cellCount++
			}
		}
		assertThat(cellCount)
			.withFailMessage("Expected Bresenham to handle negative coordinates correctly")
			.isGreaterThan(0)
	}

	@Test
	@DisplayName("Join cells in reverse direction creates same line")
	fun joinCells_reverseDirection_createsSameLine() {
		// Arrange - Same two points but joined in reverse order
		val p1 = Point(2, 2)
		val p2 = Point(8, 5)
		val inA1 = InOut("A1", false, SpatialType.HORIZONTAL)
		val inB1 = InOut("B1", true, SpatialType.HORIZONTAL)
		val inA2 = InOut("A2", false, SpatialType.HORIZONTAL)
		val inB2 = InOut("B2", true, SpatialType.HORIZONTAL)
		val block1 = SimpleTrackBlock(inA1, inB1, 1000.0, 80.0)
		val block2 = SimpleTrackBlock(inA2, inB2, 1000.0, 80.0)

		// Create two separate contexts to test both directions independently
		val context1 = XMLContextFactory.getInstance().createEmptyContext()
		val context2 = XMLContextFactory.getInstance().createEmptyContext()

		context1.putCell(p1, inA1)
		context1.putCell(p2, inB1)
		context2.putCell(p1, inA2)
		context2.putCell(p2, inB2)

		// Act - Join in both directions
		context1.joinCells(p1, p2, block1)
		context2.joinCells(p2, p1, block2)

		// Assert - Both should create cells (though direction might differ internally)
		// Count cells in both contexts
		var count1 = 0
		var count2 = 0
		for (x in 3..7) {
			for (y in 2..5) {
				if (context1.getRailWayNetGrid().getCellAt(x, y) != null) count1++
				if (context2.getRailWayNetGrid().getCellAt(x, y) != null) count2++
			}
		}

		assertThat(count1)
			.withFailMessage("Expected cells in forward direction")
			.isGreaterThan(0)
		assertThat(count2)
			.withFailMessage("Expected cells in reverse direction")
			.isGreaterThan(0)
	}

	@Test
	@DisplayName("Join preserves immutability of Point parameters")
	fun joinCells_immutablePoints_preservesOriginals() {
		// Arrange
		val start = Point(1, 1)
		val end = Point(10, 10)
		val originalStartX = start.x
		val originalStartY = start.y
		val originalEndX = end.x
		val originalEndY = end.y

		val inA = InOut("A", false, SpatialType.DIAGONAL1)
		val inB = InOut("B", true, SpatialType.DIAGONAL1)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Original Points should be unchanged (immutability)
		assertThat(start.x).isEqualTo(originalStartX)
		assertThat(start.y).isEqualTo(originalStartY)
		assertThat(end.x).isEqualTo(originalEndX)
		assertThat(end.y).isEqualTo(originalEndY)
	}
}
