/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context transformation (Issue #153.9)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Test suite for context transformation (editing → simulation).
 *
 * Tests verify that:
 * - Empty editing contexts transform to empty simulation contexts
 * - Simple networks transform correctly
 * - Complex networks transform correctly
 * - Properties are preserved during transformation
 * - Graph structure is preserved
 * - Dynamic mapping is correct
 * - Can simulate after transformation
 * - Multiple transformations work
 *
 * ## Issue #153.9: Add Comprehensive Context Refactoring Tests
 *
 * Category 2: Context Transformation tests (8 tests)
 * - Tests the editing → simulation context conversion process
 *
 * @since 2026-01-20
 */
class ContextTransformationTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	/**
	 * Test 1: Transform empty editing context → simulation context
	 */
	@Test
	fun `transform empty editing context to simulation context`() {
		// Arrange
		val editingContext = factory.createEmptyContext()

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext.getRailWayNetGrid()).isNotNull()
		assertThat(simulationContext.getGraph()).isNotNull()
	}

	/**
	 * Test 2: Transform simple network (2 nodes, 1 track block)
	 */
	@Test
	fun `transform simple network editing to simulation`() {
		// Arrange - Build simple network
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - Network structure preserved
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts).isNotEmpty()
		assertThat(inOuts.size).isEqualTo(2)
	}

	/**
	 * Test 3: Transform complex network (3 nodes, 2 track blocks, semaphore)
	 */
	@Test
	fun `transform complex network editing to simulation`() {
		// Arrange - Build complex network
		val editingContext = factory.createEmptyContext()
		val p1 = Point(1, 1)
		val p2 = Point(3, 3)
		val p3 = Point(5, 5)
		editingContext.putCell(p1, inA)
		editingContext.putCell(p2, semaphore)
		editingContext.putCell(p3, outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(p1, p2, track1)
		editingContext.joinCells(p2, p3, track2)

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - All components preserved
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts.size).isEqualTo(2)
		// Verify semaphore exists in grid
		val semaphoreCell = simulationContext.getRailWayNetGrid().getCellAt(p2.x, p2.y)
		assertThat(semaphoreCell).isNotNull()
	}

	/**
	 * Test 4: Verify property preservation during transformation
	 */
	@Test
	fun `properties preserved during transformation`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		editingContext.currentMaxSpeed = 120.0
		editingContext.currentTrackLength = 750.0
		editingContext.currentNameString = "TestTransform"

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - Properties preserved (simulation context inherits from BaseContext)
		// Note: These are BaseContext properties, so they should be accessible
		assertThat((simulationContext as? DefaultSimulationContext)?.currentMaxSpeed).isEqualTo(120.0)
		assertThat((simulationContext as? DefaultSimulationContext)?.currentTrackLength).isEqualTo(750.0)
		assertThat((simulationContext as? DefaultSimulationContext)?.currentNameString).isEqualTo("TestTransform")
	}

	/**
	 * Test 5: Verify graph structure preservation
	 */
	@Test
	fun `graph structure preserved during transformation`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		val editingGraphSize = editingContext.getGraph().size()

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - Graph size matches
		assertThat(simulationContext.getGraph().size()).isEqualTo(editingGraphSize)
	}

	/**
	 * Test 6: Verify dynamic mapping correctness (static → dynamic cells)
	 */
	@Test
	fun `dynamic mapping created correctly during transformation`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - InOut cells transformed to DynamicInOut
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts).isNotEmpty()
		for (dynInOut in inOuts) {
			assertThat(dynInOut).isInstanceOf(DynamicInOut::class)
			// Verify static reference exists
			assertThat(dynInOut.staticRef).isNotNull()
		}
	}

	/**
	 * Test 7: Simulation context is frozen after transformation
	 */
	@Test
	fun `simulation context frozen after transformation`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Act
		val simulationContext = factory.createContext(editingContext)

		// Assert - Simulation context should be frozen
		// Note: SimulationContext doesn't extend EditingContext, so we can't call freeze/isFrozen directly
		// But we can verify it's a DefaultSimulationContext which handles freezing internally
		assertThat(simulationContext).isInstanceOf(DefaultSimulationContext::class)
		// The factory should have frozen it, so casting and checking isFrozen should work
		assertThat((simulationContext as DefaultSimulationContext).isFrozen()).isTrue()
	}

	/**
	 * Test 8: Multiple transformations work (editing → sim → editing → sim)
	 *
	 * Note: This test verifies that we can create a new editing context and transform it
	 * multiple times without issues. This is a workflow test.
	 */
	@Test
	fun `multiple transformations work correctly`() {
		// First transformation
		val editing1 = factory.createEmptyContext()
		editing1.putCell(Point(1, 1), inA)
		editing1.putCell(Point(5, 5), outB)
		val trackBlock1 = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editing1.joinCells(Point(1, 1), Point(5, 5), trackBlock1)
		val sim1 = factory.createContext(editing1)
		assertThat(sim1.getGraph().size()).isGreaterThan(0)

		// Second transformation (new editing context, same structure)
		val editing2 = factory.createEmptyContext()
		val inA2 = InOut("A2", false, SpatialType.HORIZONTAL)
		val outB2 = InOut("B2", true, SpatialType.HORIZONTAL)
		editing2.putCell(Point(2, 2), inA2)
		editing2.putCell(Point(6, 6), outB2)
		val trackBlock2 = SimpleTrackBlock(inA2, outB2, 1000.0, 80.0)
		editing2.joinCells(Point(2, 2), Point(6, 6), trackBlock2)
		val sim2 = factory.createContext(editing2)
		assertThat(sim2.getGraph().size()).isGreaterThan(0)

		// Both simulations should be independent
		assertThat(sim1.getInOuts().first().name).isEqualTo("A")
		assertThat(sim2.getInOuts().first().name).isEqualTo("A2")
	}
}
