/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlockinging Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

/**
 *
 * nejmensi jednotka ve ktere se muze nachazet jeden "normalni vlak"
 * (v rozsireni: ve stanici je mozno do bloku pustit posun)
 *
 * Combines Track (static + dynamic) with state query functionality.
 */
interface TrackFacility : Track {
	/**
	 * facility state
	 *
	 */
	enum class State {
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
		OCCUPIED
	}
}
