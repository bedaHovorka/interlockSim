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
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.SweeperReleaseKind
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import kotlin.test.Test

/**
 * Unit tests for [RunEndAccounting], the run-end counters of the metrics service (Issue #1304,
 * Goal 6 SP6), driven with synthetic events: no simulation runs.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
class RunEndAccountingTest {
	private val accounting = RunEndAccounting()

	private fun summary(): RunEndSummary = accounting.summary(EMPTY)

	private fun queue(
		train: String,
		entry: String = "A",
		plannedExit: String = "B",
		time: Double = 0.0
	) = accounting.onJourney(TrainJourneyEvent.Queued(train, entry, plannedExit, time))

	private fun admit(
		train: String,
		time: Double = 1.0
	) = accounting.onJourney(TrainJourneyEvent.Admitted(train, time))

	private fun exit(
		train: String,
		exitInOut: String = "B",
		time: Double = 2.0
	) = accounting.onJourney(TrainJourneyEvent.Exited(train, exitInOut, time))

	private fun expiry(kind: ReservationExpired.Kind) =
		accounting.onExpired(ReservationExpired("T1", kind, emptyList(), emptyList(), 61.0, 60.0, 61.0))

	private fun entryFailure(kind: MultiTrainLoop.EntryFailureKind) =
		RunAccountingEvent.EntryFailed(MultiTrainLoop.EntryFailure("T1", "A", "B", kind, 1, null, 3.0))

	@Test
	fun `nothing recorded gives an all-zero summary with no classification`() {
		val s = summary()

		assertThat(s.trainsEntered).isZero()
		assertThat(s.trainsExited).isZero()
		assertThat(s.maxConcurrentTrains).isZero()
		assertThat(s.blockTransitions).isZero()
		assertThat(s.failedReservations).isZero()
		assertThat(s.failedReservationsByKind).isEmpty()
		assertThat(s.entryFailures).isZero()
		assertThat(s.entryFailuresByKind).isEmpty()
		assertThat(s.stall).isNull()
		assertThat(s.capacityShownToDispatcher).isNull()
		assertThat(s.topologyCapacity).isNull()
		assertThat(s.endTime).isNull()
		assertThat(s.endTimeReached).isFalse()
		assertThat(s.classification).isNull()
	}

	@Test
	fun `queued trains count as entered, exits as exited, and a different exit as a wrong exit`() {
		queue("T1", plannedExit = "B")
		queue("T2", plannedExit = "A")
		queue("T3", plannedExit = "B")
		admit("T1")
		admit("T2")
		exit("T1", exitInOut = "B")
		exit("T2", exitInOut = "B")

		val s = summary()
		assertThat(s.trainsEntered).isEqualTo(3)
		assertThat(s.trainsExited).isEqualTo(2)
		assertThat(s.wrongExits).isEqualTo(1)
	}

	@Test
	fun `an exit without a queue step is counted but never a wrong exit`() {
		admit("T1")
		exit("T1", exitInOut = "C")

		assertThat(summary().trainsExited).isEqualTo(1)
		assertThat(summary().wrongExits).isZero()
	}

	@Test
	fun `max concurrent is the peak of admitted trains not yet exited`() {
		admit("T1", time = 1.0)
		admit("T2", time = 2.0)
		exit("T1", time = 3.0)
		admit("T3", time = 4.0)
		exit("T2", time = 5.0)
		exit("T3", time = 6.0)

		assertThat(summary().maxConcurrentTrains).isEqualTo(2)
		assertThat(summary().unfinishedJourneys).isZero()
	}

	@Test
	fun `route grants are block transitions and refusals are counted by kind`() {
		accounting.onAccounting(RunAccountingEvent.RouteGranted("T1", 1.0))
		accounting.onAccounting(RunAccountingEvent.RouteGranted("T1", 3.0))
		accounting.onAccounting(RunAccountingEvent.ReservationRefused("T2", FailedReservationKind.ALL_PATHS_BLOCKED, 3.0))
		accounting.onAccounting(RunAccountingEvent.ReservationRefused("T2", FailedReservationKind.ALL_PATHS_BLOCKED, 5.0))
		accounting.onAccounting(RunAccountingEvent.ReservationRefused("T3", FailedReservationKind.CONFLICT, 5.0))

		val s = summary()
		assertThat(s.blockTransitions).isEqualTo(2)
		assertThat(s.failedReservations).isEqualTo(3)
		assertThat(s.failedReservationsByKind).isEqualTo(
			mapOf(FailedReservationKind.ALL_PATHS_BLOCKED to 2, FailedReservationKind.CONFLICT to 1)
		)
	}

	@Test
	fun `entry failures are counted by kind and keep their records`() {
		accounting.onAccounting(entryFailure(MultiTrainLoop.EntryFailureKind.NO_ROUTE))
		accounting.onAccounting(entryFailure(MultiTrainLoop.EntryFailureKind.NO_ROUTE))
		accounting.onAccounting(entryFailure(MultiTrainLoop.EntryFailureKind.UNKNOWN_IN_OUT))

		val s = summary()
		assertThat(s.entryFailures).isEqualTo(3)
		assertThat(s.entryFailuresByKind).isEqualTo(
			mapOf(MultiTrainLoop.EntryFailureKind.NO_ROUTE to 2, MultiTrainLoop.EntryFailureKind.UNKNOWN_IN_OUT to 1)
		)
	}

	@Test
	fun `a stall is kept with its cycle, knot, ticks and time`() {
		accounting.onAccounting(RunAccountingEvent.Stalled(listOf("T1", "T2"), setOf("T1", "T2", "T3"), 2, 14.0))

		val stall = summary().stall
		assertThat(stall).isNotNull()
		assertThat(stall!!.cycle).isEqualTo(listOf("T1", "T2"))
		assertThat(stall.knot).containsOnly("T1", "T2", "T3")
		assertThat(stall.persistedTicks).isEqualTo(2)
		assertThat(stall.simTime).isEqualTo(14.0)
	}

	@Test
	fun `expiries and sweeper releases are counted by kind`() {
		expiry(ReservationExpired.Kind.WHOLE_HOLDING)
		expiry(ReservationExpired.Kind.WHOLE_HOLDING)
		expiry(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL)
		accounting.onAccounting(RunAccountingEvent.SweeperReleased("T1", SweeperReleaseKind.STALE, 70.0))
		accounting.onAccounting(RunAccountingEvent.SweeperReleased("T1", SweeperReleaseKind.PARTIAL, 72.0))
		accounting.onAccounting(RunAccountingEvent.SweeperReleased("T2", SweeperReleaseKind.PARTIAL, 74.0))

		val s = summary()
		assertThat(s.expiredHoldingCount).isEqualTo(2)
		assertThat(s.expiredTailCount).isEqualTo(1)
		assertThat(s.staleReleaseCount).isEqualTo(1)
		assertThat(s.partialReleaseCount).isEqualTo(2)
	}

	@Test
	fun `capacity shown to the dispatcher is kept next to the topology capacity`() {
		accounting.recordCapacity(shownToDispatcher = 2, topologyCapacity = 4)

		assertThat(summary().capacityShownToDispatcher).isEqualTo(2)
		assertThat(summary().topologyCapacity).isEqualTo(4)
	}

	@Test
	fun `the snapshot supplies journeys, conflicts and unresolved contentions`() {
		val s =
			accounting.summary(
				EMPTY.copy(completedTrains = 7, conflictCount = 1, unresolvedAtRunEndCount = 3)
			)

		assertThat(s.journeysCompleted).isEqualTo(7)
		assertThat(s.conflicts).isEqualTo(1)
		assertThat(s.unresolvedAtRunEnd).isEqualTo(3)
	}

	@Test
	fun `every admitted train exited by the end time is a natural completion`() {
		queue("T1")
		admit("T1")
		exit("T1")
		queue("T2", time = 590.0)
		accounting.onRunEnded(RunEnded(601.0, endTimeReached = true))

		val s = summary()
		assertThat(s.endTime).isEqualTo(601.0)
		assertThat(s.endTimeReached).isTrue()
		assertThat(s.classification).isEqualTo(RunEndClassification.NATURAL_COMPLETION)
	}

	@Test
	fun `an admitted train still on the network at the end time is a cutoff`() {
		admit("T1")
		admit("T2")
		exit("T1")
		accounting.onRunEnded(RunEnded(601.0, endTimeReached = true))

		assertThat(summary().unfinishedJourneys).isEqualTo(1)
		assertThat(summary().classification).isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
	}

	@Test
	fun `a stall wins over everything else`() {
		admit("T1")
		accounting.onAccounting(RunAccountingEvent.Stalled(listOf("T2", "T3"), setOf("T2", "T3"), 2, 9.0))
		accounting.onRunEnded(RunEnded(9.0, endTimeReached = false))

		assertThat(summary().classification).isEqualTo(RunEndClassification.STALLED)
	}

	@Test
	fun `a run that stopped before its end time without a stall terminated early`() {
		admit("T1")
		exit("T1")
		accounting.onRunEnded(RunEnded(42.0, endTimeReached = false))

		assertThat(summary().classification).isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	@Test
	fun `classify follows the order stall, end time, unfinished journeys`() {
		assertThat(RunEndClassification.classify(stalled = true, endTimeReached = true, unfinishedJourneys = 0))
			.isEqualTo(RunEndClassification.STALLED)
		assertThat(RunEndClassification.classify(stalled = false, endTimeReached = true, unfinishedJourneys = 1))
			.isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
		assertThat(RunEndClassification.classify(stalled = false, endTimeReached = true, unfinishedJourneys = 0))
			.isEqualTo(RunEndClassification.NATURAL_COMPLETION)
		assertThat(RunEndClassification.classify(stalled = false, endTimeReached = false, unfinishedJourneys = 1))
			.isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	private companion object {
		val EMPTY =
			MetricsSnapshot(
				time = 0.0,
				conflictCount = 0,
				completedTrains = 0,
				throughput = 0.0,
				totalWaitSeconds = 0.0,
				averageWaitSeconds = 0.0,
				occupiedBlocks = 0,
				totalBlocks = 0,
				utilization = 0.0
			)
	}
}
