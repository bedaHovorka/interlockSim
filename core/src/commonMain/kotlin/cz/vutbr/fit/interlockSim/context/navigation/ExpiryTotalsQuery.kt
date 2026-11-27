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

/**
 * Read-only view of the two expiry totals the kernel-side reservation expiry keeps itself (Issue
 * #1360): how many whole holdings and how many stalled tails it has released completely since the
 * run began. Split out of [PathReservationService], like [ContentionQuery], so that [ReservationExpiry]
 * stays a `fun interface` with its one trigger and a reader -- the run-end metrics -- can hold the
 * totals alone.
 *
 * The totals are raised before each [ReservationExpired] is announced, so they do not depend on the
 * delivery of that event: a raw `onSimulationEvent` subscriber that throws ends the bus's delivery to
 * the subscribers after it on a bare kDisco bus (in a context run the Issue #1361 delivery guard
 * contains the failure), but it cannot change these counts. Reading them changes nothing.
 *
 * **Thread rules:** like the service, single-threaded: read on the simulation thread or after the run.
 * Until #1326 the dashboard's worker thread also reads them after a natural stop, when the simulation
 * thread has ended.
 *
 * @since Issue #1360
 */
interface ExpiryTotalsQuery {
	/**
	 * Whole holdings ([ReservationExpired.Kind.WHOLE_HOLDING]) released completely so far; a holding
	 * approach locking deferred counts once, when its release completes.
	 */
	val expiredHoldingCount: Int

	/**
	 * Stalled RESERVED tails of occupying trains ([ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL])
	 * released completely so far, counted once, on the call that frees the last un-entered block.
	 */
	val expiredTailCount: Int

	/**
	 * Expiry acts of whole holdings on the loop tick (row C1 of the #1475 counted automatics, author
	 * `EXPIRY`), counted once per act on the first call of its episode that changed railway state --
	 * a freed block or a signal dropped to STOP -- so a holding approach locking deferred counts on its
	 * deferring call, also when an LLM cancel or the run end finishes it (Issue #1476). Unlike
	 * [expiredHoldingCount], which counts only completed releases.
	 */
	val wholeHoldingExpiryActs: Int

	/** Expiry acts of stalled tails on the loop tick (row C2), counted like [wholeHoldingExpiryActs]. */
	val tailExpiryActs: Int

	/**
	 * Expiry acts, of either kind, run at the top of another train's request (row C3): the requester
	 * did not choose to release that route, so the act is the expiry's, counted like
	 * [wholeHoldingExpiryActs] and never under a loop-tick row.
	 */
	val requestExpiryActs: Int
}
