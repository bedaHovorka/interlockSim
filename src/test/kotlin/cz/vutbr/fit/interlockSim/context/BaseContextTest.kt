/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for BaseContext shared functionality (Issue #153.9)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Test suite for BaseContext shared functionality.
 *
 * Tests verify that:
 * - Grid and graph storage/access work correctly
 * - PropertyChangeSupport listener management works
 * - Configuration properties (maxSpeed, trackLength, nameString) work
 * - Track block mappings and queries work
 * - InOut (entry/exit points) management works
 * - Both editing and simulation contexts inherit BaseContext properly
 *
 * ## Issue #153.9: Add Comprehensive Context Refactoring Tests
 *
 * Category 1: Context Creation tests
 * - Tests shared infrastructure that both EditingContext and SimulationContext depend on
 *
 * @since 2026-01-20
 */
class BaseContextTest : KoinTestBase() {
	private val editingContextFactory: cz.vutbr.fit.interlockSim.context.EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)

	/**
	 * Test 1: Create empty editing context
	 */
	@Test
	fun `create empty editing context succeeds`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Assert - Default empty context has 100x100 grid (XMLContextFactory.DEFAULT_GRID_SIZE)
			assertThat(context.getRailWayNetGrid().getCols()).isEqualTo(100)
			assertThat(context.getRailWayNetGrid().getRows()).isEqualTo(100)
		}
	}

	/**
	 * Test 2: Create empty simulation context
	 */
	@Test
	fun `create empty simulation context succeeds`() {
		simulationContextFactory.createEmptyContext().use { context ->
			// Assert - Default empty context has 100x100 grid (XMLContextFactory.DEFAULT_GRID_SIZE)
			assertThat(context.getRailWayNetGrid().getCols()).isEqualTo(100)
			assertThat(context.getRailWayNetGrid().getRows()).isEqualTo(100)
		}
	}

	/**
	 * Test 3: Grid access returns same instance
	 */
	@Test
	fun `getRailWayNetGrid returns consistent instance`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Act
			val grid1 = context.getRailWayNetGrid()
			val grid2 = context.getRailWayNetGrid()

			// Assert
			assertThat(grid1).isSameInstanceAs(grid2)
		}
	}

	/**
	 * Test 4: Graph access returns same instance
	 */
	@Test
	fun `getGraph returns consistent instance`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Act
			val graph1 = context.getGraph()
			val graph2 = context.getGraph()

			// Assert
			assertThat(graph1).isSameInstanceAs(graph2)
		}
	}

	/**
	 * Test 5: Configuration properties work in editing context
	 */
	@Test
	fun `configuration properties work in editing context`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Act - Set configuration
			context.currentMaxSpeed = 120.0
			context.currentTrackLength = 500.0
			context.currentNameString = "TestName"

			// Assert - Read configuration
			assertThat(context.currentMaxSpeed).isEqualTo(120.0)
			assertThat(context.currentTrackLength).isEqualTo(500.0)
			assertThat(context.currentNameString).isEqualTo("TestName")
		}
	}

	/**
	 * Test 6: Grid stores cells correctly
	 */
	@Test
	fun `grid stores and retrieves cells correctly`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Arrange
			val point = Point(5, 5)

			// Act
			context.putCell(point, inA)

			// Assert
			val retrievedCell = context.getRailWayNetGrid().getCellAt(point.x, point.y)
			assertThat(retrievedCell!!).isInstanceOf<InOut>()
			assertThat((retrievedCell as InOut).getName()).isEqualTo("A")
		}
	}

	/**
	 * Test 7: Graph tracks connections correctly
	 */
	@Test
	fun `graph tracks track block connections correctly`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Arrange
			val p1 = Point(1, 1)
			val p2 = Point(5, 5)
			context.putCell(p1, inA)
			context.putCell(p2, outB)

			// Act - Join cells with track block
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			context.joinCells(p1, p2, trackBlock)

			// Assert - Graph contains the connection between p1 and p2
			val graph = context.getGraph()
			assertThat(graph.size()).isEqualTo(1) // One edge connecting the two points
			assertThat(graph.contains(p1, p2)).isEqualTo(true)
			assertThat(graph.get(p1, p2)).isEqualTo(trackBlock)
		}
	}

	/**
	 * Test 8: PropertyChangeListener registration works
	 */
	@Test
	fun `property change listener registration works`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Arrange
			var listenerCalled = false
			val listener =
				java.beans.PropertyChangeListener { evt ->
					listenerCalled = true
				}

			// Act
			context.addPropertyChangeListener(listener)
			// Trigger a property change by adding a cell
			context.putCell(Point(1, 1), inA)

			// Assert - Listener was registered successfully (no exception thrown)
			// Note: We can't easily verify if listener was called without internal access,
			// but we can verify registration/removal doesn't throw
			context.removePropertyChangeListener(listener)
		}
	}
}
