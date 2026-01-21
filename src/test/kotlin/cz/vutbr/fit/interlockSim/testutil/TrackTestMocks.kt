/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Utilities
 *
 * Mock implementations for track testing
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackOccupant

/**
 * Mock implementation of NodeCell for testing track endpoints.
 *
 * NodeCell is a PathSeparator that represents connection points on a track.
 * This mock provides minimal implementation for track testing.
 */
class MockNodeCell(
	private val name: String,
	private val speed: Double = 80.0
) : NodeCell(SpatialType.HORIZONTAL), DynamicPathSeparator {
	override fun cancelPathSetup(
		from: Segment?,
		to: Segment?
	) {
		// Mock implementation - no-op
	}

	override fun setUpPath(
		from: Segment?,
		to: Segment?,
		allowedSpeed: Double
	) {
		// Mock implementation - no-op
	}

	override fun getFollowingSegment(from: Segment?): Segment? = null

	override fun possibleFollowers(from: Segment): Set<Segment> = emptySet()

	override fun allowedSpeed(): Double = speed

	override fun joins(): Set<Segment> {
		// Return the two segments that join for HORIZONTAL type
		return setOf(Segment.F, Segment.A)
	}

	init {
		setName(name)
	}
}

/**
 * Mock implementation of TrackOccupant for testing enter/leave operations.
 *
 * TrackOccupant represents a train or other object occupying a track.
 * For testing, we only need the minimal implementation.
 */
class MockTrackOccupant(
	private val name: String
) : TrackOccupant {
	override fun distanceToSemaphore(): Double = 100.0

	override fun nextSemaphore(): OrientedPathSeparator? = null

	override fun toString(): String = name
}
