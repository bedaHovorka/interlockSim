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

import cz.vutbr.fit.interlockSim.objects.tracks.Track

/**
 *
 *
 */
class TrackOperationException : SimulationException {
	/**
	 * @param track
	 *
	 */
	constructor(track: Track) : super(track)

	/**
	 * @param message
	 * @param track
	 */
	constructor(message: String, track: Track) : super(message, track)

	/**
	 * @param cause
	 * @param track
	 */
	constructor(cause: Throwable, track: Track) : super(cause, track)

	/**
	 * @param message
	 * @param cause
	 * @param track
	 */
	constructor(message: String, cause: Throwable, track: Track) : super(message, cause, track)

	/**
	 * @return failed track
	 */
	override fun getObject(): Track? = super.getObject() as? Track
}
