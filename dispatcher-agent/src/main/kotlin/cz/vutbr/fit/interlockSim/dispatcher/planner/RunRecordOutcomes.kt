/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.sim.metrics.RunStall
import kotlinx.serialization.Serializable

/**
 * The watchdog stall of a persisted run (Issue #676, Goal 6 SP5): `RunStall` in a stable JSON shape.
 *
 * @property cycle Names of the trains in the wait-for cycle, each waiting for the next.
 * @property knot Names of all deadlocked trains, sorted.
 * @property persistedTicks Dispatcher ticks the deadlock persisted.
 * @property simTime Simulation time of the report.
 * @since Issue #676 (Goal 6 SP5)
 */
@Serializable
data class StallOutcome(
	val cycle: List<String>,
	val knot: List<String>,
	val persistedTicks: Long,
	val simTime: Double
)

/**
 * This run record as the persisted [RailwayOutcome]: every figure present, the per-kind maps keyed by
 * enum name (the [DispatcherRunSnapshot] map convention).
 *
 * @since Issue #676 (Goal 6 SP5)
 */
fun RunRecord.toRailwayOutcome(): RailwayOutcome =
	RailwayOutcome(
		journeysCompleted = journeysCompleted.toLong(),
		trainsEntered = trainsEntered.toLong(),
		trainsExited = trainsExited.toLong(),
		maxConcurrentTrains = maxConcurrentTrains.toLong(),
		blockTransitions = blockTransitions.toLong(),
		conflicts = conflicts.toLong(),
		failedReservations = failedReservations.toLong(),
		unresolvedAtRunEnd = unresolvedAtRunEnd.toLong(),
		wrongExits = wrongExits.toLong(),
		unfinishedJourneys = unfinishedJourneys.toLong(),
		failedReservationsByKind = failedReservationsByKind.byName(),
		entryFailures = entryFailures.toLong(),
		entryFailuresByKind = entryFailuresByKind.byName(),
		stall = stall?.toStallOutcome(),
		expiredHoldingCount = expiredHoldingCount.toLong(),
		expiredTailCount = expiredTailCount.toLong(),
		staleReleaseCount = staleReleaseCount.toLong(),
		partialReleaseCount = partialReleaseCount.toLong(),
		capacityShownToDispatcher = capacityShownToDispatcher?.toLong(),
		topologyCapacity = topologyCapacity?.toLong(),
		endTime = endTime,
		endTimeReached = endTimeReached,
		endClassification = classification
	)

/**
 * The outcome of a run read before it ended, so without a [RunRecord]: the eight row figures counted
 * so far, and every record figure absent -- the run had no end to record yet.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
fun RunEndSummary.toUnfinishedRunOutcome(): RailwayOutcome =
	RailwayOutcome(
		journeysCompleted = journeysCompleted.toLong(),
		trainsEntered = trainsEntered.toLong(),
		trainsExited = trainsExited.toLong(),
		maxConcurrentTrains = maxConcurrentTrains.toLong(),
		blockTransitions = blockTransitions.toLong(),
		conflicts = conflicts.toLong(),
		failedReservations = failedReservations.toLong(),
		unresolvedAtRunEnd = unresolvedAtRunEnd.toLong()
	)

private fun <K : Enum<K>> Map<K, Int>.byName(): Map<String, Long> =
	entries.associate { (kind, count) -> kind.name to count.toLong() }

private fun RunStall.toStallOutcome(): StallOutcome =
	StallOutcome(cycle = cycle, knot = knot.sorted(), persistedTicks = persistedTicks.toLong(), simTime = simTime)
