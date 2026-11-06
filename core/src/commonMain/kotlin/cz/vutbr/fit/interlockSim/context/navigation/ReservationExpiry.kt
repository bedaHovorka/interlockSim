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
 * `OrphanReservationSweeper` runs; nor does the tail of a route its train has stopped advancing
 * along (phase 2, Issue #1220).
 *
 * The rule, the TTL and the release are [DefaultReservationExpiry]'s; this interface is only the
 * trigger. It is a superinterface of [PathReservationService], split out the way
 * [ApproachLockedPathRelease] and [BlockReleaseSteps] are, so that a caller which must not hold the
 * whole service -- `ShuntingLoop`, which deliberately holds no reservation handle (Issue #1152) -- can
 * reach the trigger alone through [RoutingServices.getReservationExpiry]. The
 * [DefaultPathReservationService] stays the one facade: it implements this interface by delegating
 * to the internal [DefaultReservationExpiry] it constructs.
 *
 * It stays an interface of its own, rather than a method on the accessor-only [RoutingServices],
 * which would sit away from [PathReservationService], where the expiry state and the release live
 * (Issue #1229).
 *
 * Every completed expiry is announced as a [ReservationExpired] custom event (Issue #1243); deferrals
 * announce nothing.
 *
 * @since Issue #1174
 */
fun interface ReservationExpiry {
	/**
	 * Release every stale holding: a train whose held blocks are all RESERVED and none of them
	 * entered, and whose newest block registration is at least the TTL old in simulation time.
	 * Since Issue #1220 also the stalled tail of a train on the track: a train that stands on part
	 * of its holding and has made no progress for the TTL loses its un-entered RESERVED blocks. The
	 * blocks a train stands on never expire. The release honours approach locking, so a holding may
	 * take two calls to disappear (the first resets its signals to STOP and frees what is safe, a
	 * later one frees the rest).
	 *
	 * Runs on the simulation thread, like every other reservation call.
	 *
	 * @return the number of holdings and tails this call released completely; one whose release
	 *   approach locking deferred counts once, on the call that completes it
	 */
	fun expireStaleReservations(): Int
}
