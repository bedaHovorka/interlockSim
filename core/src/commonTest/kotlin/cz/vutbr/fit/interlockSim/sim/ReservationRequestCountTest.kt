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

	/**
	 * Calls only the public `(start, next)` overload of `reservePathToAnyNextSemaphore`, the one
	 * `InOutWorker` calls in production, then ends the run.
	 */
	private class NextSemaphoreRequester(
		private val ctx: DefaultSimulationContext
	) : LoopProcess() {
		override suspend fun iteration() {
			val entry = ctx.getInOuts().first()
			val next =
				checkNotNull(ctx.getRoutingServices().getTopologyNavigator().getNextTrackSection(entry, null)) {
					"forward section of the entry InOut"
				}
			ctx.getRoutingServices().getPathReservationService().reservePathToAnyNextSemaphore("T4", entry, next)
		}

		override suspend fun interLoopSleep() = terminate()
	}

	/** Runs [requester] as the main process and returns the run's counted public route requests. */
	private fun countedRequests(requester: (DefaultSimulationContext) -> LoopProcess): Int {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked().also { it.getInOuts() }
		ctx.setMainProcess(requester(ctx))
		ctx.run()
		val record = checkNotNull(ctx.getMetricsServices().getRunRecordSource().runRecord()) { "run record" }
		return checkNotNull(record.performance) { "performance row" }.reservePathCalls
	}

	@Test
	fun everyPublicReservationEntryPointIsCountedOnce() {
		assertThat(countedRequests(::Requester), name = "public route requests").isEqualTo(3)
	}

	@Test
	fun theNextSemaphoreOverloadWithAKnownSectionIsCountedOnce() {
		assertThat(countedRequests(::NextSemaphoreRequester), name = "public route requests").isEqualTo(1)
	}
}
