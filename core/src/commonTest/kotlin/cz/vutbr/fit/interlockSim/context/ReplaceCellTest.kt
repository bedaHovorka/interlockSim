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
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.CELL_ADDED
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.CELL_MODIFIED
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ReplaceCellTest : CommonKoinTestBase() {
	private val factory: EditingContextFactory by inject()

	@Test
	fun `replaceCell fires CELL_MODIFIED event`() {
		factory.createEmptyContext().use { ctx ->
			val pos = Point(1, 1)
			val original = InOut("A", false, SpatialType.HORIZONTAL)
			ctx.putCell(pos, original)

			val events = mutableListOf<ContextChangeEvent>()
			ctx.addPropertyChangeListener(ContextPropertyChangeListener { events.add(it) })

			ctx.replaceCell(pos, original.withName("B"))

			assertThat(events.filter { it.propertyName == CELL_MODIFIED }).hasSize(1)
		}
	}

	@Test
	fun `replaceCell does not fire CELL_ADDED event`() {
		factory.createEmptyContext().use { ctx ->
			val pos = Point(1, 1)
			val original = InOut("A", false, SpatialType.HORIZONTAL)
			ctx.putCell(pos, original)

			val events = mutableListOf<ContextChangeEvent>()
			ctx.addPropertyChangeListener(ContextPropertyChangeListener { events.add(it) })

			ctx.replaceCell(pos, original.withName("B"))

			assertThat(events.filter { it.propertyName == CELL_ADDED }).isEmpty()
		}
	}

	@Test
	fun `replaceCell updates InOut list when replacing InOut`() {
		factory.createEmptyContext().use { ctx ->
			val pos = Point(1, 1)
			val original = InOut("before", false, SpatialType.HORIZONTAL)
			ctx.putCell(pos, original)

			ctx.replaceCell(pos, original.withName("after"))

			val inouts = ctx.getInOuts()
			assertThat(inouts).hasSize(1)
			assertThat(inouts[0].getName()).isEqualTo("after")
		}
	}

	@Test
	fun `replaceCell leaves InOut list unchanged when replacing non-InOut`() {
		factory.createEmptyContext().use { ctx ->
			val inoutPos = Point(1, 1)
			val semPos = Point(5, 5)
			val inout = InOut("io", false, SpatialType.HORIZONTAL)
			val sem = RailSemaphore("sem", true, SpatialType.HORIZONTAL)
			ctx.putCell(inoutPos, inout)
			ctx.putCell(semPos, sem)

			ctx.replaceCell(semPos, sem.withName("newSem"))

			val inouts = ctx.getInOuts()
			assertThat(inouts).hasSize(1)
			assertThat(inouts[0].getName()).isEqualTo("io")
		}
	}

	@Test
	fun `replaceCell updates grid to new cell instance`() {
		factory.createEmptyContext().use { ctx ->
			val pos = Point(2, 2)
			val original = InOut("before", false, SpatialType.HORIZONTAL)
			ctx.putCell(pos, original)

			ctx.replaceCell(pos, original.withName("after"))

			val cell = ctx.getRailWayNetGrid().getCellAt(pos.x, pos.y) as? InOut
			assertThat(cell?.getName()).isEqualTo("after")
		}
	}

	@Test
	fun `replaceCell throws when no NodeCell at key`() {
		factory.createEmptyContext().use { ctx ->
			val emptyPos = Point(9, 9)
			val cell = InOut("new", false, SpatialType.HORIZONTAL)

			assertFailsWith<IllegalArgumentException> {
				ctx.replaceCell(emptyPos, cell)
			}
		}
	}

	@Test
	fun `replaceCell throws when replacing InOut with non-InOut`() {
		factory.createEmptyContext().use { ctx ->
			val pos = Point(1, 1)
			val original = InOut("io", false, SpatialType.HORIZONTAL)
			ctx.putCell(pos, original)

			val sem = RailSemaphore("s", false, SpatialType.HORIZONTAL)

			assertFailsWith<IllegalArgumentException> {
				ctx.replaceCell(pos, sem)
			}
		}
	}
}
