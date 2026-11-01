/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: a stand-in MetricsCollectionService
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsHistory
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.sim.metrics.TrainJourneyRecord

/**
 * A [MetricsCollectionService] that reports a fixed snapshot and records whether the leak gauge
 * was read.
 *
 * `DispatcherRunSummaries.railwayOutcomeFrom` reads `completedTrains` and `conflictCount` off the
 * snapshot, so a test that wants a particular railway outcome needs a service reporting those
 * numbers — but the real one needs a real run behind it. Two test classes each declared their own
 * stand-in for exactly this, one reporting zero journeys and one reporting a completed journey
 * (Issue #955, cluster U7).
 *
 * The listener methods are no-ops: `railwayOutcomeFrom` never subscribes.
 *
 * @param completedTrains journeys the snapshot reports as finished
 * @param conflictCount conflicts the snapshot reports
 * @param unresolvedAtRunEndCount contentions the snapshot reports as still open at run end (Issue #1280)
 * @param unreleasedReservations what the leak gauge returns
 */
class FakeMetricsCollectionService(
	private val completedTrains: Int = 0,
	private val conflictCount: Int = 0,
	private val unresolvedAtRunEndCount: Int = 0,
	private val unreleasedReservations: Set<String> = emptySet()
) : MetricsCollectionService {
	/** `true` once [reportUnreleasedReservations] has been called at least once. */
	var reportUnreleasedReservationsInvoked = false
		private set

	override fun getSnapshot(): MetricsSnapshot =
		MetricsSnapshot(
			time = 0.0,
			conflictCount = conflictCount,
			completedTrains = completedTrains,
			throughput = 0.0,
			totalWaitSeconds = 0.0,
			averageWaitSeconds = 0.0,
			occupiedBlocks = 0,
			totalBlocks = 0,
			utilization = 0.0,
			unresolvedAtRunEndCount = unresolvedAtRunEndCount
		)

	override fun onSnapshot(listener: (MetricsSnapshot) -> Unit) = Unit

	override fun removeSnapshotListener(listener: (MetricsSnapshot) -> Unit) = Unit

	override fun reportUnreleasedReservations(): Set<String> {
		reportUnreleasedReservationsInvoked = true
		return unreleasedReservations
	}

	override fun latestSnapshot(): MetricsSnapshot = getSnapshot()

	/** One empty buffer shared by every read, like the real service's single buffer. */
	private val emptyHistory = MetricsHistory()

	override fun history(): MetricsHistory = emptyHistory

	override fun journeys(): List<TrainJourneyRecord> = emptyList()

	/** The snapshot's figures; every run-end counter zero and the run not ended (Issue #1304). */
	override fun runEndSummary(): RunEndSummary =
		RunEndSummary(
			journeysCompleted = completedTrains,
			trainsEntered = 0,
			trainsExited = 0,
			wrongExits = 0,
			maxConcurrentTrains = 0,
			blockTransitions = 0,
			conflicts = conflictCount,
			failedReservations = 0,
			failedReservationsByKind = emptyMap(),
			unresolvedAtRunEnd = unresolvedAtRunEndCount,
			entryFailures = 0,
			entryFailuresByKind = emptyMap(),
			stall = null,
			expiredHoldingCount = 0,
			expiredTailCount = 0,
			staleReleaseCount = 0,
			partialReleaseCount = 0,
			capacityShownToDispatcher = null,
			topologyCapacity = null,
			unfinishedJourneys = 0,
			endTime = null,
			endTimeReached = false,
			classification = null
		)

	override fun recordCapacity(
		shownToDispatcher: Int,
		topologyCapacity: Int
	) = Unit

	/** No contention is ever open (Issue #1305). */
	override fun latestOpenContentions(): List<OpenContention> = emptyList()
}
