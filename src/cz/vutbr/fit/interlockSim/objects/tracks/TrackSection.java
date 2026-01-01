/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlockinging Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks;

/**
 *
 * tratovy oddil v posloupnosti prvku cesty z pohledu vlaku
 */
public interface TrackSection extends Track {
	/**
	 * Train entering
	 * @param occupant
	 */
	public void enter(TrackOccupant occupant);

	/**
	 * Train leaving
	 * @param occupant
	 */
	public void leave(TrackOccupant occupant);

	/**
	 * @return block, in which is this section
	 */
	public TrackBlock getTrackBlock();

	/**
	 * @return object in section
	 */
	public TrackOccupant getTrackOccupant();
}
