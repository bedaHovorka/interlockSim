/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * One blocked-path contention that is still open: [trainId] asked for a route, every candidate was
 * blocked, and the train has not been granted a route since (Issue #1305, Goal 6 SP7; Goal 9B
 * demand D1 of `docs/goal9b-demands/SP12.1-contention-tracking.md`).
 *
 * An immutable value. [block] is the live domain object: off the simulation thread use only its
 * identity (`equals`/`hashCode` follow its static block), never its dynamic state.
 *
 * @property trainId The waiting train.
 * @property block The first busy block of the refused request, the one the contention was recorded on.
 * @property owner The train that keeps [block] from [trainId] now, as [BlockOwnerQuery.getBlockOwner]
 *   names it when the snapshot is taken; `null` when no train holds the block any more (the waiting
 *   train simply has not asked again yet).
 * @property since Simulation time the contention was first observed.
 * @since Issue #1305 (Goal 6 SP7)
 */
data class OpenContention(
	val trainId: String,
	val block: DynamicTrackBlock,
	val owner: String?,
	val since: Double
)

/**
 * Read-only view of the blocked-path contention tracking of [PathReservationService] (Issue #1305,
 * Goal 6 SP7): which contentions are open now and how long each train has stood blocked. Split out
 * of [PathReservationService], like [BlockOwnerQuery], to keep that interface within the detekt
 * function budget.
 *
 * Nothing here changes the tracking: reading it never clears a contention and never emits an event.
 * A contention opens when [PathReservationService.reservePath] refuses a train with
 * [PathReservationService.ReservationResult.AllPathsBlocked], and closes for the whole train at once
 * on that train's next reservation `Success`, on its whole-route release, or in the end-of-run
 * [PathReservationService.flushUnresolvedConflicts]. A release of a train that holds no block does
 * not close it (the SP12.1 quirk, pinned): such a train stays blocked to the run end.
 *
 * **Thread rules:** like the service, single-threaded: call it on the simulation thread or after the
 * run. A reader on another thread (a dashboard) reads the copy the metrics service publishes,
 * `MetricsCollectionService.latestOpenContentions`.
 *
 * @since Issue #1305 (Goal 6 SP7)
 */
interface ContentionQuery {
	/**
	 * The contentions open now, in the order they were first observed; a copy. Empty after the
	 * end-of-run flush.
	 *
	 * @since Issue #1305 (Goal 6 SP7)
	 */
	fun openContentions(): List<OpenContention>

	/**
	 * Simulated seconds each train has stood blocked so far, keyed by train id; trains never blocked
	 * are absent. One blocked episode of a train runs from its earliest open contention to the moment
	 * all of them close, so contentions on several blocks at once count once. An episode still open
	 * counts up to [now]; the end-of-run flush closes every open one at the run's end time.
	 *
	 * @param now Simulation time to measure the open episodes to.
	 * @since Issue #1305 (Goal 6 SP7)
	 */
	fun blockedSeconds(now: Double): Map<String, Double>
}
