/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.sim.metrics.RunPerformance
import cz.vutbr.fit.interlockSim.sim.metrics.RunStall
import cz.vutbr.fit.interlockSim.sim.metrics.toRunRecord
import org.junit.jupiter.api.Test

/**
 * The persisted [RailwayOutcome] of a run record (Issue #676, Goal 6 SP5): every record figure is
 * carried, and a run that has not ended keeps only the row figures counted so far.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class RunRecordOutcomesTest {
	private val ended =
		RunEndSummary(
			journeysCompleted = 15,
			trainsEntered = 16,
			trainsExited = 15,
			wrongExits = 1,
			maxConcurrentTrains = 2,
			blockTransitions = 30,
			conflicts = 0,
			failedReservations = 2,
			failedReservationsByKind = mapOf(FailedReservationKind.ALL_PATHS_BLOCKED to 2),
			unresolvedAtRunEnd = 1,
			entryFailures = 1,
			entryFailuresByKind = mapOf(MultiTrainLoop.EntryFailureKind.NO_ROUTE to 1),
			stall = RunStall(listOf("T2", "T1"), setOf("T2", "T1"), persistedTicks = 3, simTime = 120.0),
			expiredHoldingCount = 4,
			expiredTailCount = 5,
			staleReleaseCount = 6,
			partialReleaseCount = 7,
			capacityShownToDispatcher = 2,
			topologyCapacity = 4,
			unfinishedJourneys = 1,
			endTime = 600.0,
			endTimeReached = true,
			classification = RunEndClassification.STALLED
		)

	@Test
	fun `a run record is persisted with every figure`() {
		val outcome = checkNotNull(ended.toRunRecord()).toRailwayOutcome()

		assertThat(outcome).isEqualTo(
			RailwayOutcome(
				journeysCompleted = 15L,
				trainsEntered = 16L,
				trainsExited = 15L,
				maxConcurrentTrains = 2L,
				blockTransitions = 30L,
				conflicts = 0L,
				failedReservations = 2L,
				unresolvedAtRunEnd = 1L,
				wrongExits = 1L,
				unfinishedJourneys = 1L,
				failedReservationsByKind = mapOf("ALL_PATHS_BLOCKED" to 2L),
				entryFailures = 1L,
				entryFailuresByKind = mapOf("NO_ROUTE" to 1L),
				stall = StallOutcome(cycle = listOf("T2", "T1"), knot = listOf("T1", "T2"), persistedTicks = 3L, simTime = 120.0),
				expiredHoldingCount = 4L,
				expiredTailCount = 5L,
				staleReleaseCount = 6L,
				partialReleaseCount = 7L,
				capacityShownToDispatcher = 2L,
				topologyCapacity = 4L,
				endTime = 600.0,
				endTimeReached = true,
				endClassification = RunEndClassification.STALLED
			)
		)
	}

	@Test
	fun `the performance row is persisted, and absent when the group was off`() {
		val performance =
			RunPerformance(
				wallSeconds = 1.25,
				simulationSeconds = 600.0,
				realTimeRatio = 480.0,
				routeSearchBudgetExceeded = 1,
				reservePathCalls = 32
			)

		val measured = checkNotNull(ended.copy(performance = performance).toRunRecord()).toRailwayOutcome()
		val off = checkNotNull(ended.toRunRecord()).toRailwayOutcome()

		assertThat(measured.wallSeconds, "wall seconds").isEqualTo(1.25)
		assertThat(measured.simulationSeconds, "simulated seconds").isEqualTo(600.0)
		assertThat(measured.realTimeRatio, "ratio").isEqualTo(480.0)
		assertThat(measured.routeSearchBudgetExceeded, "budget stops").isEqualTo(1L)
		assertThat(measured.reservePathCalls, "reservePath calls").isEqualTo(32L)
		assertThat(off.wallSeconds, "wall seconds, group off").isNull()
		assertThat(off.reservePathCalls, "reservePath calls, group off").isNull()
	}

	@Test
	fun `a figure the record did not get stays absent`() {
		val outcome =
			checkNotNull(ended.copy(stall = null, capacityShownToDispatcher = null, topologyCapacity = null).toRunRecord())
				.toRailwayOutcome()

		assertThat(outcome.stall, "stall").isNull()
		assertThat(outcome.capacityShownToDispatcher, "capacity shown").isNull()
		assertThat(outcome.topologyCapacity, "topology capacity").isNull()
	}

	@Test
	fun `a run that has not ended keeps only the figures of the latest snapshot`() {
		val snapshot =
			MetricsSnapshot(
				time = 12.0,
				conflictCount = 3,
				completedTrains = 15,
				throughput = 0.0,
				totalWaitSeconds = 0.0,
				averageWaitSeconds = 0.0,
				occupiedBlocks = 0,
				totalBlocks = 0,
				utilization = 0.0,
				unresolvedAtRunEndCount = 1
			)

		assertThat(snapshot.toUnfinishedRunOutcome()).isEqualTo(
			RailwayOutcome(journeysCompleted = 15L, conflicts = 3L, unresolvedAtRunEnd = 1L)
		)
	}
}
