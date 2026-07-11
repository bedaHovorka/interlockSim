/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for GridScanUtils.cellsOfType()
 * Goal 10 code-review fix — reuse fix for duplicated grid-scan cache builders
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("GridScanUtils.cellsOfType()")
class GridScanUtilsTest {
	/** A 3x1 grid mocked the same way ports tests build fake grids (getCellAt/cols/rows only). */
	private fun grid(cells: Map<Pair<Int, Int>, Cell?>): RailwayNetGrid<Cell> {
		val g = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { g.cols } returns 3
		every { g.rows } returns 1
		for (x in 0 until 3) {
			every { g.getCellAt(x, 0) } returns cells[x to 0]
		}
		return g
	}

	@Test
	@DisplayName("returns only cells of the requested type, ignoring others and empty cells")
	fun returnsOnlyMatchingType() {
		val semA = mockk<DynamicRailSemaphore>(relaxed = true)
		val semB = mockk<DynamicRailSemaphore>(relaxed = true)
		val sw = mockk<DynamicRailSwitch>(relaxed = true)
		val g = grid(mapOf((0 to 0) to semA, (1 to 0) to sw, (2 to 0) to semB))

		assertThat(g.cellsOfType<DynamicRailSemaphore>()).containsExactlyInAnyOrder(semA, semB)
		assertThat(g.cellsOfType<DynamicRailSwitch>()).containsExactlyInAnyOrder(sw)
	}

	@Test
	@DisplayName("returns an empty list when no cell matches")
	fun returnsEmptyWhenNoMatch() {
		val g = grid(mapOf((0 to 0) to mockk<DynamicInOut>(relaxed = true)))

		assertThat(g.cellsOfType<DynamicRailSemaphore>()).isEmpty()
	}

	@Test
	@DisplayName("returns an empty list for a fully empty grid")
	fun returnsEmptyForEmptyGrid() {
		val g = grid(emptyMap())

		assertThat(g.cellsOfType<DynamicRailSemaphore>()).isEmpty()
	}
}
