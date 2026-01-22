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

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import java.util.Arrays

/**
 * This is common track block with one section
 *
 * **IMPORTANT Phase 1 Note (Issue #100.2):**
 * This class now lacks dynamic state management after SimpleTrack refactoring.
 * Dynamic methods below are TEMPORARY stubs that throw UnsupportedOperationException.
 * Phase 2 will integrate DynamicTrack wrapper to restore functionality.
 */
class SimpleTrackBlock :
	SimpleTrack,
	TrackBlock,
	TrackSection,
	TrackFacility {
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

	// TrackSection interface methods
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

	// PHASE 1 TEMPORARY STUBS - Dynamic behavior removed from SimpleTrack
	// These will be replaced with DynamicTrack wrapper in Phase 2
	
	override fun getState(): TrackFacility.State {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic state removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for state management."
		)
	}

	override fun isFreeFrom(sep: PathSeparator): Boolean {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for path operations."
		)
	}

	override fun setUpPath(from: PathSeparator) {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for path operations."
		)
	}

	override fun isSetUpPath(from: PathSeparator): Boolean {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for path operations."
		)
	}

	override fun cancelPathSetup(from: PathSeparator) {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for path operations."
		)
	}

	override fun enter(occupant: TrackOccupant) {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for occupancy operations."
		)
	}

	override fun leave(occupant: TrackOccupant) {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for occupancy operations."
		)
	}

	override fun getTrackOccupant(): TrackOccupant {
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. " +
			"Use DynamicTrack wrapper for occupancy operations."
		)
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
