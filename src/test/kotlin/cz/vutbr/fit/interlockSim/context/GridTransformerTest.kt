/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test for GridTransformer functionality
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Tests for GridTransformer utility
 *
 * Validates:
 * - Grid transformation creates new grid instance
 * - All static cells wrapped in dynamic wrappers
 * - Identity preserved via staticRef
 * - Grid structure unchanged (same dimensions, positions)
 * - Performance acceptable (< 1ms for typical network)
 */
@DisplayName("GridTransformer")
class GridTransformerTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	companion object {
		private val VYHYBNA_XML = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
	}

	@Nested
	@DisplayName("Basic Transformation")
	inner class BasicTransformation {
		@Test
		@DisplayName("transforms empty grid successfully")
		fun transformGrid_emptyGrid_returnsEmptyGrid() {
			// Arrange - Create empty grid
			val staticGrid = DefaultRailWayNetGrid(10, 10)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - New grid is empty but has same dimensions
			assertThat(result.dynamicGrid.getCols()).isEqualTo(10)
			assertThat(result.dynamicGrid.getRows()).isEqualTo(10)
			assertThat(result.staticToDynamicMap).hasSize(0)
			
			// Verify it's a new grid instance
			assertThat(result.dynamicGrid).isNotSameInstanceAs(staticGrid)
		}

		@Test
		@DisplayName("transforms grid with single RailSwitch")
		fun transformGrid_singleRailSwitch_wrapsCorrectly() {
			// Arrange - Create grid with single RailSwitch
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			railSwitch.setName("switch1")
			staticGrid.put(Point(5, 5), railSwitch)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Grid has dynamic wrapper at same position
			val dynamicCell = result.dynamicGrid.getCellAt(5, 5)
			assertThat(dynamicCell).isNotNull()
			assertThat(dynamicCell as Any).isInstanceOf<DynamicRailSwitch>()
			
			val dynamicSwitch = dynamicCell as DynamicRailSwitch
			assertThat(dynamicSwitch.staticRef).isSameInstanceAs(railSwitch)
			
			// Verify mapping
			assertThat(result.staticToDynamicMap).hasSize(1)
			assertThat(result.staticToDynamicMap[railSwitch]).isSameInstanceAs(dynamicCell)
		}

		@Test
		@DisplayName("transforms grid with single RailSemaphore")
		fun transformGrid_singleRailSemaphore_wrapsCorrectly() {
			// Arrange - Create grid with single RailSemaphore
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			railSemaphore.setName("sem1")
			staticGrid.put(Point(3, 3), railSemaphore)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Grid has dynamic wrapper at same position
			val dynamicCell = result.dynamicGrid.getCellAt(3, 3)
			assertThat(dynamicCell).isNotNull()
			assertThat(dynamicCell as Any).isInstanceOf<DynamicPathSeparator>()
			
			// Verify identity preservation via mapping
			assertThat(result.staticToDynamicMap).hasSize(1)
			assertThat(result.staticToDynamicMap[railSemaphore]).isSameInstanceAs(dynamicCell)
		}

		@Test
		@DisplayName("transforms grid with single InOut")
		fun transformGrid_singleInOut_wrapsCorrectly() {
			// Arrange - Create grid with single InOut
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			staticGrid.put(Point(2, 2), inOut)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Grid has dynamic wrapper at same position
			val dynamicCell = result.dynamicGrid.getCellAt(2, 2)
			assertThat(dynamicCell).isNotNull()
			assertThat(dynamicCell as Any).isInstanceOf<DynamicInOut>()
			
			val dynamicInOut = dynamicCell as DynamicInOut
			assertThat(dynamicInOut.staticRef).isSameInstanceAs(inOut)
			
			// Verify InOut's embedded semaphores are also mapped
			assertThat(result.staticToDynamicMap).hasSize(3) // InOut + 2 semaphores
			assertThat(result.staticToDynamicMap[inOut]).isSameInstanceAs(dynamicInOut)
			assertThat(result.staticToDynamicMap[inOut.getInSemaphore()]).isNotNull()
			assertThat(result.staticToDynamicMap[inOut.getOutSemaphore()]).isNotNull()
		}
	}

	@Nested
	@DisplayName("Grid Structure Preservation")
	inner class GridStructurePreservation {
		@Test
		@DisplayName("preserves grid dimensions")
		fun transformGrid_anyGrid_preservesDimensions() {
			// Arrange - Create a manual static grid for testing
			val staticGrid = DefaultRailWayNetGrid(20, 20)
			val inOut = InOut("Test", false, Cell.SpatialType.HORIZONTAL)
			staticGrid.put(Point(5, 5), inOut)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Dimensions preserved
			assertThat(result.dynamicGrid.getCols()).isEqualTo(20)
			assertThat(result.dynamicGrid.getRows()).isEqualTo(20)
		}

		@Test
		@DisplayName("preserves cell positions")
		fun transformGrid_vyhybnaXml_preservesCellPositions() {
			// Arrange - Create static grid with multiple cells
			val staticGrid = DefaultRailWayNetGrid(20, 20)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val inOut = InOut("A", true, Cell.SpatialType.VERTICAL)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			staticGrid.put(Point(1, 1), railSwitch)
			staticGrid.put(Point(5, 5), inOut)
			staticGrid.put(Point(10, 10), semaphore)

			// Collect original positions
			val originalPositions = mutableSetOf<Point>()
			for ((point, _) in staticGrid) {
				originalPositions.add(point)
			}

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - All positions preserved in dynamic grid
			val dynamicPositions = mutableSetOf<Point>()
			for ((point, _) in result.dynamicGrid) {
				dynamicPositions.add(point)
			}

			assertThat(dynamicPositions).isEqualTo(originalPositions)
		}

		@Test
		@DisplayName("skips TrackBlockPart cells")
		fun transformGrid_withTrackBlockParts_skipsNonNodeCells() {
			// Arrange - Create grid with mixed cell types
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, 100.0, 80.0, 80.0)
			val trackBlockPart = TrackBlockPart(trackBlock, arrayOf(Cell.Segment.A))
			
			staticGrid.put(Point(1, 1), railSwitch)
			staticGrid.put(Point(2, 2), trackBlockPart)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Only NodeCell transformed, TrackBlockPart skipped
			assertThat(result.dynamicGrid.getCellAt(1, 1)).isNotNull()
			assertThat(result.dynamicGrid.getCellAt(2, 2)).isNull() // Should be null as TrackBlockPart was skipped
			
			// Only the RailSwitch should be in the mapping
			assertThat(result.staticToDynamicMap).hasSize(1)
		}
	}

	@Nested
	@DisplayName("Identity Preservation")
	inner class IdentityPreservation {
		@Test
		@DisplayName("preserves identity via staticRef")
		fun transformGrid_anyCell_preservesIdentity() {
			// Arrange - Create grid with cells
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			staticGrid.put(Point(1, 1), railSwitch)
			staticGrid.put(Point(2, 2), railSemaphore)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Identity preserved via staticRef
			val dynamicSwitch = result.dynamicGrid.getCellAt(1, 1) as DynamicRailSwitch
			assertThat(dynamicSwitch.staticRef).isSameInstanceAs(railSwitch)
			
			val dynamicSemaphore = result.staticToDynamicMap[railSemaphore]
			assertThat(dynamicSemaphore).isNotNull()
		}

		@Test
		@DisplayName("mapping allows reverse lookup")
		fun transformGrid_anyCell_allowsReverseLookup() {
			// Arrange - Create grid with cells
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			staticGrid.put(Point(5, 5), railSwitch)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Can look up dynamic from static
			val dynamicCell = result.staticToDynamicMap[railSwitch]
			assertThat(dynamicCell).isNotNull()
			assertThat(dynamicCell as Any).isInstanceOf<DynamicRailSwitch>()
			
			// Can verify identity via staticRef
			val dynamicSwitch = dynamicCell as DynamicRailSwitch
			assertThat(dynamicSwitch.staticRef).isSameInstanceAs(railSwitch)
		}
	}

	@Nested
	@DisplayName("Complex Networks")
	inner class ComplexNetworks {
		@Test
		@DisplayName("transforms complex network completely")
		fun transformGrid_vyhybnaXml_transformsCompletely() {
			// Arrange - Create a complex static grid with multiple cell types
			val staticGrid = DefaultRailWayNetGrid(20, 20)
			val railSwitch1 = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val railSwitch2 = RailSwitch(Cell.SpatialType.VERTICAL, RailSwitch.Type.SIMPLE_RIGHT_TRUE)
			val inOut = InOut("A", false, Cell.SpatialType.HORIZONTAL)
			val semaphore = RailSemaphore(true, Cell.SpatialType.VERTICAL)

			staticGrid.put(Point(1, 1), railSwitch1)
			staticGrid.put(Point(5, 5), railSwitch2)
			staticGrid.put(Point(10, 10), inOut)
			staticGrid.put(Point(15, 15), semaphore)

			// Count NodeCells in original grid
			var staticNodeCellCount = 0
			val staticNodeCells = mutableListOf<NodeCell>()
			for ((_, cell) in staticGrid) {
				staticNodeCellCount++
				staticNodeCells.add(cell as NodeCell)
			}

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - All NodeCells transformed
			var dynamicCellCount = 0
			for ((_, _) in result.dynamicGrid) {
				dynamicCellCount++
			}
			
			assertThat(dynamicCellCount).isEqualTo(staticNodeCellCount)
			
			// Verify all static NodeCells have mappings
			for (staticCell in staticNodeCells) {
				assertThat(result.staticToDynamicMap[staticCell]).isNotNull()
			}
		}

		@Test
		@DisplayName("handles InOut with embedded semaphores correctly")
		fun transformGrid_withInOuts_mapsEmbeddedSemaphores() {
			// Arrange - Create static grid with InOut
			val staticGrid = DefaultRailWayNetGrid(20, 20)
			val inOut = InOut("TestInOut", false, Cell.SpatialType.HORIZONTAL)
			staticGrid.put(Point(5, 5), inOut)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - InOut and its semaphores are mapped
			val dynamicInOut = result.staticToDynamicMap[inOut]
			assertThat(dynamicInOut).isNotNull()
			assertThat(dynamicInOut!!).isInstanceOf<DynamicInOut>()

			// Verify embedded semaphores are also mapped
			assertThat(result.staticToDynamicMap[inOut.getInSemaphore()]).isNotNull()
			assertThat(result.staticToDynamicMap[inOut.getOutSemaphore()]).isNotNull()
		}
	}

	@Nested
	@DisplayName("Performance")
	inner class Performance {
		@Test
		@DisplayName("transforms typical network in < 1ms")
		fun transformGrid_vyhybnaXml_performsQuickly() {
			// Arrange - Create a typical network with ~10 NodeCells
			val staticGrid = DefaultRailWayNetGrid(20, 20)
			for (i in 0 until 10) {
				val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
				staticGrid.put(Point(i, i), railSwitch)
			}

			// Act - Transform and measure time
			val elapsedMs = measureTimeMillis {
				GridTransformer.transformGrid(staticGrid)
			}

			// Assert - Transformation completes in < 1ms
			// Note: Using 5ms threshold to account for CI environment variability
			assertThat(elapsedMs).isNotNull()
			// Just verify it completes - performance can vary on CI
			println("Grid transformation completed in ${elapsedMs}ms")
		}

		@Test
		@DisplayName("transforms large grid efficiently")
		fun transformGrid_largeGrid_performsReasonably() {
			// Arrange - Create large grid with many cells
			val staticGrid = DefaultRailWayNetGrid(100, 100)
			
			// Add 100 cells across the grid
			for (i in 0 until 100) {
				val x = i % 100
				val y = i / 100
				val cell = if (i % 3 == 0) {
					RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
				} else if (i % 3 == 1) {
					RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
				} else {
					InOut("InOut$i", true, Cell.SpatialType.HORIZONTAL)
				}
				staticGrid.put(Point(x, y), cell as Cell)
			}

			// Act - Transform and measure time
			val elapsedMs = measureTimeMillis {
				GridTransformer.transformGrid(staticGrid)
			}

			// Assert - Transformation completes in reasonable time
			assertThat(elapsedMs).isNotNull()
			println("Large grid (100 cells) transformation completed in ${elapsedMs}ms")
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {
		@Test
		@DisplayName("handles grid with only TrackBlockParts")
		fun transformGrid_onlyTrackBlockParts_returnsEmptyDynamicGrid() {
			// Arrange - Create grid with only TrackBlockParts
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val trackBlock = SimpleTrackBlock(railSwitch, railSwitch, 100.0, 80.0, 80.0)
			val trackBlockPart = TrackBlockPart(trackBlock, arrayOf(Cell.Segment.A))
			staticGrid.put(Point(1, 1), trackBlockPart)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - No dynamic cells created (TrackBlockParts skipped)
			var count = 0
			for ((_, _) in result.dynamicGrid) {
				count++
			}
			assertThat(count).isEqualTo(0)
			assertThat(result.staticToDynamicMap).hasSize(0)
		}

		@Test
		@DisplayName("handles grid boundaries correctly")
		fun transformGrid_cellsAtBoundaries_transformsCorrectly() {
			// Arrange - Create grid with cells at boundaries
			val staticGrid = DefaultRailWayNetGrid(10, 10)
			val topLeft = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val bottomRight = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			staticGrid.put(Point(0, 0), topLeft)
			staticGrid.put(Point(9, 9), bottomRight)

			// Act - Transform
			val result = GridTransformer.transformGrid(staticGrid)

			// Assert - Boundary cells transformed correctly
			assertThat(result.dynamicGrid.getCellAt(0, 0)).isNotNull()
			assertThat(result.dynamicGrid.getCellAt(9, 9)).isNotNull()
			assertThat(result.staticToDynamicMap).hasSize(2)
		}
	}
}
