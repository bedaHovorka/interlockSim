/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import java.util.Arrays

/**
 * This is common track block with one section
 *
 */
class SimpleTrackBlock :
	SimpleTrack,
	TrackBlock {
	private var name: String? = null

	/**
	 * @see SimpleTrack#SimpleTrack(PathSeparator, PathSeparator, Double, Double, Double)
	 * @param end1
	 * @param end2
	 * @param length
	 * @param maxSpeed1
	 * @param maxSpeed2
	 */
	constructor(end1: PathSeparator, end2: PathSeparator, length: Double, maxSpeed1: Double, maxSpeed2: Double) :
		super(end1, end2, length, maxSpeed1, maxSpeed2)

	/**
	 * @see SimpleTrack#SimpleTrack(PathSeparator, PathSeparator, Double, Double, Double)
	 * @param end1
	 * @param end2
	 * @param length
	 * @param maxSpeed (equal for both direction)
	 */
	constructor(end1: NodeCell, end2: NodeCell, length: Double, maxSpeed: Double) :
		this(end1, end2, length, maxSpeed, maxSpeed)

	override fun getTrackBlock(): TrackBlock = this

	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		if (current == null) return this
		if (current == this) return null
		throw IllegalArgumentException("simpletrackblock: current must be only this or null")
	}

	override fun isInnerElement(element: PathElement): Boolean = false

	override fun getJoin(
		separator: PathSeparator,
		current: TrackSection
	): Segment {
		// SimpleTrackBlock doesn't support getJoin - should not be called
		throw UnsupportedOperationException("SimpleTrackBlock does not support getJoin operation")
	}

	// Note: SimpleTrack.ends() returns Array<PathSeparator> which should be Array<NodeCell>
	// based on the constructor (it accepts NodeCell as PathSeparator), but we keep parent signature
	// The parent ends() method returns Array<PathSeparator> which is correct

	/**
	 * Setter
	 * @param name
	 */
	fun setName(name: String) {
		this.name = name
	}

	override fun toString(): String = if (name == null) Arrays.toString(ends()) else name!!
}
