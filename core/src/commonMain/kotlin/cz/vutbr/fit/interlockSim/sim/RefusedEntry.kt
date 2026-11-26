/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

/**
 * Why an [InOutWorker] refused the entry route of the train at the head of its queue (Issue #1457).
 *
 * @property text The reason as the run-end residue log and the run record line print it.
 * @since Issue #1457 (Goal 9B SP3.2)
 */
enum class EntryRefusalKind(
	val text: String
) {
	/**
	 * The entry route was refused as
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NonContiguousStart]:
	 * it does not join the footprint the train already holds (typically a route set up from the wrong
	 * InOut). The train waits at its InOut until the route is granted.
	 */
	NON_CONTIGUOUS("entry refused: non-contiguous")
}

/**
 * A train waiting at its entry InOut because the [InOutWorker] refused its entry route (Issue #1457):
 * the refused-entry state of one worker ([InOutWorker.refusedEntry]), and one row of the run record's
 * `refusedEntriesAtRunEnd` when the wait lasted to the end of the run.
 *
 * The wait is actionable work for a dispatcher: the route that blocks the entry is the dispatcher's
 * to cancel. A watchdog may read [since] to bound how long such work stays undone.
 *
 * @property trainId Name of the refused train.
 * @property inOutName Name of the InOut the train waits at.
 * @property kind Why the entry was refused.
 * @property since Simulation time of the first refusal of the running wait.
 * @since Issue #1457 (Goal 9B SP3.2)
 */
data class RefusedEntry(
	val trainId: String,
	val inOutName: String,
	val kind: EntryRefusalKind,
	val since: Double
) {
	/** The entry as one phrase: `T at InOut since t (entry refused: non-contiguous)`. */
	fun describe(): String = "$trainId at $inOutName since t=$since (${kind.text})"
}
