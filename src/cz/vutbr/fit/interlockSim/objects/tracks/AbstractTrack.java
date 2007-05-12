/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks;

import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

/**
 * Base implementation of {@link Track}
 *
 */
public abstract class AbstractTrack implements Track {
	
	public final PathSeparator getSecondEnd(PathSeparator sep) {
		final PathSeparator[] ends = ends();
		assert ends != null && ends.length == 2;
		assert ends[0] != ends[1];
		
		if (ends[0] != sep) {
			assert sep == ends[1] : sep;
			return ends[0];
		}
		assert sep == ends[0];
		return ends[1];
	}
	
	protected final boolean isEnd(PathSeparator sep) {
		final PathSeparator[] ends = ends();
		return sep == ends[0] || sep == ends[1];
	}
}
