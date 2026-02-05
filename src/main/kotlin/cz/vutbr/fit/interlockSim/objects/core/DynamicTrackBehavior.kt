/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException

/**
 * Interface for dynamic (mutable) track behavior during simulation.
 *
 * Represents the runtime state and operations of a track that change during
 * simulation execution:
 * - State transitions (FREE → RESERVED → OCCUPIED → FREE)
 * - Train entry and exit operations
 * - Path reservation and cancellation
 * - Occupancy queries
 *
 * This interface supports clean separation between:
 * - **Editing time**: Static track configuration (StaticTrack interface)
 * - **Simulation time**: Dynamic track state (this interface)
 *
 * Part of Phase 1: Track Wrapper Infrastructure (bedaHovorka/interlockSim#100.2)
 *
 * @see StaticTrack for immutable configuration properties
 * @see TrackFacility which combines both static and dynamic aspects
 */
interface DynamicTrackBehavior {
	/**
	 * Gets the current state of the track facility.
	 *
	 * @return Current state (FREE, RESERVED, or OCCUPIED)
	 */
	fun getState(): TrackFacility.State

	/**
	 * Checks if the track is free to accept a path from the given separator.
	 *
	 * @param sep The separator to check from
	 * @return true if track is in FREE state, false otherwise
	 */
	fun isFreeFrom(sep: DynamicPathSeparator): Boolean

	/**
	 * Reserves this track for a path.
	 *
	 * Transitions state from FREE to RESERVED. All or some (depending on type)
	 * of the TrackFacilities in this track change state.
	 *
	 * @param from The separator from which the path is being set up (must be one of ends())
	 * @param reservingTrainId
	 * @throws TrackOperationException if track is not FREE
	 */
	fun setUpPath(
		from: DynamicPathSeparator,
		reservingTrainId: String
	)

	/**
	 * Checks if a path is set up from the given separator.
	 *
	 * @param from The separator to check
	 * @return true if track is RESERVED from this separator, false otherwise
	 * @throws TrackOperationException on invalid state
	 */
	fun isSetUpPath(from: DynamicPathSeparator): Boolean

	/**
	 * Cancels the path reservation.
	 *
	 * Transitions state from RESERVED to FREE. All or some (depending on type)
	 * of the TrackFacilities in this track change state.
	 *
	 * @param from The separator from which the path was set up
	 * @throws TrackOperationException if separator doesn't match or state is not RESERVED
	 */
	fun cancelPathSetup(from: DynamicPathSeparator)

	/**
	 * Train entering the track.
	 *
	 * Transitions state from RESERVED to OCCUPIED.
	 *
	 * @param occupant The train entering the track
	 * @throws IllegalStateException if track is already occupied (collision detection)
	 */
	fun enter(occupant: TrackOccupant)

	/**
	 * Train leaving the track.
	 *
	 * Transitions state from OCCUPIED to FREE.
	 *
	 * @param occupant The train leaving (must match current occupant)
	 * @throws IllegalStateException if occupant doesn't match
	 */
	fun leave(occupant: TrackOccupant)

	/**
	 * Gets the current occupant (train) in the track.
	 *
	 * @return The current occupant, null if not occupied
	 */
	fun getTrackOccupant(): TrackOccupant?
}
