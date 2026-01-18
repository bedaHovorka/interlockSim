/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlockinging Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

/**
 *
 * tratovy oddil v posloupnosti prvku cesty z pohledu vlaku
 *
 * Extends Track (static + dynamic) with block-level operations.
 */
interface TrackSection : Track {
	/**
	 * @return block, in which is this section
	 */
	fun getTrackBlock(): TrackBlock
}
