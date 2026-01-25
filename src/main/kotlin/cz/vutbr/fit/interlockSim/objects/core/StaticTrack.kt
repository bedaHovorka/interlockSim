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

/**
 * Interface for static (immutable) track properties.
 *
 * Represents the permanent, editing-time configuration of a track that doesn't
 * change during simulation:
 * - Physical geometry (length, endpoints)
 * - Speed limits
 * - Topology connections
 *
 * This interface supports clean separation between:
 * - **Editing time**: Static track configuration (this interface)
 * - **Simulation time**: Dynamic track state (DynamicTrackBehavior interface)
 *
 * Part of Phase 1: Track Wrapper Infrastructure (bedaHovorka/interlockSim#100.2)
 *
 * @see DynamicTrackBehavior for mutable simulation-time behavior
 * @see TrackFacility which combines both static and dynamic aspects
 */
interface StaticTrack : PathElement {
	companion object {
		/**
		 * Minimum allowed track length in meters
		 * @deprecated Use [cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH] from domain.PhysicsConstants
		 */
		@Deprecated(
			"Use MIN_TRACK_LENGTH from domain.PhysicsConstants",
			ReplaceWith("MIN_TRACK_LENGTH", "cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH")
		)
		const val MIN_LENGTH = cz.vutbr.fit.interlockSim.domain.MIN_TRACK_LENGTH

		/**
		 * Typical track length in meters
		 * @deprecated Use [cz.vutbr.fit.interlockSim.domain.COMMON_TRACK_LENGTH] from domain.PhysicsConstants
		 */
		@Deprecated(
			"Use COMMON_TRACK_LENGTH from domain.PhysicsConstants",
			ReplaceWith("COMMON_TRACK_LENGTH", "cz.vutbr.fit.interlockSim.domain.COMMON_TRACK_LENGTH")
		)
		const val COMMON_TRACK_LENGTH = cz.vutbr.fit.interlockSim.domain.COMMON_TRACK_LENGTH
	}

	/**
	 * Gets the length of the track in meters.
	 *
	 * @return Track length (immutable, set at construction time)
	 */
	fun length(): Double

	/**
	 * Gets the maximum allowed speed when entering from a specific end.
	 *
	 * Speed limits may differ by direction (e.g., due to track geometry).
	 *
	 * @param from The endpoint from which the train is entering (must be one of ends())
	 * @return Maximum speed in m/s for this direction
	 */
	fun maxSpeed(from: PathSeparator?): Double

	/**
	 * Gets both endpoints of the track.
	 *
	 * Every track has exactly 2 endpoints (PathSeparators) that connect it to
	 * other path elements in the railway network.
	 *
	 * @return Array of 2 PathSeparators representing track endpoints
	 */
	fun ends(): Array<PathSeparator>

	/**
	 * Gets the opposite end of the track from the given separator.
	 *
	 * @param sep One end of the track
	 * @return The other end of the track
	 */
	fun getSecondEnd(sep: PathSeparator): PathSeparator
}
