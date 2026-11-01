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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import kotlin.test.Test

/**
 * Unit tests for [RunRecord], the published record of a finished run (Issue #676, Goal 6 SP5):
 * built from a [RunEndSummary] only once the run has ended, and printed as one summary line.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class RunRecordTest {
	private val stall = RunStall(listOf("T1", "T2"), setOf("T1", "T2"), persistedTicks = 3, simTime = 120.0)

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
			stall = stall,
			expiredHoldingCount = 4,
			expiredTailCount = 5,
			staleReleaseCount = 6,
			partialReleaseCount = 7,
			capacityShownToDispatcher = 2,
			topologyCapacity = 4,
			unfinishedJourneys = 1,
			endTime = 600.0,
			endTimeReached = true,
			classification = RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS
		)

	@Test
	fun `a run that has not ended has no record`() {
		assertThat(ended.copy(classification = null, endTime = null).toRunRecord()).isNull()
	}

	@Test
	fun `the record of an ended run carries every run-end figure of its summary`() {
		val record = ended.toRunRecord()

		assertThat(record).isNotNull()
		assertThat(record).isEqualTo(
			RunRecord(
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
				stall = stall,
				expiredHoldingCount = 4,
				expiredTailCount = 5,
				staleReleaseCount = 6,
				partialReleaseCount = 7,
				capacityShownToDispatcher = 2,
				topologyCapacity = 4,
				unfinishedJourneys = 1,
				endTime = 600.0,
				endTimeReached = true,
				classification = RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS
			)
		)
	}

	@Test
	fun `the summary line names the end classification and the row figures`() {
		val line = checkNotNull(ended.toRunRecord()).summaryLine()

		assertThat(line).startsWith("Run record: CUTOFF_WITH_UNFINISHED_JOURNEYS at t=600.0")
		assertThat(line).contains("journeys 15")
		assertThat(line).contains("entered 16")
		assertThat(line).contains("exited 15")
		assertThat(line).contains("peak admitted 2")
		assertThat(line).contains("granted route requests 30")
		assertThat(line).contains("failed reservations 2 {ALL_PATHS_BLOCKED=2}")
		assertThat(line).contains("unresolved at run end 1")
		assertThat(line).contains("entry failures 1 {NO_ROUTE=1}")
		assertThat(line).contains("stall [T1, T2] after 3 ticks at t=120.0")
		assertThat(line).contains("capacity shown 2 of topology 4")
	}

	@Test
	fun `the summary line says none for a figure nothing recorded`() {
		val line = checkNotNull(ended.copy(stall = null, capacityShownToDispatcher = null).toRunRecord()).summaryLine()

		assertThat(line).contains("stall none")
		assertThat(line).contains("capacity shown none of topology 4")
	}
}
