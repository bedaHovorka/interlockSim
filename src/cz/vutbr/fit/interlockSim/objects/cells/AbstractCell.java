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
import java.util.Iterator;
import java.util.Set;


/**
 * Base implementation of {@link Cell}
 *
 */
public abstract class AbstractCell implements Cell {
	
	protected final Set<Segment> joinsOnLine() {
	    SpatialType st = getSpatialType(); assert st != null : this;
		Set<Segment> arr2set = arr2set(st.getSegments()); assert arr2set.size() == 2;
		return arr2set;
	}
	
	protected final Segment secondOnLine(Segment from) {
		final Set<Segment> joins = joinsOnLine();
		final boolean removed = joins.remove(from); assert removed && joins.size() == 1: from + " " + joins;
		final Iterator<Segment> iterator = joins.iterator(); assert iterator.hasNext();
		return iterator.next();
	}
	
	protected static final Set<Segment> arr2set(Segment[] segments) {
		assert segments != null;
		return EnumSet.of(segments[0], segments);
	}
}
