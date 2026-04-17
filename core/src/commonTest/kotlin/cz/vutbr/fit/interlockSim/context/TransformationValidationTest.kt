/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test: Transformation Validation
	Issue #211: Add Transformation Validation

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test coverage: 2026-02-06
*/

package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.Test
import kotlin.test.assertIs
import org.koin.core.component.inject

class TransformationValidationTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@Test
	fun `validation succeeds for complete transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext.getInOuts().size).isEqualTo(2)
				assertThat(simulationContext.getGraph().size()).isEqualTo(1)
			}
		}
	}

	@Test
	fun `validation succeeds for complex network transformation`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			val inC = InOut("C", false, SpatialType.VERTICAL)
			val outD = InOut("D", true, SpatialType.VERTICAL)

			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			editingContext.putCell(Point(10, 10), inC)
			editingContext.putCell(Point(15, 15), outD)
			editingContext.putCell(Point(7, 7), semaphore)

			val trackBlock1 = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			val trackBlock2 = SimpleTrackBlock(inC, outD, 1200.0, 100.0)

			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock1)
			editingContext.joinCells(Point(10, 10), Point(15, 15), trackBlock2)

			editingContext.currentMaxSpeed = 120.0
			editingContext.currentTrackLength = 2200.0
			editingContext.currentNameString = "ComplexNetwork"

			simulationContextFactory.createContext(editingContext).use { simulationContext ->
				assertThat(simulationContext.getInOuts().size).isEqualTo(4)
				assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
				val simCtx = assertIs<DefaultSimulationContext>(simulationContext)
				assertThat(simCtx.currentMaxSpeed).isEqualTo(120.0)
				assertThat(simCtx.currentTrackLength).isEqualTo(2200.0)
				assertThat(simCtx.currentNameString).isEqualTo("ComplexNetwork")
			}
		}
	}
}
