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
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test

class ContextTransformationPhasesTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@Test
	fun `copyGridCells copies all cells correctly`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)

		val simulationContext = simulationContextFactory.createContext(editingContext)

		val grid = simulationContext.getRailWayNetGrid()
		assertThat(grid.getCellAt(1, 1)).isNotNull()
		assertThat(grid.getCellAt(3, 3)).isNotNull()
		assertThat(grid.getCellAt(5, 5)).isNotNull()
		assertThat(grid.count()).isEqualTo(3)
	}

	@Test
	fun `copyGraphStructure copies all graph entries correctly`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(3, 3), track1)
		editingContext.joinCells(Point(3, 3), Point(5, 5), track2)
		val originalGraphSize = editingContext.getGraph().size()

		val simulationContext = simulationContextFactory.createContext(editingContext)

		val graph = simulationContext.getGraph()
		assertThat(graph.size()).isEqualTo(originalGraphSize)
		assertThat(graph.size()).isGreaterThan(0)
	}

	@Test
	fun `copyInOutList copies all InOut elements correctly`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		val originalInOutCount = editingContext.getInOuts().size

		val simulationContext = simulationContextFactory.createContext(editingContext)

		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts.size).isEqualTo(originalInOutCount)
		assertThat(inOuts.size).isEqualTo(2)
	}

	@Test
	fun `copyConfiguration copies all properties correctly`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.currentMaxSpeed = 120.0
		editingContext.currentTrackLength = 750.0
		editingContext.currentNameString = "TestConfig"

		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		assertThat(simulationContext.currentMaxSpeed).isEqualTo(120.0)
		assertThat(simulationContext.currentTrackLength).isEqualTo(750.0)
		assertThat(simulationContext.currentNameString).isEqualTo("TestConfig")
	}

	@Test
	fun `createDynamicMappings creates dynamic wrappers correctly`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(3, 3), semaphore)
		editingContext.putCell(Point(5, 5), outB)
		val track1 = SimpleTrackBlock(inA, semaphore, 500.0, 80.0)
		val track2 = SimpleTrackBlock(semaphore, outB, 500.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(3, 3), track1)
		editingContext.joinCells(Point(3, 3), Point(5, 5), track2)

		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		assertThat(simulationContext.getInOuts().size).isGreaterThan(0)
		for (dynInOut in simulationContext.getInOuts()) {
			assertThat(dynInOut.staticRef).isNotNull()
		}
	}

	@Test
	fun `validateTransformation completes without errors`() {
		val editingContext = editingContextFactory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		val simulationContext = simulationContextFactory.createContext(editingContext)

		assertThat(simulationContext).isNotNull()
		assertThat((simulationContext as DefaultSimulationContext).isFrozen).isTrue()
	}

	@Test
	fun `fromEditingContext orchestrates all phases correctly`() {
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

		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		assertThat(simulationContext.getRailWayNetGrid().count()).isGreaterThan(0)
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		assertThat(simulationContext.getInOuts().size).isEqualTo(2)
		assertThat(simulationContext.currentMaxSpeed).isEqualTo(100.0)
		assertThat(simulationContext.currentTrackLength).isEqualTo(600.0)
		assertThat(simulationContext.currentNameString).isEqualTo("TestNetwork")
		assertThat(simulationContext.getInOuts().first().staticRef).isNotNull()
		assertThat(simulationContext.isFrozen).isTrue()
	}

	@Test
	fun `empty editing context transforms without errors`() {
		val editingContext = editingContextFactory.createEmptyContext()

		val simulationContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext.getRailWayNetGrid().count()).isEqualTo(0)
		assertThat(simulationContext.getGraph().size()).isEqualTo(0)
		assertThat(simulationContext.getInOuts().size).isEqualTo(0)
		assertThat(simulationContext.isFrozen).isTrue()
	}
}
