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
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.Test
import org.koin.core.component.inject

class ContextTypeParameterizationTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)

	@Test
	fun `EditingContext returns grid parameterized with NodeCell`() {
		val context = editingContextFactory.createEmptyContext()

		val grid = context.getRailWayNetGrid()

		assertThat(grid).isInstanceOf<RailwayNetGrid<*>>()

		context.putCell(Point(1, 1), inA)
		val cell = grid.getCellAt(1, 1)
		assertThat(cell!!).isInstanceOf<NodeCell>()
	}

	@Test
	fun `SimulationContext returns grid parameterized with Cell`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		val simulationContext = simulationContextFactory.createContext(editingContext)

		val grid = simulationContext.getRailWayNetGrid()

		assertThat(grid).isInstanceOf<RailwayNetGrid<*>>()

		val cell = grid.getCellAt(1, 1)
		assertThat(cell!!).isInstanceOf<Cell>()
	}

	@Test
	fun `grid parameterization enforced by type system`() {
		val editingContext = editingContextFactory.createEmptyContext()
		val simulationContext = simulationContextFactory.createEmptyContext()

		val editingGrid = editingContext.getRailWayNetGrid()
		val simulationGrid = simulationContext.getRailWayNetGrid()

		assertThat(editingGrid).isNotNull()
		assertThat(simulationGrid).isNotNull()
	}

	@Test
	fun `static dynamic separation maintained through transformation`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		val simulationContext = simulationContextFactory.createContext(editingContext)

		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts).isNotNull()
		for (dynInOut in inOuts) {
			assertThat(dynInOut.staticRef).isInstanceOf(InOut::class)
		}
	}

	@Test
	fun `covariant return types work in context hierarchy`() {
		val editingContext = editingContextFactory.createEmptyContext()
		val simulationContext = simulationContextFactory.createEmptyContext()

		val editingContextRef: EditingContext = editingContext
		val simulationContextRef: SimulationContext = simulationContext

		val editingGrid = editingContextRef.getRailWayNetGrid()
		assertThat(editingGrid).isInstanceOf(RailwayNetGrid::class)

		val simulationGrid = simulationContextRef.getRailWayNetGrid()
		assertThat(simulationGrid).isInstanceOf(RailwayNetGrid::class)
	}
}
