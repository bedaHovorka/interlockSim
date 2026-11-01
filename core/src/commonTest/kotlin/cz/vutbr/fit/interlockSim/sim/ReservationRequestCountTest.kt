/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * The `reservePath` count of the performance row (Issue #1306, Goal 6 SP8, fix round 1) counts every
 * public route request once: `reservePath`, `reservePathToAny` and both `reservePathToAnyNextSemaphore`
 * overloads (the oriented one delegates to the other and is not counted twice). The internal
 * per-target attempts are not counted.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class ReservationRequestCountTest : CommonKoinTestBase() {
	/** Makes one call of each public entry point, then ends the run. */
	private class Requester(
		private val ctx: DefaultSimulationContext
	) : LoopProcess() {
		override suspend fun iteration() {
			val service = ctx.getRoutingServices().getPathReservationService()
			val (entry, exit) = ctx.getInOuts().toList()
			service.reservePath("T1", entry, exit)
			service.reservePathToAny("T2", entry)
			service.reservePathToAnyNextSemaphore("T3", entry)
		}

		override suspend fun interLoopSleep() = terminate()
	}

	@Test
	fun everyPublicReservationEntryPointIsCountedOnce() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked().also { it.getInOuts() }
		ctx.setMainProcess(Requester(ctx))

		ctx.run()

		val performance =
			checkNotNull(
				ctx
					.getMetricsServices()
					.getRunRecordSource()
					.runRecord()
					?.performance
			)
		assertThat(performance.reservePathCalls, name = "public route requests").isEqualTo(3)
	}
}
