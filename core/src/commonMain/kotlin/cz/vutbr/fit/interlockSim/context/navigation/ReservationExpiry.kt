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
 * Kernel-side reservation expiry (Issue #1174, absorbs #975): a route granted to a train that never
 * enters it does not outlive its train, whoever reserved it and whether or not the dispatcher's
 * `OrphanReservationSweeper` runs.
 *
 * The rule, the TTL and the release are [DefaultReservationExpiry]'s; this interface is only the
 * trigger. It is a superinterface of [PathReservationService], split out the way
 * [ApproachLockedPathRelease] and [BlockReleaseSteps] are, so that a caller which must not hold the
 * whole service -- `ShuntingLoop`, which deliberately holds no reservation handle (Issue #1152) -- can
 * reach the trigger alone through [RoutingServices.getReservationExpiry]. The
 * [DefaultPathReservationService] stays the one facade: it implements this interface by delegating
 * to the internal [DefaultReservationExpiry] it constructs.
 *
 * It stays an interface of its own, rather than a method on [RoutingServices], because
 * `ShuntingLoop` reaches the tick through [RoutingServices.getReservationExpiry] and holds no
 * reservation service (Issue #1152); a method on the accessor-only [RoutingServices] would sit away
 * from [PathReservationService], where the expiry state and the release live (Issue #1229).
 *
 * @since Issue #1174
 */
fun interface ReservationExpiry {
	/**
	 * Release every stale holding: a train whose held blocks are all RESERVED and none of them
	 * entered, and whose newest block registration is at least the TTL old in simulation time.
	 * A train that occupies any block never expires. The release honours approach locking, so a
	 * holding may take two calls to disappear (the first resets its signals to STOP and frees what
	 * is safe, a later one frees the rest).
	 *
	 * Runs on the simulation thread, like every other reservation call.
	 *
	 * @return the number of holdings this call released completely; a holding whose release
	 *   approach locking deferred counts once, on the call that completes it
	 */
	fun expireStaleReservations(): Int
}
