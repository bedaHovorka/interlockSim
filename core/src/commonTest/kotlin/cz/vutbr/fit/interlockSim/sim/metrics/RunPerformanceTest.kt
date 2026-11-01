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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import kotlin.test.Test

/**
 * The performance row of a run record as one console line (Issue #1306, Goal 6 SP8), printed by the
 * JVM `example` mode and the native CLI alike.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class RunPerformanceTest {
	private val record =
		RunRecord(
			journeysCompleted = 15,
			trainsEntered = 16,
			trainsExited = 15,
			wrongExits = 0,
			maxConcurrentTrains = 2,
			blockTransitions = 30,
			conflicts = 0,
			failedReservations = 2,
			failedReservationsByKind = emptyMap(),
			unresolvedAtRunEnd = 0,
			entryFailures = 0,
			entryFailuresByKind = emptyMap(),
			stall = null,
			expiredHoldingCount = 0,
			expiredTailCount = 0,
			staleReleaseCount = 0,
			partialReleaseCount = 0,
			capacityShownToDispatcher = 2,
			topologyCapacity = 2,
			unfinishedJourneys = 0,
			endTime = 601.0,
			endTimeReached = true,
			classification = RunEndClassification.NATURAL_COMPLETION,
			performance =
				RunPerformance(
					wallSeconds = 1.25,
					simulationSeconds = 601.0,
					realTimeRatio = 480.8,
					routeSearchBudgetExceeded = 1,
					reservePathCalls = 32
				)
		)

	@Test
	fun `the performance line names every figure of the row with its unit`() {
		val line = record.performanceLine()

		assertThat(line).startsWith("Performance: ")
		assertThat(line).contains("wall 1.25 s")
		assertThat(line).contains("simulated 601.0 s")
		assertThat(line).contains("real-time ratio 480.8x")
		assertThat(line).contains("peak admitted 2")
		assertThat(line).contains("route-search budget exceeded 1")
		assertThat(line).contains("reservePath calls 32")
	}

	@Test
	fun `a run without the performance group says the row was not measured`() {
		assertThat(record.copy(performance = null).performanceLine())
			.isEqualTo("Performance: not measured (metric group PERFORMANCE is off)")
	}

	@Test
	fun `a run without a measurable wall time has no ratio`() {
		val noRatio = record.copy(performance = record.performance?.copy(wallSeconds = 0.0, realTimeRatio = null))

		assertThat(noRatio.performanceLine()).contains("real-time ratio none")
	}
}
