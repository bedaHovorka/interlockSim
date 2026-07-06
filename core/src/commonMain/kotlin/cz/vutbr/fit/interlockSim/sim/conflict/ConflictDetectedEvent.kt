/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Event emitted when two trains spatially conflict over the same track block.
 *
 * Emitted by
 * [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]
 * via the kDisco event scheduler when a spatial conflict is detected at reservation
 * time — **before** the conflicting reservation is accepted or committed:
 *
 * - **Path blocked by another train**: [trainId] attempted to reserve [block], but
 *   [conflictingTrainId] already holds a reservation for it
 *   ([cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.AllPathsBlocked]).
 * - **Atomic registry race**: the reservation passed the free-check but the registry
 *   rejected it because [conflictingTrainId] double-booked [block] concurrently
 *   ([cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.RegistrationResult.Conflict]).
 *
 * The event is distinct from
 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning.ReservationConflict]
 * (Goal 3 collision *warning* layer) because:
 * - It is emitted **immediately** at reservation time, not only at end-of-run flush.
 * - It does **not** trigger an automatic simulation pause; it is a signal for the
 *   Goal 9 conflict-resolution layer to process.
 * - Non-conflicting reservations on different blocks never produce this event.
 *
 * Subscribe via
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onConflictDetectedEvent].
 *
 * @property block The track block that two trains are competing for.
 * @property trainId The train that attempted the conflicting reservation.
 * @property conflictingTrainId The train that already holds the reservation on [block].
 * @property time Simulation time (in seconds) at which the conflict was detected.
 *
 * @since Issue #580 (Goal 9 SP1)
 */
data class ConflictDetectedEvent(
	val block: DynamicTrackBlock,
	val trainId: String,
	val conflictingTrainId: String,
	val time: Double
)
