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

import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import kotlinx.serialization.Serializable

/**
 * What the railway actually achieved during one dispatcher run.
 *
 * Every other figure in [DispatcherRunSnapshot] describes *decision hygiene* — how many ticks the
 * planner completed, how many of its actions were rejected, whether the rule fallback engaged.
 * None of it says whether a single train moved. Issue #895 states the consequence plainly: *"The
 * gate scores decision hygiene, not outcomes. … If #893's fix works, these move; if only the gate
 * moves, it did not."* #834's sweep ranks parameter cells, and it cannot rank them on whether the
 * railway worked unless the run JSON carries these numbers.
 *
 * ## Absent is not zero
 *
 * Every field is nullable, and `null` means **not measured** — never "measured as none". The
 * distinction is load-bearing, not stylistic:
 *
 * - `trainsEntered = 0` is a finding: the dispatcher admitted nothing for the whole run.
 * - `trainsEntered = null` is the absence of a finding: nothing in this run was in a position to
 *   count admissions -- a file written before the figure existed, or (before schema version 11) a
 *   main process that kept no such counter.
 *
 * Collapsing the two would reproduce exactly the failure mode
 * #847's sweep recorded under *"Structurally empty columns"*, where a
 * column of hardcoded zeroes was read for several rounds as a measured result. Serialising these
 * as JSON `null` keeps the two apart on disk as well as in memory.
 *
 * Absence is expressed **per field**, never by omitting the whole object: a run read before it ended
 * has the row figures but no record figures, and a file of an older schema has only the figures its
 * version knew.
 *
 * ## Where the values come from
 *
 * From schema version 11 (Issue #676, Goal 6 SP5) every figure comes from the run's
 * `cz.vutbr.fit.interlockSim.sim.metrics.RunRecord`, the record the metrics service fills at run
 * end for every harness (`ShuntingLoop`, `MultiTrainLoop`), mapped by [toRailwayOutcome]. No loop
 * getter is read and no cast to the main process is made, so a `MultiTrainLoop` run has the same
 * figures as a `ShuntingLoop` one. A run that has not ended yet when it is read (a GUI stop racing
 * the simulation thread) has no record; it keeps the three figures of the latest metrics
 * snapshot ([toUnfinishedRunOutcome]) and every other figure stays absent. Sourced by
 * `DispatcherRunSummaries.railwayOutcomeFrom` (`:desktop-ui`) and handed to
 * [DispatcherRunRecorder.recordRailwayOutcome] before [DispatcherRunRecorder.finish] freezes the
 * snapshot.
 *
 * Files of schema version 10 and older carry none of the record figures (they decode as `null`), and
 * their `trainsEntered`, `trainsExited`, `maxConcurrentTrains`, `blockTransitions` and
 * `failedReservations` were the `ShuntingLoop` getters: `null` for any other main process.
 *
 * @property journeysCompleted Trains that released every block they held, i.e. finished their
 *   journey and left the network (`MetricsSnapshot.completedTrains`).
 * @property trainsEntered Trains queued at their entry InOut (`RunRecord.trainsEntered`).
 * @property trainsExited Trains whose front reached an exit InOut (`RunRecord.trainsExited`). The
 *   exit counts when the front reaches the InOut; the loop's own counter lags one tick behind it, so
 *   at an end-time cutoff a version-11 file can read one train more than a version-10 one.
 * @property maxConcurrentTrains Peak admitted trains: the most trains at one time that were admitted
 *   and whose front had not yet reached an exit InOut (`RunRecord.maxConcurrentTrains`). The same
 *   meaning in every harness.
 * @property blockTransitions Granted route requests (`RunRecord.blockTransitions`). The granularity
 *   differs per harness -- `ShuntingLoop` grants 2 per vyhybna journey, `MultiTrainLoop` 1 entry
 *   route per started train -- so do not compare it across harnesses. It is still the figure that
 *   separated a working railway from a busy dispatcher in the #847 sweep (Issue #1304 review M4).
 * @property conflicts Reservation conflicts detected while the run was live — a double-booking the
 *   registry caught and rolled back (`MetricsSnapshot.conflictCount`). From schema version 10
 *   (Issue #1280) it no longer includes the contentions still open at run end; those are
 *   [unresolvedAtRunEnd]. A file of version 9 or older counted both here.
 * @property failedReservations Refused reservation requests (`RunRecord.failedReservations`).
 * @property unresolvedAtRunEnd Blocked-path contentions (a train waiting for a block another train
 *   holds) still open when the run ended (`MetricsSnapshot.unresolvedAtRunEndCount`, Issue #1280).
 *   `0` when every waiting train got its route. Non-zero is either the Issue #612
 *   deadlock/starvation indicator or — on a run cut off at its end time with trains still on the
 *   network, which a run fed by an endless train generator always is — queueing the cutoff
 *   interrupted; it is never a double-booking. `null` in files written before schema version 10.
 *
 * The figures below came with schema version 11 (Issue #676); each is `null` in older files and in a
 * run that had not ended when it was read.
 *
 * @property wrongExits Exits through another InOut than the one the train was queued for.
 * @property unfinishedJourneys Trains admitted whose front had not reached an exit InOut at run end.
 * @property failedReservationsByKind Refused requests per `FailedReservationKind` name; kinds never
 *   seen are absent from the map.
 * @property entryFailures `MultiTrainLoop` entries given up. `0` = none, which includes "does not
 *   apply" for any other main process (the record cannot tell the harness from its events).
 * @property entryFailuresByKind Entries given up per `MultiTrainLoop.EntryFailureKind` name.
 * @property stall The watchdog stall. With [endClassification] present, `null` means the run did not
 *   stall; with both `null`, nothing was measured.
 * @property expiredHoldingCount Kernel expiries of a whole holding (Issue #1174).
 * @property expiredTailCount Kernel expiries of an occupying train's tail (Issue #1220).
 * @property staleReleaseCount Whole un-travelled routes the orphan sweeper released. `0` = none,
 *   which includes "no sweeper wired": only a run with a dispatcher agent has one.
 * @property partialReleaseCount Un-travelled tails the orphan sweeper released; `0` as for
 *   [staleReleaseCount].
 * @property capacityShownToDispatcher Admission cap the dispatcher was given; `null` without one.
 * @property topologyCapacity Capacity of the station topology; `null` when not recorded.
 * @property endTime Kernel clock at run end, in simulated seconds.
 * @property endTimeReached Whether the main loop reached its end time.
 * @property endClassification How the run ended, as the metrics service classified it. The recorded
 *   [RunEndCause] is decided separately and is not replaced by it.
 *
 * The figures below are the run record's performance row (`RunRecord.performance`, schema version
 * 12, Issue #1306, Goal 6 SP8). Each is `null` in older files, in a run that had not ended when it
 * was read, and in a run whose `PERFORMANCE` metric group was off. They measure the machine as much
 * as the railway: the wall figures vary from run to run and with load.
 *
 * @property wallSeconds Wall-clock seconds the simulation kernel ran (not the JVM start-up or the
 *   network loading).
 * @property simulationSeconds Simulated seconds the run reached.
 * @property realTimeRatio [simulationSeconds] divided by [wallSeconds]; `null` also when the wall time
 *   was not above zero.
 * @property routeSearchBudgetExceeded Route searches stopped at their node budget (Issue #1151).
 * @property reservePathCalls Calls of the public `PathReservationService.reservePath`.
 *
 * @see DispatcherRunSnapshot.railwayOutcome
 * @since Issue #834 (SP2c.11 — record what the railway achieved, not just decision hygiene)
 */
