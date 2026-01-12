package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UtilTest {

	@Test
	fun `toClass returns actual class for non-SimulationContext`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = Util.toClass(dummy)
		assertThat(result).isEqualTo(RailSemaphore::class.java)
	}

	@Test
	fun `assertNodeCell returns NodeCell when correct type`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = Util.assertNodeCell(dummy)
		assertThat(result).isSameInstanceAs(dummy)
	}

	@Test
	fun `assertNodeCell throws AssertionError for wrong type`() {
		val dummy = "String"
		assertThrows<AssertionError> { Util.assertNodeCell(dummy) }
	}

	@Test
	fun `assertInstanceOf returns casted instance when correct type`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = Util.assertInstanceOf(NodeCell::class.java, dummy)
		assertThat(result).isSameInstanceAs(dummy)
	}

	@Test
	fun `assertInstanceOf throws AssertionError for wrong type`() {
		assertThrows<AssertionError> { Util.assertInstanceOf(NodeCell::class.java, "not a node cell") }
	}
}
