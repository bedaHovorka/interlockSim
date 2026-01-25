/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context transformation helper methods (Issue #210)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Unit tests for individual transformation phases in DefaultSimulationContext.fromEditingContext().
 *
 * These tests verify that each helper method works correctly in isolation:
 * - copyGridCells() - Copies all cells from editing to simulation grid
 * - copyGraphStructure() - Copies graph entries (track blocks)
 * - copyInOutList() - Copies InOut elements
 * - copyConfiguration() - Copies properties (maxSpeed, trackLength, nameString)
 * - createDynamicMappings() - Creates dynamic wrapper mappings
 * - validateTransformation() - Logs transformation summary
 *
 * ## Issue #210: Simplify Context Transformation Process
 *
 * This test suite supports the refactoring of the 84-line fromEditingContext() god method
 * into focused, testable helper methods.
 *
 * @since 2026-01-22
 */
class ContextTransformationPhasesTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	/**
	 * Test 1: copyGridCells() copies all cells from editing to simulation grid
	 */
	@Test
	fun `copyGridCells copies all cells correctly`() {
		// Arrange - Build editing context with cells
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)

		// Act - Transform to simulation context (which calls copyGridCells internally)
		val simulationContext = simulationContextFactory.createContext(editingContext)

		// Assert - All cells copied
		val grid = simulationContext.getRailWayNetGrid()
		assertThat(grid.getCellAt(1, 1)).isNotNull()
		assertThat(grid.getCellAt(3, 3)).isNotNull()
		assertThat(grid.getCellAt(5, 5)).isNotNull()
		assertThat(grid.count()).isEqualTo(3)
	}

	/**
	 * Test 2: copyGraphStructure() copies all graph entries
	 */
	@Test
	fun `copyGraphStructure copies all graph entries correctly`() {
		// Arrange - Build editing context with graph entries
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(3, 3), track1)
		editingContext.joinCells(Point(3, 3), Point(5, 5), track2)
		val originalGraphSize = editingContext.getGraph().size()

		// Act - Transform to simulation context (which calls copyGraphStructure internally)
		val simulationContext = simulationContextFactory.createContext(editingContext)

		// Assert - All graph entries copied
		val graph = simulationContext.getGraph()
		assertThat(graph.size()).isEqualTo(originalGraphSize)
		assertThat(graph.size()).isGreaterThan(0)
	}

	/**
	 * Test 3: copyInOutList() copies all InOut elements
	 */
	@Test
	fun `copyInOutList copies all InOut elements correctly`() {
		// Arrange - Build editing context with InOut elements
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		val originalInOutCount = editingContext.getInOuts().size

		// Act - Transform to simulation context (which calls copyInOutList internally)
		val simulationContext = simulationContextFactory.createContext(editingContext)

		// Assert - All InOut elements copied
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts.size).isEqualTo(originalInOutCount)
		assertThat(inOuts.size).isEqualTo(2)
	}

	/**
	 * Test 4: copyConfiguration() copies all properties
	 */
	@Test
	fun `copyConfiguration copies all properties correctly`() {
		// Arrange - Build editing context with custom properties
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.currentMaxSpeed = 120.0
		editingContext.currentTrackLength = 750.0
		editingContext.currentNameString = "TestConfig"

		// Act - Transform to simulation context (which calls copyConfiguration internally)
		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		// Assert - All properties copied
		assertThat(simulationContext.currentMaxSpeed).isEqualTo(120.0)
		assertThat(simulationContext.currentTrackLength).isEqualTo(750.0)
		assertThat(simulationContext.currentNameString).isEqualTo("TestConfig")
	}

	/**
	 * Test 5: createDynamicMappings() creates wrapper mappings
	 */
	@Test
	fun `createDynamicMappings creates dynamic wrappers correctly`() {
		// Arrange - Build editing context with PathSeparators (InOut, RailSemaphore)
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(3, 3), track1)
		editingContext.joinCells(Point(3, 3), Point(5, 5), track2)

		// Act - Transform to simulation context (which calls createDynamicMappings internally)
		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		// Assert - Dynamic wrappers created (for InOut and RailSemaphore)
		// Note: We can't directly access staticToDynamicMap (it's private), but we can test toDynamic()
		assertThat(simulationContext.getInOuts().size).isGreaterThan(0)
		// Verify dynamic wrappers work
		for (dynInOut in simulationContext.getInOuts()) {
			assertThat(dynInOut.staticRef).isNotNull()
		}
	}

	/**
	 * Test 6: validateTransformation() logs summary (integration test)
	 *
	 * Note: We can't directly test logging output, but we verify the method doesn't throw
	 * and the transformation completes successfully.
	 */
	@Test
	fun `validateTransformation completes without errors`() {
		// Arrange - Build editing context
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Act - Transform to simulation context (which calls validateTransformation internally)
		// Should not throw any exceptions
		val simulationContext = simulationContextFactory.createContext(editingContext)

		// Assert - Transformation completed successfully
		assertThat(simulationContext).isNotNull()
		assertThat((simulationContext as DefaultSimulationContext).isFrozen()).isTrue()
	}

	/**
	 * Test 7: fromEditingContext() orchestrates all phases correctly
	 *
	 * This integration test verifies that the orchestration method calls all helper methods
	 * in the correct order and produces a valid simulation context.
	 */
	@Test
	fun `fromEditingContext orchestrates all phases correctly`() {
		// Arrange - Build complete network
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.currentMaxSpeed = 100.0
		editingContext.currentTrackLength = 600.0
		editingContext.currentNameString = "TestNetwork"
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(3, 3), track1)
		editingContext.joinCells(Point(3, 3), Point(5, 5), track2)

		// Act - Transform to simulation context
		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		// Assert - All phases completed successfully
		// 1. Grid cells copied (includes NodeCell and TrackBlockPart cells added by joinCells)
		assertThat(simulationContext.getRailWayNetGrid().count()).isGreaterThan(0)
		// 2. Graph structure copied
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		// 3. InOut list copied
		assertThat(simulationContext.getInOuts().size).isEqualTo(2)
		// 4. Configuration copied
		assertThat(simulationContext.currentMaxSpeed).isEqualTo(100.0)
		assertThat(simulationContext.currentTrackLength).isEqualTo(600.0)
		assertThat(simulationContext.currentNameString).isEqualTo("TestNetwork")
		// 5. Dynamic mappings created
		assertThat(simulationContext.getInOuts().first().staticRef).isNotNull()
		// 6. Context frozen
		assertThat(simulationContext.isFrozen()).isTrue()
	}

	/**
	 * Test 8: Empty editing context transforms correctly
	 */
	@Test
	fun `empty editing context transforms without errors`() {
		// Arrange - Empty editing context
		val editingContext = editingContextFactory.createEmptyContext()

		// Act - Transform to simulation context
		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		// Assert - Empty simulation context created
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext.getRailWayNetGrid().count()).isEqualTo(0)
		assertThat(simulationContext.getGraph().size()).isEqualTo(0)
		assertThat(simulationContext.getInOuts().size).isEqualTo(0)
		assertThat(simulationContext.isFrozen()).isTrue()
	}
}