@Serializable
data class RailwayOutcome(
	val journeysCompleted: Long? = null,
	val trainsEntered: Long? = null,
	val trainsExited: Long? = null,
	val maxConcurrentTrains: Long? = null,
	val blockTransitions: Long? = null,
	val conflicts: Long? = null,
	val failedReservations: Long? = null,
	val unresolvedAtRunEnd: Long? = null,
	val wrongExits: Long? = null,
	val unfinishedJourneys: Long? = null,
	val failedReservationsByKind: Map<String, Long>? = null,
	val entryFailures: Long? = null,
	val entryFailuresByKind: Map<String, Long>? = null,
	val stall: StallOutcome? = null,
	val expiredHoldingCount: Long? = null,
	val expiredTailCount: Long? = null,
	val staleReleaseCount: Long? = null,
	val partialReleaseCount: Long? = null,
	val capacityShownToDispatcher: Long? = null,
	val topologyCapacity: Long? = null,
	val endTime: Double? = null,
	val endTimeReached: Boolean? = null,
	val endClassification: RunEndClassification? = null,
	val wallSeconds: Double? = null,
	val simulationSeconds: Double? = null,
	val realTimeRatio: Double? = null,
	val routeSearchBudgetExceeded: Long? = null,
	val reservePathCalls: Long? = null
) {
	companion object {
		/**
		 * The all-absent outcome: nothing about this run's railway was measured.
		 *
		 * This is the default for [DispatcherRunSnapshot.railwayOutcome], which is what keeps
		 * schema-version-1 run files readable — see that property's KDoc.
		 */
		val UNMEASURED: RailwayOutcome = RailwayOutcome()
	}
}
