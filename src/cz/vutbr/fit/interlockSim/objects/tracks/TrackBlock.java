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

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

/**
 * blok koleji rizeny dispecerem
 *
 */
public interface TrackBlock extends Track {
	/**
	 * Move in block
	 * @param separator for determine direction
	 * @param current
	 * @return section in block following current
	 */
	public TrackSection getNextTrackSection(PathSeparator separator, TrackSection current);
	
	/**
	 * @param element
	 * @return is a inner elemnent
	 */
	public boolean isInnerElement(PathElement element);
	
	/**
	 * @param separator
	 * @param current for determine direction, from
	 * @return segment reprezents join 
	 */
	public Segment getJoin(PathSeparator separator, TrackSection current);
}
