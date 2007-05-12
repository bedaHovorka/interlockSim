/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;

import java.util.EnumSet;
import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;

/**
 * Base implementation of {@link OrientedPathSeparator}
 */
public abstract class OrientedNodeCell extends NodeCell implements OrientedPathSeparator {
	private final boolean orientation;

	protected OrientedNodeCell(boolean orientation, SpatialType spatialType) {
		super(spatialType);
		this.orientation = orientation;
	}

	public Set<Segment> possibleFollowers(Segment from) {
		return EnumSet.of(getFollowingSegment(from));
	}
	
	public boolean getOrientation() {
		return orientation;
	}
	
	public Segment direction() {
		SpatialType st = getSpatialType(); assert st != null : this;
		return st.getSegments()[getOrientation()? 1 : 0];
	}
}
