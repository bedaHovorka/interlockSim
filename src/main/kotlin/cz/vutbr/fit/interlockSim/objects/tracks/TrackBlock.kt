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

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

/**
 * blok koleji rizeny dispecerem
 *
 */
interface TrackBlock : Track {
	/**
	 * Move in block
	 * @param separator for determine direction
	 * @param current
	 * @return section in block following current
	 */
	fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection?

	/**
	 * @param element
	 * @return is a inner elemnent
	 */
	fun isInnerElement(element: PathElement): Boolean

	/**
	 * @param separator
	 * @param current for determine direction, from
	 * @return segment reprezents join
	 */
	fun getJoin(
		separator: PathSeparator,
		current: TrackSection
	): Segment
}
