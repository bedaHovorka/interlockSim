/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop

/**
 * The run-end accounting of one run, counted by the metrics service from events alone, for every
 * harness (Issue #1304, Goal 6 SP6) -- no loop getter is read. The figures named like the
 * dispatcher-agent `RailwayOutcome` carry the same meaning, so the run record (SP5) can be built
 * from this value.
 *
 * Mid-run it holds the figures so far, with [classification] and [endTime] `null`; after
 * [cz.vutbr.fit.interlockSim.sim.RunEnded] they are set. Read it on the simulation thread or after
 * the run.
 *
 * @property journeysCompleted `MetricsSnapshot.completedTrains`: trains that released every block
 *   after moving.
 * @property trainsEntered Trains queued at their entry InOut (`TrainJourneyEvent.Queued`), the
 *   loops' `getTrainsEntered`.
 * @property trainsExited Trains whose front left through an InOut (`TrainJourneyEvent.Exited`).
 * @property wrongExits Exits through another InOut than the one the train was queued for
 *   (`ends in wrong out`, Issue #1276).
 * @property maxConcurrentTrains Peak number of trains admitted whose front had not yet reached an exit
 *   InOut. Not an occupancy safety figure: `ShuntingLoop` keeps a train in its approved list until the
 *   train's tail has cleared and its process ended, so this value is at most the loop's
 *   `getMaxConcurrentTrains`.
 * @property blockTransitions Granted reservation requests (`RunAccountingEvent.RouteGranted`).
 * @property conflicts `MetricsSnapshot.conflictCount`: mid-run double-bookings caught.
 * @property failedReservations Refused reservation requests, the sum of [failedReservationsByKind]. It counts
 * attempts: `MultiTrainLoop` reports a refusal on every retry tick, so one waiting train can add many.
 * @property failedReservationsByKind Refused requests per kind; kinds never seen are absent.
 * @property unresolvedAtRunEnd `MetricsSnapshot.unresolvedAtRunEndCount` (Issue #1280).
 * @property entryFailures `MultiTrainLoop` entries given up, the sum of [entryFailuresByKind].
 * @property entryFailuresByKind Entries given up per kind; kinds never seen are absent.
 * @property stall The watchdog stall, or `null` when the run did not stall.
 * @property expiredHoldingCount Kernel expiries of a whole holding (phase 1, Issue #1174).
 * @property expiredTailCount Kernel expiries of an occupying train's tail (phase 2, Issue #1220).
 * @property staleReleaseCount Whole un-travelled routes the orphan sweeper released.
 * @property partialReleaseCount Un-travelled tails the orphan sweeper released.
 * @property capacityShownToDispatcher Admission cap the dispatcher was given; `null` without one.
 * @property topologyCapacity Capacity of the station topology; `null` when not recorded.
 * @property unfinishedJourneys Trains admitted whose front has not (yet) reached an exit InOut. Like
 *   [maxConcurrentTrains] it is not an occupancy figure: a train whose front has left but whose tail is
 *   still on the last block no longer counts here.
 * @property endTime Kernel clock at run end; `null` before the run ended.
 * @property endTimeReached Whether the main loop reached its end time.
 * @property classification How the run ended; `null` before the run ended.
 * @property performance The performance row (Issue #1306, Goal 6 SP8); `null` before the run ended
 *   and whenever [MetricGroup.PERFORMANCE] is off.
 * @since Issue #1304 (Goal 6 SP6)
 */
data class RunEndSummary(
	val journeysCompleted: Int,
	val trainsEntered: Int,
	val trainsExited: Int,
	val wrongExits: Int,
	val maxConcurrentTrains: Int,
	val blockTransitions: Int,
	val conflicts: Int,
	val failedReservations: Int,
	val failedReservationsByKind: Map<FailedReservationKind, Int>,
	val unresolvedAtRunEnd: Int,
	val entryFailures: Int,
	val entryFailuresByKind: Map<MultiTrainLoop.EntryFailureKind, Int>,
	val stall: RunStall?,
	val expiredHoldingCount: Int,
	val expiredTailCount: Int,
	val staleReleaseCount: Int,
	val partialReleaseCount: Int,
	val capacityShownToDispatcher: Int?,
	val topologyCapacity: Int?,
	val unfinishedJourneys: Int,
	val endTime: Double?,
	val endTimeReached: Boolean,
	val classification: RunEndClassification?,
	val performance: RunPerformance? = null
)

/**
 * The deadlock watchdog's report as data (Issue #1304; Goal 9B SP7 demand 4).
 *
 * @property cycle Names of the trains in the wait-for cycle, each waiting for the next.
 * @property knot Names of all deadlocked trains (Issue #1270).
 * @property persistedTicks Dispatcher ticks the deadlock persisted.
 * @property simTime Simulation time of the report.
 * @since Issue #1304 (Goal 6 SP6)
 */
data class RunStall(
	val cycle: List<String>,
	val knot: Set<String>,
	val persistedTicks: Int,
	val simTime: Double
)
