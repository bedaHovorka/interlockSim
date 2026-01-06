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
 */
interface TrackSection : Track {
	/**
	 * Train entering
	 * @param occupant
	 */
	fun enter(occupant: TrackOccupant)

	/**
	 * Train leaving
	 * @param occupant
	 */
	fun leave(occupant: TrackOccupant)

	/**
	 * @return block, in which is this section
	 */
	fun getTrackBlock(): TrackBlock

	/**
	 * @return object in section
	 */
	fun getTrackOccupant(): TrackOccupant
}
