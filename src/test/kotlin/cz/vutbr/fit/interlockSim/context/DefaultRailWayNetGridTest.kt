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
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.assertThatCode
import cz.vutbr.fit.interlockSim.testutil.doesNotThrowAnyException
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Mock implementation of TrackBlock for testing grid operations.
 */
private class MockTrackBlock : TrackBlock {
	// TrackBlock methods
	override var name: String? = null

	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? = null

	override fun isInnerElement(element: PathElement): Boolean = false

	override fun getJoin(
		separator: PathSeparator,
		current: TrackSection
	): Cell.Segment = Cell.Segment.A

	// Track methods
	override fun isFreeFrom(sep: PathSeparator): Boolean = true

	override fun setUpPath(from: PathSeparator) {}

	override fun isSetUpPath(from: PathSeparator): Boolean = false

	override fun cancelPathSetup(from: PathSeparator) {}

	override fun getSecondEnd(sep: PathSeparator): PathSeparator = sep

	override fun length(): Double = 100.0

	override fun maxSpeed(from: PathSeparator?): Double = 80.0

	override fun ends(): Array<PathSeparator> = emptyArray()

	override fun getState(): cz.vutbr.fit.interlockSim.objects.core.TrackFacility.State =
		cz.vutbr.fit.interlockSim.objects.core.TrackFacility.State.FREE

	override fun enter(occupant: cz.vutbr.fit.interlockSim.objects.core.TrackOccupant) {}

	override fun leave(occupant: cz.vutbr.fit.interlockSim.objects.core.TrackOccupant) {}

	override fun getTrackOccupant(): cz.vutbr.fit.interlockSim.objects.core.TrackOccupant =
		throw UnsupportedOperationException("Mock implementation")
}

/**
 * Comprehensive unit tests for [DefaultRailWayNetGrid].
 *
 * Coverage:
 * - Basic operations: put, get, remove, containsKey
 * - Reverse table consistency: cell-to-point mapping integrity
 * - Iterator operations: keySet iteration and removal
 * - Batch operations: putMap for TrackBlockPart collections
 */
@DisplayName("DefaultRailWayNetGrid")
class DefaultRailWayNetGridTest {
	companion object {
		// Shared mock TrackBlock instance for all tests
		private val mockTrackBlock = MockTrackBlock()

		// Helper function to create TrackBlockPart for testing
		private fun createTestTrackBlockPart(): TrackBlockPart =
			TrackBlockPart(mockTrackBlock, arrayOf(Cell.Segment.A, Cell.Segment.F))
	}

	@Nested
	@DisplayName("Basic Operations")
	class BasicOperationsTests {
		private lateinit var grid: DefaultRailWayNetGrid

		@BeforeEach
		fun setUp() {
			grid = DefaultRailWayNetGrid(20, 20)
		}

		@Test
		@DisplayName("put adds cell to grid")
		fun put_validCell_addsToGrid() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)

			// Act
			val result = grid.put(point, cell)

