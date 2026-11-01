/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import kotlin.test.Test

/**
 * Unit tests for [RunPerformanceAccounting], the performance row of the metrics service (Issue #1306,
 * Goal 6 SP8), driven with synthetic events: no simulation runs, so no wall clock is read.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class RunPerformanceAccountingTest {
	private val accounting = RunPerformanceAccounting()

	private fun reservePathCalled(time: Double = 1.0) =
		accounting.onAccounting(RunAccountingEvent.ReservePathCalled("T1", time))

	private fun budgetExceeded(time: Double = 1.0) =
		accounting.onAccounting(
			RunAccountingEvent.RouteSearchBudgetExceeded("A", "B", nodeBudget = 5, expandedNodes = 5, time = time)
		)

	@Test
	fun `there is no performance row before the run has ended`() {
		reservePathCalled()

		assertThat(accounting.performance()).isNull()
	}

	@Test
	fun `the row counts reservePath calls and budget stops and divides simulated by wall time`() {
		repeat(3) { reservePathCalled() }
		budgetExceeded()
		accounting.onAccounting(RunAccountingEvent.RouteGranted("T1", 2.0))

		accounting.onRunEnded(RunEnded(600.0, endTimeReached = true, wallSeconds = 2.0))

		assertThat(accounting.performance()).isEqualTo(
			RunPerformance(
				wallSeconds = 2.0,
				simulationSeconds = 600.0,
				realTimeRatio = 300.0,
				routeSearchBudgetExceeded = 1,
				reservePathCalls = 3
			)
		)
	}

	@Test
	fun `a run that took no measurable wall time has no ratio`() {
		accounting.onRunEnded(RunEnded(600.0, endTimeReached = true, wallSeconds = 0.0))

		assertThat(accounting.performance()?.realTimeRatio).isNull()
	}
}
