/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

import cz.vutbr.fit.interlockSim.objects.tracks.StaticTrack

/**
 * Exception thrown when a track operation fails.
 *
 * **Phase 1 Update (Issue #100.2):** Now accepts StaticTrack instead of Track
 * since exceptions only need the track identity/configuration, not dynamic state.
 */
class TrackOperationException : SimulationException {
	/**
	 * @param track
	 *
	 */
	constructor(track: StaticTrack) : super(track)

	/**
	 * @param message
	 * @param track
	 */
	constructor(message: String, track: StaticTrack) : super(message, track)

	/**
	 * @param cause
	 * @param track
	 */
	constructor(cause: Throwable, track: StaticTrack) : super(cause, track)

	/**
	 * @param message
	 * @param cause
	 * @param track
	 */
	constructor(message: String, cause: Throwable, track: StaticTrack) : super(message, cause, track)

	/**
	 * @return failed track
	 */
	override fun getObject(): StaticTrack? = super.getObject() as? StaticTrack
}