			// Assert
			assertThat(result).isNull()
			assertThat(grid.getCellAt(5, 5)).isSameInstanceAs(cell)
		}

		@Test
		@DisplayName("put returns previous cell when replacing")
		fun put_replacingCell_returnsPrevious() {
			// Arrange
			val point = Point(5, 5)
			val first = InOut("A", false, SpatialType.HORIZONTAL)
			val second = InOut("B", true, SpatialType.HORIZONTAL)
			grid.put(point, first)

			// Act
			val result = grid.put(point, second)

			// Assert
			assertThat(result).isSameInstanceAs(first)
			assertThat(grid.getCellAt(5, 5)).isSameInstanceAs(second)
		}

		@Test
		@DisplayName("get returns cell at position")
		fun get_existingCell_returnsCell() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act
			val result = grid[point]

			// Assert
			assertThat(result).isSameInstanceAs(cell)
		}

		@Test
		@DisplayName("get returns null for empty position")
		fun get_emptyPosition_returnsNull() {
			// Arrange
			val point = Point(5, 5)

			// Act
			val result = grid[point]

			// Assert
			assertThat(result).isNull()
		}

		@Test
		@DisplayName("remove deletes cell from grid")
		fun remove_existingCell_removesCell() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act
			grid.remove(point)

			// Assert
			assertThat(grid.getCellAt(5, 5)).isNull()
		}

		@Test
		@DisplayName("containsKey returns true for existing cell")
		fun containsKey_existingCell_returnsTrue() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act & Assert
			assertThat(grid.containsKey(point)).isTrue()
		}

		@Test
		@DisplayName("containsKey returns false for empty position")
		fun containsKey_emptyPosition_returnsFalse() {
			// Arrange
			val point = Point(5, 5)

			// Act & Assert
			assertThat(grid.containsKey(point)).isFalse()
		}

		@Test
		@DisplayName("isEmpty returns true for empty grid")
		fun isEmpty_emptyGrid_returnsTrue() {
			// Act & Assert
			assertThat(grid.isEmpty()).isTrue()
		}

		@Test
		@DisplayName("isEmpty returns false for non-empty grid")
		fun isEmpty_withCells_returnsFalse() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act & Assert
			assertThat(grid.isEmpty()).isFalse()
		}
	}

	@Nested
	@DisplayName("Reverse Table Consistency")
	class ReverseTableConsistencyTests {
		private lateinit var grid: DefaultRailWayNetGrid

		@BeforeEach
		fun setUp() {
			grid = DefaultRailWayNetGrid(20, 20)
		}

		@Test
		@DisplayName("put maintains reverse table consistency")
		fun put_addsCell_maintainsReverseTable() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)

			// Act
			grid.put(point, cell)

			// Assert - reverse table should map cell back to point
			assertThat(grid.getLocation(cell)).isEqualTo(point)
		}

		@Test
		@DisplayName("put replacing cell maintains reverse table")
		fun put_replacingCell_maintainsReverseTable() {
			// Arrange
			val point = Point(5, 5)
			val first = InOut("A", false, SpatialType.HORIZONTAL)
			val second = InOut("B", true, SpatialType.HORIZONTAL)
			grid.put(point, first)

			// Act
			grid.put(point, second)

			// Assert - old cell should not be in reverse table
			assertThat(grid.getLocation(first)).isNull()
			assertThat(grid.getLocation(second)).isEqualTo(point)
		}

		@Test
		@DisplayName("remove maintains reverse table consistency")
		fun remove_existingCell_maintainsReverseTable() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act
			grid.remove(point)

			// Assert - cell should not be in reverse table
			assertThat(grid.getLocation(cell)).isNull()
		}

		@Test
		@DisplayName("multiple puts maintain reverse table consistency")
		fun multiplePuts_maintainReverseTable() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(10, 10)
			val point3 = Point(15, 15)
			val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
			val cell2 = RailSemaphore(false, SpatialType.VERTICAL)
			val cell3 = InOut("B", true, SpatialType.HORIZONTAL)

			// Act
			grid.put(point1, cell1)
			grid.put(point2, cell2)
			grid.put(point3, cell3)

			// Assert
			assertThat(grid.getLocation(cell1)).isEqualTo(point1)
			assertThat(grid.getLocation(cell2)).isEqualTo(point2)
			assertThat(grid.getLocation(cell3)).isEqualTo(point3)
		}

		@Test
		@DisplayName("containsKey assertion validates reverse table consistency")
		fun containsKey_validatesReverseTableConsistency() {
			// Arrange
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)

			// Act & Assert - should not throw assertion error
			assertThatCode { grid.containsKey(point) }
				.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("containsKey on empty position validates no stale reverse entries")
		fun containsKey_emptyPosition_validatesNoStaleReverseEntries() {
			// Arrange - add and remove cell to ensure clean state
			val point = Point(5, 5)
			val cell = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point, cell)
			grid.remove(point)

			// Act & Assert - should not throw assertion error
			assertThatCode { grid.containsKey(point) }
				.doesNotThrowAnyException()
		}
	}

	@Nested
	@DisplayName("KeySet Iterator Operations")
	class KeySetIteratorTests {
		private lateinit var grid: DefaultRailWayNetGrid

		@BeforeEach
		fun setUp() {
			grid = DefaultRailWayNetGrid(20, 20)
		}

		@Test
		@DisplayName("keySet returns all cell positions")
		fun keySet_withCells_returnsAllPositions() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(10, 10)
			val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
			val cell2 = RailSemaphore(false, SpatialType.VERTICAL)
			grid.put(point1, cell1)
			grid.put(point2, cell2)

			// Act
			val keySet = grid.keySet()

			// Assert
			assertThat(keySet).containsOnly(point1, point2)
		}

		@Test
		@DisplayName("keySet iterator remove maintains reverse table")
		fun keySet_iteratorRemove_maintainsReverseTable() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(10, 10)
			val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
			val cell2 = RailSemaphore(false, SpatialType.VERTICAL)
			grid.put(point1, cell1)
			grid.put(point2, cell2)

			// Act - remove via iterator
			val iterator = grid.keySet().iterator()
			while (iterator.hasNext()) {
				val point = iterator.next()
				if (point == point1) {
					iterator.remove()
					break
				}
			}

			// Assert - cell should be removed from both maps
			assertThat(grid.getCellAt(5, 5)).isNull()
			assertThat(grid.getLocation(cell1)).isNull()
			assertThat(grid.getLocation(cell2)).isEqualTo(point2)
		}

		@Test
		@DisplayName("keySet removeAll maintains reverse table consistency")
		fun keySet_removeAll_maintainsReverseTable() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(10, 10)
			val point3 = Point(15, 15)
			val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
			val cell2 = RailSemaphore(false, SpatialType.VERTICAL)
			val cell3 = InOut("B", true, SpatialType.HORIZONTAL)
			grid.put(point1, cell1)
			grid.put(point2, cell2)
			grid.put(point3, cell3)

			// Act - remove multiple cells
			grid.keySet().removeAll(setOf(point1, point3))

			// Assert - removed cells should not be in reverse table
			assertThat(grid.getCellAt(5, 5)).isNull()
			assertThat(grid.getCellAt(15, 15)).isNull()
			assertThat(grid.getLocation(cell1)).isNull()
			assertThat(grid.getLocation(cell3)).isNull()
			// cell2 should still be present
			assertThat(grid.getCellAt(10, 10)).isSameInstanceAs(cell2)
			assertThat(grid.getLocation(cell2)).isEqualTo(point2)
		}

		@Test
		@DisplayName("keySet removeAll with empty set does nothing")
		fun keySet_removeAllEmpty_doesNothing() {
			// Arrange
			val point1 = Point(5, 5)
			val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
			grid.put(point1, cell1)

			// Act
			grid.keySet().removeAll(emptySet())

			// Assert
			assertThat(grid.getCellAt(5, 5)).isSameInstanceAs(cell1)
			assertThat(grid.getLocation(cell1)).isEqualTo(point1)
		}
	}

	@Nested
	@DisplayName("Batch Operations")
	class BatchOperationsTests {
		private lateinit var grid: DefaultRailWayNetGrid

		@BeforeEach
		fun setUp() {
			grid = DefaultRailWayNetGrid(20, 20)
		}

		@Test
		@DisplayName("putMap adds multiple TrackBlockParts")
		fun putMap_multipleTrackBlockParts_addsAll() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(6, 6)
			val point3 = Point(7, 7)
			val part1 = createTestTrackBlockPart()
			val part2 = createTestTrackBlockPart()
			val part3 = createTestTrackBlockPart()
			val map = java.util.HashMap<Point, TrackBlockPart>()
			map[point1] = part1
			map[point2] = part2
			map[point3] = part3

			// Act
			grid.putMap(map)

			// Assert - all parts should be in grid
			assertThat(grid.getCellAt(5, 5)).isSameInstanceAs(part1)
			assertThat(grid.getCellAt(6, 6)).isSameInstanceAs(part2)
			assertThat(grid.getCellAt(7, 7)).isSameInstanceAs(part3)
		}

		@Test
		@DisplayName("putMap maintains reverse table for all TrackBlockParts")
		fun putMap_maintainsReverseTableForAll() {
			// Arrange
			val point1 = Point(5, 5)
			val point2 = Point(6, 6)
			val point3 = Point(7, 7)
			val part1 = createTestTrackBlockPart()
			val part2 = createTestTrackBlockPart()
			val part3 = createTestTrackBlockPart()
			val map = java.util.HashMap<Point, TrackBlockPart>()
			map[point1] = part1
			map[point2] = part2
			map[point3] = part3

			// Act
			grid.putMap(map)

			// Assert - all parts should be in reverse table
			assertThat(grid.getLocation(part1)).isEqualTo(point1)
			assertThat(grid.getLocation(part2)).isEqualTo(point2)
			assertThat(grid.getLocation(part3)).isEqualTo(point3)
		}

		@Test
		@DisplayName("putMap followed by removeAll maintains consistency")
		fun putMap_thenRemoveAll_maintainsConsistency() {
			// Arrange - simulate joinCells scenario
			val point1 = Point(5, 5)
			val point2 = Point(6, 6)
			val point3 = Point(7, 7)
			val part1 = createTestTrackBlockPart()
			val part2 = createTestTrackBlockPart()
			val part3 = createTestTrackBlockPart()
			val map = java.util.HashMap<Point, TrackBlockPart>()
			map[point1] = part1
			map[point2] = part2
			map[point3] = part3
			grid.putMap(map)

			// Act - remove all intermediate cells (simulate removeLine)
			grid.keySet().removeAll(setOf(point1, point2, point3))

			// Assert - reverse table should be clean
			assertThat(grid.getLocation(part1)).isNull()
			assertThat(grid.getLocation(part2)).isNull()
			assertThat(grid.getLocation(part3)).isNull()
			assertThat(grid.isEmpty()).isTrue()
		}

		@Test
		@DisplayName("putMap with empty map does nothing")
		fun putMap_emptyMap_doesNothing() {
			// Act
			grid.putMap(emptyMap())

			// Assert
			assertThat(grid.isEmpty()).isTrue()
		}
	}

	@Nested
	@DisplayName("Sparse Layout Scenarios")
	class SparseLayoutTests {
		private lateinit var grid: DefaultRailWayNetGrid

		@BeforeEach
		fun setUp() {
			grid = DefaultRailWayNetGrid(20, 20)
		}

		@Test
		@DisplayName("sparse grid with intermediate cells maintains consistency")
		fun sparseGrid_withIntermediateCells_maintainsConsistency() {
			// Arrange - create sparse layout with nodes and intermediate track parts
			val nodePoint1 = Point(1, 1)
			val nodePoint2 = Point(10, 10)
			val node1 = InOut("A", false, SpatialType.HORIZONTAL)
			val node2 = InOut("B", true, SpatialType.HORIZONTAL)

			// Add nodes
			grid.put(nodePoint1, node1)
			grid.put(nodePoint2, node2)

			// Add intermediate TrackBlockParts (simulating joinCells)
			val intermediatePoints =
				listOf(
					Point(2, 2),
					Point(3, 3),
					Point(4, 4),
					Point(5, 5),
					Point(6, 6),
					Point(7, 7),
					Point(8, 8),
					Point(9, 9)
				)
			val parts = intermediatePoints.map { createTestTrackBlockPart() }
			val map = java.util.HashMap<Point, TrackBlockPart>()
			intermediatePoints.zip(parts).forEach { (point, part) -> map[point] = part }
			grid.putMap(map)

			// Act & Assert - all points should pass containsKey check
			assertThatCode {
				for (point in intermediatePoints) {
					grid.containsKey(point)
				}
			}.doesNotThrowAnyException()

			// Assert - reverse table should have all entries
			for ((point, part) in map) {
				assertThat(grid.getLocation(part)).isEqualTo(point)
			}
		}

		@Test
		@DisplayName("checking used intermediate point does not throw assertion")
		fun checkingUsedIntermediatePoint_doesNotThrow() {
			// Arrange - simulate the scenario from issue #38
			val point1 = Point(5, 5)
			val part = createTestTrackBlockPart()
			grid.put(point1, part)

			// Act & Assert - containsKey should not throw
			assertThatCode { grid.containsKey(point1) }
				.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("checking unused point after removal does not throw assertion")
		fun checkingUnusedPointAfterRemoval_doesNotThrow() {
			// Arrange
			val point = Point(5, 5)
			val part = createTestTrackBlockPart()
			grid.put(point, part)
			grid.remove(point)

			// Act & Assert - containsKey should not throw
			assertThatCode { grid.containsKey(point) }
				.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("removing intermediate cells via removeAll maintains consistency")
		fun removingIntermediateCellsViaRemoveAll_maintainsConsistency() {
			// Arrange - create layout with intermediate cells
			val intermediatePoints =
				listOf(
					Point(2, 2),
					Point(3, 3),
					Point(4, 4),
					Point(5, 5),
					Point(6, 6)
				)
			val parts = intermediatePoints.map { createTestTrackBlockPart() }
			val map = java.util.HashMap<Point, TrackBlockPart>()
			intermediatePoints.zip(parts).forEach { (point, part) -> map[point] = part }
			grid.putMap(map)

			// Act - remove all intermediate cells (simulates removeLine scenario)
			grid.keySet().removeAll(intermediatePoints.toSet())

			// Assert - grid should be empty and consistent
			assertThat(grid.isEmpty()).isTrue()
			for (part in parts) {
				assertThat(grid.getLocation(part)).isNull()
			}

			// Verify no assertion errors when checking removed points
			for (point in intermediatePoints) {
				assertThatCode { grid.containsKey(point) }
					.doesNotThrowAnyException()
			}
		}
	}
}
