/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;//jinde?

import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;

/**
 * Cell as part of {@link TrackBlock} in grid
 * 
 */
public class TrackBlockPart extends AbstractCell {
	private final TrackBlock trackBlock;
	private final Segment[] segments;
	
	/**
	 * 
	 * @param trackBlock
	 * @param segments
	 */
	public TrackBlockPart(TrackBlock trackBlock, Segment... segments) {
		assert segments.length > 0;
		this.trackBlock = trackBlock;
		this.segments = segments;
	}
	
	/**
	 * @return whole block
	 */
	public TrackBlock getTrackBlock() {
	    return trackBlock;
	}

	/**
	 * @return topology joins
	 */
	public Segment[] getSegments() {
		return segments;
	}

	public Set<Segment> joins() {
		return arr2set(getSegments());
	}

	public SpatialType getSpatialType() {
		return null;
	}
}
