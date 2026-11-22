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
 * the subscribers after it, but cannot change these counts. Reading them changes nothing.
 *
 * **Thread rules:** like the service, single-threaded: read on the simulation thread or after the run.
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
}
