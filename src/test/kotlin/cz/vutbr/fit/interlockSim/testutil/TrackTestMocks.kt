/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Utilities
 *
 * Test utility mock factories and delegation wrappers for railway simulation testing.
 *
 * ## Contents
 *
 * - **createMockNodeCell()**: MockK factory for NodeCell mocks (track endpoints)
 *   - Replaces manual MockNodeCell class (Phase 2 migration, 2026-02-05)
 *   - Pattern: Concrete class + factory (NodeCell is abstract)
 *   - Note: MockNodeCell class preserved due to abstract base requirement
 *
 * - **createMockTrackOccupant()**: MockK factory for TrackOccupant mocks (trains)
 *   - Replaces manual MockTrackOccupant class (Phase 3 migration, 2026-02-05)
 *   - Pattern: Pure MockK (TrackOccupant is interface)
 *   - MockTrackOccupant class removed (no longer needed)
 *
 * - **MockNodeCell**: Concrete implementation for abstract NodeCell
 *   - INTENTIONAL: Kept because NodeCell is abstract (cannot use pure MockK)
 *   - Implements NodeCell + DynamicPathSeparator interfaces
 *   - Use createMockNodeCell() factory for consistent test setup
 *
 * ## MockK Migration History
 *
 * - Phase 1 (2026-01-20): Mockito → MockK (8 simulation tests)
 * - Phase 1 (2026-02-05): MockTrainOccupant, MockTrackBlock → MockK factories
 * - Phase 2 (2026-02-05): MockNodeCell → createMockNodeCell(), remove Mockito dependency
 * - Phase 3 (2026-02-05): MockTrackOccupant → createMockTrackOccupant(), remove class
 *
 * @since 2006/2007 (Original thesis project)
 * @see Issue #332 - MockK migration plan (Phases 1-3 complete)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.mockk.every
import io.mockk.mockk

/**
 * Creates a mock NodeCell for testing track operations.
 *
 * This factory function replaces direct instantiation of MockNodeCell with a factory pattern
 * following the approach established in Phase 1 (Issue #332).
 *
 * NodeCell represents connection points (endpoints) on a track in the railway network.
 * This mock provides all necessary behavior for track testing including:
 * - Name and speed configuration
 * - Spatial type (HORIZONTAL by default)
 * - Segment joining (F and A segments for HORIZONTAL)
 * - Empty follower sets (no routing by default)
 *
 * Note: Returns a concrete MockNodeCell instance (not a pure MockK mock) because NodeCell
 * is an abstract class. This preserves the structure of the original implementation while
 * providing the factory pattern for consistent test setup.
 *
 * @param name Node name (default: "MockNode")
 * @param speed Maximum speed through node in m/s (default: 80.0)
 * @param spatialType Spatial orientation (default: HORIZONTAL)
 * @return Concrete MockNodeCell instance implementing NodeCell and DynamicPathSeparator
 *
 * @since Phase 2 (2026-02-05) - MockK migration
 * @see createMockTrackBlock Similar factory pattern from Phase 1
 *
 * Example usage:
 * ```kotlin
 * val node = createMockNodeCell(name = "TestNode", speed = 100.0)
 * assertThat(node.getName()).isEqualTo("TestNode")
 * assertThat(node.allowedSpeed()).isEqualTo(100.0)
 * ```
 */
fun createMockNodeCell(
	name: String = "MockNode",
	speed: Double = 80.0,
	spatialType: SpatialType = SpatialType.HORIZONTAL
): MockNodeCell = MockNodeCell(name, speed, spatialType)

/**
 * Creates a mock TrackOccupant for testing enter/leave operations.
 *
 * This factory function replaces the manual MockTrackOccupant class with MockK,
 * completing the factory pattern migration from Phase 1 & 2 (Issue #332).
 *
 * TrackOccupant is an interface with 3 methods, making it ideal for MockK.
 *
 * @param name Occupant name (default: "MockTrain")
 * @param distanceToSemaphore Distance to next semaphore in meters (default: 100.0)
 * @param nextSemaphore Next semaphore in path (default: null)
 * @return MockK instance of TrackOccupant
 *
 * @since Phase 3 (2026-02-05) - Complete manual mock cleanup
 * @see createMockTrackBlock Similar pattern from Phase 1
 * @see createMockNodeCell Similar pattern from Phase 2
 *
 * Example usage:
 * ```kotlin
 * val train = createMockTrackOccupant("Train1")
 * track.enter(train)
 * ```
 */
fun createMockTrackOccupant(
	name: String = "MockTrain",
	distanceToSemaphore: Double = 100.0,
	nextSemaphore: OrientedPathSeparator? = null
): TrackOccupant = mockk(relaxed = true) {
	every { this@mockk.name } returns name
	every { this@mockk.distanceToSemaphore() } returns distanceToSemaphore
	every { this@mockk.nextSemaphore() } returns nextSemaphore
	every { this@mockk.toString() } returns name
}

/**
 * Mock implementation of NodeCell for testing track endpoints.
 *
 * NodeCell is a PathSeparator that represents connection points on a track.
 * This mock provides minimal implementation for track testing.
 *
 * Note: This class is preserved from the original implementation to maintain compatibility
 * with abstract NodeCell. Use createMockNodeCell() factory function for consistent test setup.
 *
 * @param name Node name
 * @param speed Maximum speed through node in m/s
 * @param spatialType Spatial orientation (default: HORIZONTAL)
 */
class MockNodeCell(
	private val name: String,
	private val speed: Double = 80.0,
	spatialType: SpatialType = SpatialType.HORIZONTAL
) : NodeCell(spatialType),
	DynamicPathSeparator {
	override fun cancelPathSetup(
		from: Segment?,
		to: Segment?
	) {
		// Mock implementation - no-op
	}

	override fun setUpPath(
		from: Segment?,
		to: Segment?,
		allowedSpeed: Double,
		trackOccupant: TrackOccupant
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
