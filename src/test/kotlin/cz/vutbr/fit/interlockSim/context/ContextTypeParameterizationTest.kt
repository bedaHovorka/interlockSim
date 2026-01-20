/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context type parameterization (Issue #153.9)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Test suite for context type parameterization and type safety.
 *
 * Tests verify that:
 * - EditingContext returns RailwayNetGrid<NodeCell>
 * - SimulationContext returns RailwayNetGrid<Cell>
 * - Type parameterization is enforced at compile time
 * - Static/dynamic separation is maintained
 * - Grid parameterization prevents type errors
 *
 * ## Issue #153.9: Add Comprehensive Context Refactoring Tests
 *
 * Category 4: Type Safety tests (5 tests)
 * - Tests compile-time type guarantees provided by the grid parameterization
 *
 * @since 2026-01-20
 */
class ContextTypeParameterizationTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)

	/**
	 * Test 1: EditingContext returns RailwayNetGrid<NodeCell>
	 *
	 * This test verifies that the editing context grid is parameterized with NodeCell,
	 * which is the correct type for editing operations (putCell accepts NodeCell).
	 */
	@Test
	fun `EditingContext returns grid parameterized with NodeCell`() {
		// Arrange
		val context = factory.createEmptyContext()

		// Act
		val grid = context.getRailWayNetGrid()

		// Assert - Grid is parameterized with NodeCell
		// The type system ensures this at compile time, but we can verify runtime behavior
		assertThat(grid).isNotNull()
		assertThat(grid).isInstanceOf<RailwayNetGrid<*>>()

		// Add a cell and verify it's stored correctly
		context.putCell(Point(1, 1), inA)
		val cell = grid.getCellAt(1, 1)
		assertThat(cell!!).isInstanceOf<NodeCell>()
	}

	/**
	 * Test 2: SimulationContext returns RailwayNetGrid<Cell>
	 *
	 * This test verifies that the simulation context grid is parameterized with Cell,
	 * which includes both NodeCell and TrackBlockPart (generated during track joining).
	 */
	@Test
	fun `SimulationContext returns grid parameterized with Cell`() {
		// Arrange - Build network and transform
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		val simulationContext = factory.createContext(editingContext)

		// Act
		val grid = simulationContext.getRailWayNetGrid()

		// Assert - Grid is parameterized with Cell (most general type)
		assertThat(grid).isNotNull()
		assertThat(grid).isInstanceOf<RailwayNetGrid<*>>()

		// All cells in grid should be Cell instances
		val cell = grid.getCellAt(1, 1)
		assertThat(cell!!).isInstanceOf<Cell>()
	}

	/**
	 * Test 3: Grid parameterization enforced at compile time
	 *
	 * This is a documentation test - the real enforcement happens at compile time.
	 * We verify that the type system works as expected at runtime.
	 */
	@Test
	fun `grid parameterization enforced by type system`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		val simulationContext = factory.createEmptySimulationContext()

		// Act - Get grids with their parameterized types
		val editingGrid: RailwayNetGrid<NodeCell> = editingContext.getRailWayNetGrid()
		val simulationGrid: RailwayNetGrid<Cell> = simulationContext.getRailWayNetGrid()

		// Assert - Type assignments work correctly
		// If these assignments compile and run, the type system is working
		assertThat(editingGrid).isNotNull()
		assertThat(simulationGrid).isNotNull()
		
		// The key guarantee: editingGrid can only return NodeCell or subtypes
		// simulationGrid can return any Cell (NodeCell or TrackBlockPart)
		// This is enforced at compile time by Kotlin's type system
	}

	/**
	 * Test 4: Static/dynamic separation maintained through transformation
	 *
	 * Verifies that the transformation from editing to simulation properly
	 * maintains the static/dynamic separation pattern.
	 */
	@Test
	fun `static dynamic separation maintained through transformation`() {
		// Arrange - Build network with static cells
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Act - Transform to simulation (creates dynamic wrappers)
		val simulationContext = factory.createContext(editingContext)

		// Assert - Dynamic wrappers maintain references to static cells
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts).isNotNull()
		for (dynInOut in inOuts) {
			// Dynamic wrapper should have reference to static cell
			assertThat(dynInOut.staticRef).isNotNull()
			assertThat(dynInOut.staticRef).isInstanceOf(InOut::class)
		}
	}

	/**
	 * Test 5: Covariant return types work correctly
	 *
	 * This test verifies that EditingContext can override getRailWayNetGrid()
	 * with a covariant return type (RailwayNetGrid<NodeCell> instead of RailwayNetGrid<Cell>).
	 */
	@Test
	fun `covariant return types work in context hierarchy`() {
		// Arrange
		val editingContext = factory.createEmptyContext()
		val simulationContext = factory.createEmptySimulationContext()

		// Act - Access grids through interface references
		val editingContextRef: EditingContext = editingContext
		val simulationContextRef: SimulationContext = simulationContext

		// Assert - Covariant return types work
		// EditingContext.getRailWayNetGrid() returns RailwayNetGrid<NodeCell>
		val editingGrid = editingContextRef.getRailWayNetGrid()
		assertThat(editingGrid).isInstanceOf(RailwayNetGrid::class)

		// SimulationContext.getRailWayNetGrid() returns RailwayNetGrid<Cell>
		val simulationGrid = simulationContextRef.getRailWayNetGrid()
		assertThat(simulationGrid).isInstanceOf(RailwayNetGrid::class)

		// The type system ensures that:
		// - editingGrid is typed as RailwayNetGrid<NodeCell>
		// - simulationGrid is typed as RailwayNetGrid<Cell>
		// This is Java/Kotlin covariant return type support in action
	}
}
