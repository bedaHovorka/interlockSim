/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;

import cz.vutbr.fit.interlockSim.objects.tracks.Track;

/**
 * 
 *
 */
public class TrackOperationException extends SimulationException {

	/**
	 * @param track 
	 * 
	 */
	public TrackOperationException(Track track) {
		super(track);
	}

	/**
	 * @param message
	 * @param track 
	 */
	public TrackOperationException(String message, Track track) {
		super(message, track);
	}

	/**
	 * @param cause
	 * @param track 
	 */
	public TrackOperationException(Throwable cause, Track track) {
		super(cause, track);
	}

	/**
	 * @param message
	 * @param cause
	 * @param track 
	 */
	public TrackOperationException(String message, Throwable cause, Track track) {
		super(message, cause, track);
	}

	/**
	 * @return failed track
	 */
	@Override
	public Track getObject() {
		return (Track) super.getObject();
	}
}
