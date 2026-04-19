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
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test

class ContextTransformationTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@Test
	fun `transform empty editing context to simulation context`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext).isNotNull()
				assertThat(simulationContext.getRailWayNetGrid()).isNotNull()
				assertThat(simulationContext.getGraph()).isNotNull()
			}
		}
	}

	@Test
	fun `transform simple network editing to simulation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
				val inOuts = simulationContext.getInOuts()
				assertThat(inOuts.size).isGreaterThan(0)
				assertThat(inOuts.size).isEqualTo(2)
			}
		}
	}

	@Test
	fun `transform complex network editing to simulation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
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

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
				val inOuts = simulationContext.getInOuts()
				assertThat(inOuts.size).isEqualTo(2)
				val semaphoreCell = simulationContext.getRailWayNetGrid().getCellAt(p2.x, p2.y)
				assertThat(semaphoreCell).isNotNull()
			}
		}
	}

	@Test
	fun `properties preserved during transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.currentMaxSpeed = 120.0
			editingContext.currentTrackLength = 750.0
			editingContext.currentNameString = "TestTransform"

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat((simulationContext as? DefaultSimulationContext)?.currentMaxSpeed).isEqualTo(120.0)
				assertThat((simulationContext as? DefaultSimulationContext)?.currentTrackLength).isEqualTo(750.0)
				assertThat((simulationContext as? DefaultSimulationContext)?.currentNameString).isEqualTo("TestTransform")
			}
		}
	}

	@Test
	fun `graph structure preserved during transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
			val editingGraphSize = editingContext.getGraph().size()

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext.getGraph().size()).isEqualTo(editingGraphSize)
			}
		}
	}

	@Test
	fun `dynamic mapping created correctly during transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				val inOuts = simulationContext.getInOuts()
				assertThat(inOuts.size).isGreaterThan(0)
				for (dynInOut in inOuts) {
					assertThat(dynInOut).isInstanceOf(DynamicInOut::class)
					assertThat(dynInOut.staticRef).isNotNull()
				}
			}
		}
	}

	@Test
	fun `simulation context frozen after transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext).isInstanceOf(DefaultSimulationContext::class)
				assertThat((simulationContext as DefaultSimulationContext).isFrozen()).isTrue()
			}
		}
	}

	@Test
	fun `multiple transformations work correctly`() {
		editingContextFactory.createEmptyContext().use { editing1 ->
			editing1.putCell(Point(1, 1), inA)
			editing1.putCell(Point(5, 5), outB)
			val trackBlock1 = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editing1.joinCells(Point(1, 1), Point(5, 5), trackBlock1)
			simulationContextFactory.createContext(editing1).use { sim1 ->
				assertThat(sim1.getGraph().size()).isGreaterThan(0)

				editingContextFactory.createEmptyContext().use { editing2 ->
					val inA2 = InOut("A2", false, SpatialType.HORIZONTAL)
					val outB2 = InOut("B2", true, SpatialType.HORIZONTAL)
					editing2.putCell(Point(2, 2), inA2)
					editing2.putCell(Point(6, 6), outB2)
					val trackBlock2 = SimpleTrackBlock(inA2, outB2, 1000.0, 80.0)
					editing2.joinCells(Point(2, 2), Point(6, 6), trackBlock2)
					simulationContextFactory.createContext(editing2).use { sim2 ->
						assertThat(sim2.getGraph().size()).isGreaterThan(0)

						assertThat(sim1.getInOuts().first().name).isEqualTo("A")
						assertThat(sim2.getInOuts().first().name).isEqualTo("A2")
					}
				}
			}
		}
	}

	@Test
	fun `simulation grid stores dynamic cells not static cells`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				val grid = simulationContext.getRailWayNetGrid()

				val cellAt1 = grid.getCellAt(1, 1)
				assertThat(cellAt1!!).isInstanceOf(DynamicInOut::class)
				val dynInOut1 = cellAt1 as DynamicInOut
				assertThat(dynInOut1.staticRef).isNotNull()

				val cellAt2 = grid.getCellAt(5, 5)
				assertThat(cellAt2!!).isInstanceOf(DynamicInOut::class)
				val dynInOut2 = cellAt2 as DynamicInOut
				assertThat(dynInOut2.staticRef).isNotNull()

				val inOuts = simulationContext.getInOuts()
				assertThat(inOuts.size).isEqualTo(2)
				for (inout in inOuts) {
					assertThat(inout).isInstanceOf(DynamicInOut::class)
					assertThat(inout.staticRef).isInstanceOf(InOut::class)
				}
			}
		}
	}
}
