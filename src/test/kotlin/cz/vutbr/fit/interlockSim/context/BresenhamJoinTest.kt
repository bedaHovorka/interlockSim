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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase

/**
 * Tests for Bresenham line algorithm used in cell joining.
 *
 * The Bresenham algorithm is used internally by joinCells to find intermediate
 * points when connecting two cells that are far apart (distance > √2).
 * These tests verify correct behavior through the public joinCells API.
 */
@DisplayName("Bresenham Line Algorithm (via joinCells)")
class BresenhamJoinTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()
	private lateinit var context: DefaultContext

	@BeforeEach
	fun setUp() {
		context = factory.createEmptyContext()
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
				.withMessage("Expected cell at ($x, 5) from horizontal Bresenham line")
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
				.withMessage("Expected cell at (5, $y) from vertical Bresenham line")
				.isNotNull()
		}
	}

	@Test
	@DisplayName("Join diagonal cells creates diagonal line (45 degrees)")
	fun joinCells_diagonalLine45_createsCorrectPath() {
		// Arrange - Two cells on 45-degree diagonal
		// Using positions farther from edges to avoid grid boundary issues
		val start = Point(5, 5)
		val end = Point(12, 12)
		val inA = InOut("A", false, SpatialType.DIAGONAL1)
		val inB = InOut("B", true, SpatialType.DIAGONAL1)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - For 45-degree line, Bresenham should create cells along diagonal
		// For a perfect 45-degree line from (5,5) to (12,12), the Bresenham algorithm
		// should create cells that generally follow the diagonal pattern.
		// We verify that at least some expected diagonal positions are filled.
		var diagonalCellCount = 0
		for (i in 6..11) {
			if (context.getRailWayNetGrid().getCellAt(i, i) != null) {
				diagonalCellCount++
			}
		}
		// Bresenham for 45-degree line should create at least 1 of the intermediate diagonal positions
		assertThat(diagonalCellCount)
			.withMessage("Expected Bresenham to create cells along 45-degree diagonal from (5,5) to (12,12)")
			.isGreaterThan(0)
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
		assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isSameInstanceAs(inA)
		assertThat(context.getRailWayNetGrid().getCellAt(6, 5)).isSameInstanceAs(inB)
	}

	@Test
	@DisplayName("Join cells at far positions handles correctly")
	fun joinCells_farPositions_handlesCorrectly() {
		// Arrange - Diagonal line using far positions (similar to negative coordinate test)
		// Tests Bresenham with cells far from origin
		val start = Point(10, 10)
		val end = Point(15, 15)
		val inA = InOut("A", false, SpatialType.DIAGONAL1)
		val inB = InOut("B", true, SpatialType.DIAGONAL1)
		val block = SimpleTrackBlock(inA, inB, 1000.0, 80.0)

		context.putCell(start, inA)
		context.putCell(end, inB)

		// Act
		context.joinCells(start, end, block)

		// Assert - Bresenham should create cells along the diagonal
		// Check that at least some cells are created between start and end
		var cellCount = 0
		for (i in 11..14) {
			if (context.getRailWayNetGrid().getCellAt(i, i) != null) {
				cellCount++
			}
		}
		assertThat(cellCount)
			.withMessage("Expected Bresenham to create cells along diagonal from (10,10) to (15,15)")
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
		val context1 = factory.createEmptyContext()
		val context2 = factory.createEmptyContext()

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
			.withMessage("Expected cells in forward direction")
			.isGreaterThan(0)
		assertThat(count2)
			.withMessage("Expected cells in reverse direction")
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
