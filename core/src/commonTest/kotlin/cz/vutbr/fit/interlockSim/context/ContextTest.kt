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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test

class ContextTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()
	private lateinit var context: SimulationContext
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val tl: SimpleTrackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
	private val rs1: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	override fun afterKoinSetUp() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			val pA = Point(1, 1)
			val r1 = Point(4, 2)
			val pB = Point(5, 5)
			editingContext.putCell(pA, inA)
			editingContext.putCell(pB, outB)
			editingContext.putCell(r1, rs1)
			editingContext.joinCells(r1, pB, tl)
			editingContext.joinCells(pA, r1, tl)

			context = simulationContextFactory.createContext(editingContext)
			testContext = context
		}
	}

	@Test
	fun testGetNextTrackBlock() {
		val dynamicBlock = context.getNextTrackBlock(inA, null)

		assertThat(dynamicBlock).isNotNull()
		assertThat(context.getNextTrackBlock(outB, null)).isNotNull()

		assertThat(context.getNextTrackBlock(inA, dynamicBlock)).isNull()
		assertThat(context.getNextTrackBlock(outB, dynamicBlock)).isNull()
	}

	@Test
	fun testIsSeparatorInDirection() {
		assertThat(context.isSeparatorInDirection(inA, null, tl)).isTrue()
		assertThat(context.isSeparatorInDirection(outB, null, tl)).isTrue()
		assertThat(context.isSeparatorInDirection(inA, tl, null)).isTrue()
		assertThat(context.isSeparatorInDirection(outB, tl, null)).isTrue()
		assertThat(rs1.direction()).isNotNull()
	}
}
