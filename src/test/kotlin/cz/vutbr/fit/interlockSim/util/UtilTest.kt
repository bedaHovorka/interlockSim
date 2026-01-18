package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UtilTest {
	@Test
	fun `toClass array returns actual class for non-SimulationContext objects`() {
		val dummy = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val result = CellUtilities.toClass(arrayOf(dummy))
		assertThat(result.size).isEqualTo(1)
		assertThat(result[0]).isEqualTo(RailSemaphore::class.java)
	}

	@Test
	fun `toClass array returns classes for all objects`() {
		val obj1 = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val obj2 = "test string"
		val obj3 = 42
		val objects = arrayOf(obj1, obj2, obj3)
		
		val result = CellUtilities.toClass(objects)
		
		assertThat(result.size).isEqualTo(3)
		assertThat(result[0]).isEqualTo(RailSemaphore::class.java)
		assertThat(result[1]).isEqualTo(String::class.java)
		assertThat(result[2]).isEqualTo(Int::class.javaObjectType)
	}

	@Test
	fun `toClass array handles null values`() {
		val obj1 = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val objects = arrayOf<Any?>(obj1, null, "test")
		
		val result = CellUtilities.toClass(objects)
		
		assertThat(result.size).isEqualTo(3)
		assertThat(result[0]).isEqualTo(RailSemaphore::class.java)
		assertThat(result[1]).isNull()
		assertThat(result[2]).isEqualTo(String::class.java)
	}

	@Test
	fun `toClass array returns empty array for empty input`() {
		val objects = emptyArray<Any>()
		val result = CellUtilities.toClass(objects)
		assertThat(result.size).isEqualTo(0)
	}

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
		val result = Util.assertInstanceOf(NodeCell::class.java, dummy)
		assertThat(result).isSameInstanceAs(dummy)
	}

	@Test
	fun `assertInstanceOf throws AssertionError for wrong type`() {
		assertThrows<IllegalStateException> { Util.assertInstanceOf(NodeCell::class.java, "not a node cell") }
	}
}
