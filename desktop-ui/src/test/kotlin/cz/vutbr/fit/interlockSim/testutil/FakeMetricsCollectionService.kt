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

import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot

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
 * @param unreleasedReservations what the leak gauge returns
 */
class FakeMetricsCollectionService(
	private val completedTrains: Int = 0,
	private val conflictCount: Int = 0,
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
			utilization = 0.0
		)

	override fun onSnapshot(listener: (MetricsSnapshot) -> Unit) = Unit

	override fun removeSnapshotListener(listener: (MetricsSnapshot) -> Unit) = Unit

	override fun reportUnreleasedReservations(): Set<String> {
		reportUnreleasedReservationsInvoked = true
		return unreleasedReservations
	}
}
