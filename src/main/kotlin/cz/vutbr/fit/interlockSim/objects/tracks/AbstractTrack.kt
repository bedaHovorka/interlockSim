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

import cz.vutbr.fit.interlockSim.objects.core.StaticTrack
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Base implementation of {@link StaticTrack}
 *
 * Provides common implementations for static track operations.
 */
abstract class AbstractTrack : StaticTrack {
	final override fun getSecondEnd(sep: PathSeparator): PathSeparator {
		val ends = ends()
		requireValidState(ends.size == 2) { "Track must have exactly 2 ends, but has ${ends.size}" }
		requireValidState(ends[0] !== ends[1]) { "Track ends must be different" }

		// Extract static separators for identity comparison
		val staticSep = if (sep is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(sep)
		} else {
			sep
		}
		val staticEnd0 = if (ends[0] is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(ends[0])
		} else {
			ends[0]
		}
		val staticEnd1 = if (ends[1] is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(ends[1])
		} else {
			ends[1]
		}

		if (staticEnd0 !== staticSep) {
			requireValidState(staticSep === staticEnd1) { "Separator $sep is not an end of this track" }
			return ends[0]
		}
		requireValidState(staticSep === staticEnd0) { "Separator $sep should be first end" }
		return ends[1]
	}

	protected fun isEnd(sep: PathSeparator): Boolean {
		val ends = ends()
		// Extract static separators from Dynamic wrappers for identity comparison
		val staticSep = if (sep is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(sep)
		} else {
			sep
		}
		val staticEnd0 = if (ends[0] is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(ends[0])
		} else {
			ends[0]
		}
		val staticEnd1 = if (ends[1] is DynamicPathSeparator) {
			CellUtilities.assertNodeCell(ends[1])
		} else {
			ends[1]
		}
		return staticSep === staticEnd0 || staticSep === staticEnd1
	}
}
