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

import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

/**
 * Base implementation of {@link Track}
 *
 */
abstract class AbstractTrack : Track {
	final override fun getSecondEnd(sep: PathSeparator): PathSeparator {
		val ends = ends()
		assert(ends.size == 2)
		assert(ends[0] !== ends[1])

		if (ends[0] !== sep) {
			assert(sep === ends[1]) { sep }
			return ends[0]
		}
		assert(sep === ends[0])
		return ends[1]
	}

	protected fun isEnd(sep: PathSeparator): Boolean {
		val ends = ends()
		return sep === ends[0] || sep === ends[1]
	}
}
