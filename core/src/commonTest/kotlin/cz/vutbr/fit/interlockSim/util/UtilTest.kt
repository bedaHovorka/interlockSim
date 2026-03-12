package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UtilTest {
	@Test
	fun `assertNodeCell returns NodeCell when correct type`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = CellUtilities.assertNodeCell(dummy)
		assertThat(result).isSameInstanceAs(dummy)
	}

	@Test
	fun `assertNodeCell throws AssertionError for wrong type`() {
		val dummy = "String"
		assertThrows<IllegalStateException> { CellUtilities.assertNodeCell(dummy) }
	}

	@Test
	fun `assertInstanceOf returns casted instance when correct type`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = Util.assertInstanceOf<NodeCell>(dummy)
		assertThat(result).isSameInstanceAs(dummy)
	}

	@Test
	fun `assertInstanceOf throws AssertionError for wrong type`() {
		assertThrows<IllegalStateException> { Util.assertInstanceOf<NodeCell>("not a node cell") }
	}
}
