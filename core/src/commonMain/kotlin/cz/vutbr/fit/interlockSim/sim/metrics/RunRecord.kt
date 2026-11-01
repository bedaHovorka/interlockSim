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
 * The record of one finished run (Issue #676, Goal 6 SP5): the one shape every harness produces --
 * `ShuntingLoop`, `MultiTrainLoop` and the native `fast-sim` CLI alike -- and every consumer reads,
 * through [RunRecordSource]. No consumer needs `:desktop-ui` or a cast to the run's main process.
 *
 * It is the finished projection of the live [RunEndSummary]: the metrics service builds it with
 * [toRunRecord] once [cz.vutbr.fit.interlockSim.sim.RunEnded] has been delivered, so [classification]
 * and [endTime] are always set. The figures named like the dispatcher-agent `RailwayOutcome` carry
 * its meaning; the per-harness differences below are part of that meaning.
 *
 * @property journeysCompleted `MetricsSnapshot.completedTrains`: trains that released every block
 *   after moving.
 * @property trainsEntered Trains queued at their entry InOut (`TrainJourneyEvent.Queued`).
 * @property trainsExited Trains whose front reached an exit InOut (`TrainJourneyEvent.Exited`). The
 *   exit counts when the front reaches the InOut; `ShuntingLoop.getTrainsExited` counts a train at its
 *   next tick after the tail has cleared, so at an end-time cutoff the loop can lag this figure by one
 *   train.
 * @property wrongExits Exits through another InOut than the one the train was queued for.
 * @property maxConcurrentTrains Peak admitted trains: the most trains at one time that were admitted
 *   and whose front had not yet reached an exit InOut. The same meaning in every harness. It is not an
 *   occupancy safety figure (a train whose tail is still on the last block no longer counts).
 * @property blockTransitions Granted route requests (`RunAccountingEvent.RouteGranted`). The
 *   granularity differs per harness: `ShuntingLoop` grants 2 per vyhybna journey, `MultiTrainLoop`
 *   grants 1 entry route per started train. Do not compare it across harnesses.
 * @property conflicts `MetricsSnapshot.conflictCount`: mid-run double-bookings caught.
 * @property failedReservations Refused reservation requests, the sum of [failedReservationsByKind].
 * @property failedReservationsByKind Refused requests per kind; kinds never seen are absent.
 * @property unresolvedAtRunEnd `MetricsSnapshot.unresolvedAtRunEndCount` (Issue #1280).
 * @property entryFailures `MultiTrainLoop` entries given up, the sum of [entryFailuresByKind]. `0`
 *   also where it does not apply: only `MultiTrainLoop` gives entries up, and the metrics service
 *   cannot tell from events which harness ran.
 * @property entryFailuresByKind Entries given up per kind; kinds never seen are absent.
 * @property stall The watchdog stall; `null` when the run did not stall.
 * @property expiredHoldingCount Kernel expiries of a whole holding (Issue #1174).
 * @property expiredTailCount Kernel expiries of an occupying train's tail (Issue #1220).
 * @property staleReleaseCount Whole un-travelled routes the orphan sweeper released. `0` also when no
 *   sweeper was wired (only the dispatcher-agent stack wires one); [capacityShownToDispatcher] and
 *   the persisted arm tell the wirings apart.
 * @property partialReleaseCount Un-travelled tails the orphan sweeper released; `0` also when no
 *   sweeper was wired, like [staleReleaseCount].
 * @property capacityShownToDispatcher Admission cap the dispatcher was given; `null` without one.
 * @property topologyCapacity Capacity of the station topology; `null` when not recorded.
 * @property unfinishedJourneys Trains admitted whose front had not reached an exit InOut at run end.
 * @property endTime Kernel clock at run end.
 * @property endTimeReached Whether the main loop reached its end time.
 * @property classification How the run ended.
 * @since Issue #676 (Goal 6 SP5)
 */
data class RunRecord(
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
	val endTime: Double,
	val endTimeReached: Boolean,
	val classification: RunEndClassification
) {
	/**
	 * The record as one console line, starting `Run record:` -- printed by the native CLI after its
	 * `--- Simulation complete` summary, at run end and in its SIGINT partial summary. It does not
	 * start with `---`, so readers that take the last `---` line as that summary still find it. A
	 * figure nothing recorded reads `none`.
	 *
	 * @since Issue #676 (Goal 6 SP5)
	 */
	fun summaryLine(): String {
		val stallText = stall?.let { "${it.cycle} after ${it.persistedTicks} ticks at t=${it.simTime}" } ?: NONE
		val endText = if (endTimeReached) "end time reached" else "end time not reached"
		return "Run record: $classification at t=$endTime ($endText): " +
			"journeys $journeysCompleted, entered $trainsEntered, exited $trainsExited, " +
			"wrong exits $wrongExits, unfinished $unfinishedJourneys, peak admitted $maxConcurrentTrains, " +
			"granted route requests $blockTransitions, conflicts $conflicts, " +
			"failed reservations $failedReservations $failedReservationsByKind, " +
			"unresolved at run end $unresolvedAtRunEnd, entry failures $entryFailures $entryFailuresByKind, " +
			"stall $stallText, expiries holding $expiredHoldingCount tail $expiredTailCount, " +
			"sweeper releases stale $staleReleaseCount partial $partialReleaseCount, " +
			"capacity shown ${capacityShownToDispatcher ?: NONE} of topology ${topologyCapacity ?: NONE}"
	}

	private companion object {
		const val NONE = "none"
	}
}

/**
 * The [RunRecord] of this summary, or `null` while the run has not ended (no [RunEndSummary.classification]
 * or [RunEndSummary.endTime] yet).
 *
 * @since Issue #676 (Goal 6 SP5)
 */
fun RunEndSummary.toRunRecord(): RunRecord? {
	val endedAs = classification ?: return null
	val endedAt = endTime ?: return null
	return RunRecord(
		journeysCompleted = journeysCompleted,
		trainsEntered = trainsEntered,
		trainsExited = trainsExited,
		wrongExits = wrongExits,
		maxConcurrentTrains = maxConcurrentTrains,
		blockTransitions = blockTransitions,
		conflicts = conflicts,
		failedReservations = failedReservations,
		failedReservationsByKind = failedReservationsByKind,
		unresolvedAtRunEnd = unresolvedAtRunEnd,
		entryFailures = entryFailures,
		entryFailuresByKind = entryFailuresByKind,
		stall = stall,
		expiredHoldingCount = expiredHoldingCount,
		expiredTailCount = expiredTailCount,
		staleReleaseCount = staleReleaseCount,
		partialReleaseCount = partialReleaseCount,
		capacityShownToDispatcher = capacityShownToDispatcher,
		topologyCapacity = topologyCapacity,
		unfinishedJourneys = unfinishedJourneys,
		endTime = endedAt,
		endTimeReached = endTimeReached,
		classification = endedAs
	)
}
