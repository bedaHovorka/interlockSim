/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test

class WithNameTest {
	private val mainSpeed = 50.0
	private val branchSpeed = 20.0

	// ===== RailSwitch.withName() =====

	@Test
	fun `RailSwitch withName returns new instance with updated name`() {
		val switch = RailSwitch("original", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val renamed = switch.withName("renamed")
		assertThat(renamed.getName()).isEqualTo("renamed")
	}

	@Test
	fun `RailSwitch withName leaves original unchanged`() {
		val switch = RailSwitch("original", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		switch.withName("renamed")
		assertThat(switch.getName()).isEqualTo("original")
	}

	@Test
	fun `RailSwitch withName preserves mainSpeed`() {
		val switch = RailSwitch("sw", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE, mainSpeed, branchSpeed)
		val renamed = switch.withName("new")
		assertThat(renamed.speeds.getValue(RailSwitch.Conf.MAIN)).isEqualTo(mainSpeed)
	}

	@Test
	fun `RailSwitch withName preserves branchSpeed`() {
		val switch = RailSwitch("sw", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE, mainSpeed, branchSpeed)
		val renamed = switch.withName("new")
		assertThat(renamed.speeds.getValue(RailSwitch.Conf.BRANCH)).isEqualTo(branchSpeed)
	}

	@Test
	fun `RailSwitch withName preserves spatialType`() {
		val switch = RailSwitch("sw", Cell.SpatialType.VERTICAL, RailSwitch.Type.SIMPLE_LEFT_TRUE, mainSpeed, branchSpeed)
		val renamed = switch.withName("new")
		assertThat(renamed.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)
	}

	@Test
	fun `RailSwitch withName preserves type`() {
		val switch = RailSwitch("sw", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE, mainSpeed, branchSpeed)
		val renamed = switch.withName("new")
		assertThat(renamed.type).isEqualTo(RailSwitch.Type.SIMPLE_LEFT_FALSE)
	}

	@Test
	fun `RailSwitch withName returns different object`() {
		val switch = RailSwitch("sw", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val renamed = switch.withName("other")
		assertThat(renamed === switch).isFalse()
	}

	// ===== RailSemaphore.withName() =====

	@Test
	fun `RailSemaphore withName returns new instance with updated name`() {
		val sem = RailSemaphore("original", true, Cell.SpatialType.HORIZONTAL)
		val renamed = sem.withName("renamed")
		assertThat(renamed.getName()).isEqualTo("renamed")
	}

	@Test
	fun `RailSemaphore withName leaves original unchanged`() {
		val sem = RailSemaphore("original", true, Cell.SpatialType.HORIZONTAL)
		sem.withName("renamed")
		assertThat(sem.getName()).isEqualTo("original")
	}

	@Test
	fun `RailSemaphore withName preserves orientation true`() {
		val sem = RailSemaphore("s", true, Cell.SpatialType.HORIZONTAL)
		val renamed = sem.withName("new")
		assertThat(renamed.getOrientation()).isEqualTo(true)
	}

	@Test
	fun `RailSemaphore withName preserves orientation false`() {
		val sem = RailSemaphore("s", false, Cell.SpatialType.HORIZONTAL)
		val renamed = sem.withName("new")
		assertThat(renamed.getOrientation()).isEqualTo(false)
	}

	@Test
	fun `RailSemaphore withName preserves spatialType`() {
		val sem = RailSemaphore("s", false, Cell.SpatialType.VERTICAL)
		val renamed = sem.withName("new")
		assertThat(renamed.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)
	}

	@Test
	fun `RailSemaphore withName returns different object`() {
		val sem = RailSemaphore("s", false, Cell.SpatialType.HORIZONTAL)
		val renamed = sem.withName("other")
		assertThat(renamed === sem).isFalse()
	}

	// ===== InOut.withName() =====

	@Test
	fun `InOut withName returns new instance with updated name`() {
		val inout = InOut("original", true, Cell.SpatialType.HORIZONTAL)
		val renamed = inout.withName("renamed")
		assertThat(renamed.getName()).isEqualTo("renamed")
	}

	@Test
	fun `InOut withName leaves original unchanged`() {
		val inout = InOut("original", true, Cell.SpatialType.HORIZONTAL)
		inout.withName("renamed")
		assertThat(inout.getName()).isEqualTo("original")
	}

	@Test
	fun `InOut withName preserves orientation true`() {
		val inout = InOut("io", true, Cell.SpatialType.HORIZONTAL)
		val renamed = inout.withName("new")
		assertThat(renamed.getOrientation()).isEqualTo(true)
	}

	@Test
	fun `InOut withName preserves orientation false`() {
		val inout = InOut("io", false, Cell.SpatialType.HORIZONTAL)
		val renamed = inout.withName("new")
		assertThat(renamed.getOrientation()).isEqualTo(false)
	}

	@Test
	fun `InOut withName preserves spatialType`() {
		val inout = InOut("io", false, Cell.SpatialType.VERTICAL)
		val renamed = inout.withName("new")
		assertThat(renamed.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)
	}

	@Test
	fun `InOut withName returns different object`() {
		val inout = InOut("io", false, Cell.SpatialType.HORIZONTAL)
		val renamed = inout.withName("other")
		assertThat(renamed === inout).isFalse()
	}
}
