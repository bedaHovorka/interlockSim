/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks;

import java.util.Arrays;

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

/**
 * This is common track block with one section
 *
 */
public class SimpleTrackBlock extends SimpleTrack implements TrackBlock {
	private String name;
	/**
	 * @see SimpleTrack#SimpleTrack(PathSeparator, PathSeparator, double, double, double)
	 * @param end1
	 * @param end2
	 * @param length
	 * @param maxSpeed1
	 * @param maxSpeed2
	 */
	public SimpleTrackBlock(PathSeparator end1, PathSeparator end2, double length, double maxSpeed1, double maxSpeed2) {
		super(end1, end2, length, maxSpeed1, maxSpeed2);
	}

	/**
	 * @see SimpleTrack#SimpleTrack(PathSeparator, PathSeparator, double, double, double)
	 * @param end1
	 * @param end2
	 * @param length
	 * @param maxSpeed (equal for both direction)
	 */
	public SimpleTrackBlock(NodeCell end1, NodeCell end2, double length, double maxSpeed) {
		this(end1, end2, length, maxSpeed, maxSpeed);
	}

	public TrackBlock getTrackBlock() {
		return this;
	}

	public TrackSection getNextTrackSection(PathSeparator separator, TrackSection current) {
		if (current == null) return this;
		if (current == this) return null;
		throw new IllegalArgumentException("simpletrackblock: current must be only this or null");
	}

	public boolean isInnerElement(PathElement separator) {
		return false;
	}

	public Segment getJoin(PathSeparator separator, TrackSection current) {
		assert false;
		return null;
	}

	@Override
	public NodeCell[] ends() {
		final PathSeparator[] pathSeparators = super.ends();
		return Arrays.<NodeCell, PathSeparator>copyOf(pathSeparators, pathSeparators.length, NodeCell[].class);
	}

	/**
	 * Getter
	 * @param name
	 */
	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return name==null ? Arrays.toString(ends()) : name;
	}
}
