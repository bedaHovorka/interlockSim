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
 * nejmensi jednotka ve ktere se muze nachazet jeden "normalni vlak"
 * (v rozsireni: ve stanici je mozno do bloku pustit posun)
 *
 */
public interface TrackFacility extends Track {
	/**
	 * facility state
	 *
	 */
	public enum State {
		/**
		 * dispatcher can set up way
		 */
		FREE,
		/**
		 * train can enter, dispatcher can cancel way
		 */
		RESERVED,

		/**
		 * train can leave
		 */
		OCCUPIED;
	}

	/**
	 * @return state of facility
	 */
	public State getState();
}
