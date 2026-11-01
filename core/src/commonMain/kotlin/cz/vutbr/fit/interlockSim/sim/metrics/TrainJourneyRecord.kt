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

/**
 * What one train did on one journey through the network (Issue #673, Goal 6 SP2), built by
 * [JourneyRecorder] from the [cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent] stream.
 *
 * A field stays `null` while its step has not happened, and also when the scenario does not report
 * it: a train whose scenario emits no queue step has no [entryInOut], [entryTime] or [plannedExit].
 *
 * The wait figures ([queueSeconds], [blockedSeconds], [noRouteStandingSeconds] and the expiry
 * counts) are filled by the [MetricGroup.TRAIN_WAIT] group (Issue #1305, Goal 6 SP7); with that
 * group off they keep their defaults. Figures kept per train rather than per journey (blocked time,
 * standing time, expiries) go on the train's latest journey; train names are unique within a run.
 *
 * @property trainId Name of the train.
 * @property entryInOut Name of the InOut it entered at.
 * @property entryTime Simulation time it was queued at the entry InOut.
 * @property admissionTime Simulation time it was admitted (its process started).
 * @property plannedExit Name of the InOut its timetable sent it to, as known when it was queued.
 * @property actualExit Name of the InOut it left through.
 * @property exitTime Simulation time its front reached [actualExit].
 * @property blockedSeconds Total simulated seconds the train stood blocked: the time it had an open
 *   blocked-path contention ([cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery.blockedSeconds]).
 *   Only a refusal whose first busy block has an owning train opens a contention; a refusal on a
 *   locked switch, or on a busy block no train is attributed to, opens none and counts only in
 *   [noRouteStandingSeconds]. So this is the time spent behind another train's block, at most the
 *   time waited with no route.
 * @property expiries Reservation expiries the train suffered, both kinds:
 *   [expiredHoldingCount] + [expiredTailCount].
 * @property queueSeconds Simulated seconds from the queue step to the admission; for a train never
 *   admitted, to its entry failure or to the run end (so far, while the run goes on). `null` when the
 *   scenario reported no queue step (Issue #1305).
 * @property noRouteStandingSeconds Simulated seconds the admitted train waited with no route: from a
 *   refusal of kind `ALL_PATHS_BLOCKED` or `NO_ROUTE` until its next route grant, its exit or the run
 *   end (Issue #1305). Derived from the refusal and grant events; no event says the train stands.
 * @property expiredHoldingCount Whole-holding expiries (`ReservationExpired.Kind.WHOLE_HOLDING`) of the
 *   train (Issue #1305).
 * @property expiredTailCount Tail expiries of the train standing on its route
 *   (`ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL`, Issue #1305).
 * @since Issue #673 (Goal 6 SP2)
 */
data class TrainJourneyRecord(
	val trainId: String,
	val entryInOut: String? = null,
	val entryTime: Double? = null,
	val admissionTime: Double? = null,
	val plannedExit: String? = null,
	val actualExit: String? = null,
	val exitTime: Double? = null,
	val blockedSeconds: Double = 0.0,
	val expiries: Int = 0,
	val queueSeconds: Double? = null,
	val noRouteStandingSeconds: Double = 0.0,
	val expiredHoldingCount: Int = 0,
	val expiredTailCount: Int = 0
) {
	/**
	 * `true` when the train left through another InOut than its timetable planned — the
	 * `ends in wrong out` count of Issue #1276. `false` while either end is unknown.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	val wrongExit: Boolean
		get() = plannedExit != null && actualExit != null && plannedExit != actualExit
}
