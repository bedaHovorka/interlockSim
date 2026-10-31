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
 * The counter slots [blockedSeconds] and [expiries] are part of the record from SP2 on but are not
 * filled yet: later Goal 6 lanes (SP6 #1304, SP7 #1305) feed them. Until then they read zero.
 *
 * @property trainId Name of the train.
 * @property entryInOut Name of the InOut it entered at.
 * @property entryTime Simulation time it was queued at the entry InOut.
 * @property admissionTime Simulation time it was admitted (its process started).
 * @property plannedExit Name of the InOut its timetable sent it to, as known when it was queued.
 * @property actualExit Name of the InOut it left through.
 * @property exitTime Simulation time its front reached [actualExit].
 * @property blockedSeconds Total simulated seconds the train stood blocked; zero until SP7 fills it.
 * @property expiries Reservation expiries suffered on the journey; zero until SP7 fills it.
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
	val expiries: Int = 0
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
