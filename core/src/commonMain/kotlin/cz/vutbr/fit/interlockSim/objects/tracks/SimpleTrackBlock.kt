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

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant

/** Shared prefix of every Phase 1 (#100.2) stub message; see the class KDoc. */
private const val PHASE_1_BEHAVIOR_REMOVED = "Phase 1 (#100.2): Dynamic behavior removed from SimpleTrack. "

/** Stub message tail for the path-reservation methods. */
private const val USE_WRAPPER_FOR_PATH_OPERATIONS = "Use DynamicTrack wrapper for path operations."

/** Stub message tail for the occupancy methods. */
private const val USE_WRAPPER_FOR_OCCUPANCY_OPERATIONS = "Use DynamicTrack wrapper for occupancy operations."

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
	override var name: String? = null

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

	override fun getState(): TrackFacility.State =
		throw UnsupportedOperationException(
			"Phase 1 (#100.2): Dynamic state removed from SimpleTrack. " +
				"Use DynamicTrack wrapper for state management."
		)

	override fun isFreeFrom(sep: DynamicPathSeparator): Boolean =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_PATH_OPERATIONS
		)

	override fun setUpPath(
		from: DynamicPathSeparator,
		reservingTrainId: String
	): Unit =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_PATH_OPERATIONS
		)

	override fun isSetUpPath(from: DynamicPathSeparator): Boolean =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_PATH_OPERATIONS
		)

	override fun cancelPathSetup(from: DynamicPathSeparator): Unit =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_PATH_OPERATIONS
		)

	override fun enter(occupant: TrackOccupant): Unit =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_OCCUPANCY_OPERATIONS
		)

	override fun leave(occupant: TrackOccupant): Unit =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_OCCUPANCY_OPERATIONS
		)

	override fun getTrackOccupant(): TrackOccupant =
		throw UnsupportedOperationException(
			PHASE_1_BEHAVIOR_REMOVED + USE_WRAPPER_FOR_OCCUPANCY_OPERATIONS
		)

	// Note: SimpleTrack.ends() returns Array<PathSeparator> which should be Array<NodeCell>
	// based on the constructor (it accepts NodeCell as PathSeparator), but we keep parent signature
	// The parent ends() method returns Array<PathSeparator> which is correct

	override fun toString(): String = if (name == null) ends().contentToString() else name!!
}
