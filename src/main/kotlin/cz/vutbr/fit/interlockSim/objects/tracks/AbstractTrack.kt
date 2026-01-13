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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

/**
 * Base implementation of {@link Track}
 *
 */
abstract class AbstractTrack : Track {
	final override fun getSecondEnd(sep: PathSeparator): PathSeparator {
		val ends = ends()
		requireValidState(ends.size == 2) { "Track must have exactly 2 ends, but has ${ends.size}" }
		requireValidState(ends[0] !== ends[1]) { "Track ends must be different" }

		if (ends[0] !== sep) {
			requireValidState(sep === ends[1]) { "Separator $sep is not an end of this track" }
			return ends[0]
		}
		requireValidState(sep === ends[0]) { "Separator $sep should be first end" }
		return ends[1]
	}

	protected fun isEnd(sep: PathSeparator): Boolean {
		val ends = ends()
		return sep === ends[0] || sep === ends[1]
	}
}
