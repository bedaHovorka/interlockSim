/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException

/**
 * "jakykoliv (z ruznych pohledu) usek koleji mezi pathseparatory (cast cesty)"
 * "muze se tvorit rekurzivne"
 */
interface Track : PathElement {
	companion object {
		/**
		 * model bound constants
		 */
		const val MIN_LENGTH = 5.0 // m

		/**
		 * model bound constants
		 */
		const val COMMON_TRACK_LENGTH = 100.0 // m
	}

	/**
	 * @param sep must be from ends
	 * @return Is able to enter (set way) from direction
	 * @throws TrackOperationException
	 */
	fun isFreeFrom(sep: PathSeparator): Boolean

	/**
	 * Reserve this track
	 * all or some (it depends on type) of {@link TrackFacility} in Track change state
	 * from {@link State#FREE} to {@link State#RESERVED}
	 * @param from must be from ends
	 * @throws TrackOperationException
	 */
	fun setUpPath(from: PathSeparator)

	/**
	 * @param from from
	 * @return if way setted up
	 * @throws TrackOperationException
	 */
	fun isSetUpPath(from: PathSeparator): Boolean

	/**
	 * Frees this track
	 * all or some (it depends on type) of {@link TrackFacility}s in Track change state
	 * from {@link State#RESERVED} to {@link State#FREE}
	 * @param from end of track
	 * @throws TrackOperationException
	 */
	fun cancelPathSetup(from: PathSeparator)

	/**
	 * @param sep first end
	 * @return second end
	 */
	fun getSecondEnd(sep: PathSeparator): PathSeparator

	/**
	 * @return length of track in meters
	 */
	fun length(): Double

	/**
	 * @param from
	 * @return allowed speed from one end
	 */
	fun maxSpeed(from: PathSeparator?): Double

	/**
	 * @return both separators on ends of track
	 */
	fun ends(): Array<PathSeparator>
}
