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

import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

/**
 * "jakykoliv (z ruznych pohledu) usek koleji mezi pathseparatory (cast cesty)"
 * "muze se tvorit rekurzivne"
 *
 * Combines both static (immutable) and dynamic (mutable) track aspects.
 * For interface segregation, see StaticTrack and DynamicTrackBehavior.
 */
interface Track : StaticTrack, DynamicTrackBehavior {
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
}
