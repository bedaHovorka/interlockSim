/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.GridTransformer
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test

/**
 * Tests for [Train.semaphoreLogName], the name the `semaphoreAction` log lines print (Issue #1339).
 *
 * An InOut's embedded in- and out-semaphores have no name, so a train's entry logged
 * `detected at semaphore , signal=S80`. Each case below covers one branch of the helper; the
 * real SENSOR line of an entering train is pinned end to end by `TrainSensorLogNameTest`.
 */
class TrainSemaphoreLogNameTest {
	private val inOutB: DynamicInOut = inOut("B")

	private fun inOut(name: String): DynamicInOut =
		GridTransformer.createDynamic(InOut(name, true, Cell.SpatialType.HORIZONTAL))

	private fun semaphore(name: String? = null): DynamicRailSemaphore =
		createDynamicInstance(RailSemaphore(name, true, Cell.SpatialType.HORIZONTAL))

	@Test
	fun aNamedSemaphorePrintsItsOwnName() {
		val zB = semaphore("zB")
		assertThat(Train.semaphoreLogName(zB, zB)).isEqualTo("zB")
	}

	@Test
	fun theEntrySemaphoreOfAnInOutPrintsTheInOutAsEntry() {
		assertThat(Train.semaphoreLogName(inOutB.inSemaphore, inOutB)).isEqualTo("B (entry)")
	}

	@Test
	fun theExitSemaphoreOfAnInOutPrintsTheInOutAsExit() {
		assertThat(Train.semaphoreLogName(inOutB.outSemaphore, inOutB)).isEqualTo("B (exit)")
	}

	@Test
	fun anUnnamedGridSemaphorePrintsTheUnnamedLabel() {
		val unnamed = semaphore()
		assertThat(Train.semaphoreLogName(unnamed, unnamed)).isEqualTo(Station.UNNAMED)
	}

	@Test
	fun anUnnamedSemaphoreThatIsNotTheInOutsOwnPrintsTheUnnamedLabel() {
		assertThat(Train.semaphoreLogName(semaphore(), inOutB)).isEqualTo(Station.UNNAMED)
	}

	@Test
	fun theEntrySemaphoreOfAnUnnamedInOutPrintsTheUnnamedLabel() {
		val unnamedInOut = inOut("")
		assertThat(Train.semaphoreLogName(unnamedInOut.inSemaphore, unnamedInOut)).isEqualTo(Station.UNNAMED)
	}
}
