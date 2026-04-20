/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Utilities (core module version)
 *
 * Test utility mock factories and delegation wrappers for railway simulation testing.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

fun createMockNodeCell(
	name: String = "MockNode",
	speed: Double = 80.0,
	spatialType: SpatialType = SpatialType.HORIZONTAL
): MockNodeCell = MockNodeCell(name, speed, spatialType)

fun createMockTrackOccupant(
	name: String = "MockTrain",
	distanceToSemaphore: Double = 100.0,
	nextSemaphore: OrientedPathSeparator? = null
): TrackOccupant =
	mockk(relaxed = true) {
		every { this@mockk.name } returns name
		every { this@mockk.distanceToSemaphore() } returns distanceToSemaphore
		every { this@mockk.nextSemaphore() } returns nextSemaphore
		every { this@mockk.toString() } returns name
	}

// ==================== Track Facilities ====================

fun createMockTrack(
	name: String,
	length: Double,
	maxSpeed: Double = 20.0
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.maxSpeed(any()) } returns maxSpeed
	every { mock.toString() } returns "Track:$name"
	return mock
}

fun createMockReservedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[RESERVED]"
	return mock
}

fun createMockOccupiedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[OCCUPIED]"
	return mock
}

fun createMockBlockedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name"
	return mock
}

fun createMockTrackBlock(): TrackBlock =
	mockk<TrackBlock>(relaxed = true) {
		every { name } returns null
		every { getNextTrackSection(any(), any()) } returns null
		every { isInnerElement(any()) } returns false
		every { getJoin(any(), any()) } returns Cell.Segment.A
		every { isFreeFrom(any()) } returns true
		every { setUpPath(any(), any()) } just Runs
		every { isSetUpPath(any()) } returns false
		every { cancelPathSetup(any()) } just Runs
		every { getSecondEnd(any()) } answers { firstArg() }
		every { length() } returns 100.0
		every { maxSpeed(any()) } returns 80.0
		every { ends() } returns emptyArray()
		every { getState() } returns cz.vutbr.fit.interlockSim.objects.core.TrackFacility.State.FREE
		every { enter(any()) } just Runs
		every { leave(any()) } just Runs
		every { getTrackOccupant() } throws UnsupportedOperationException("Mock implementation")
	}

fun createMockTrackBlockPart(
	trackBlock: TrackBlock,
	name: String = "MockPart"
): TrackBlockPart {
	val mock = mockk<TrackBlockPart>(relaxed = true)
	every { mock.getTrackBlock() } returns trackBlock
	every { mock.getSpatialType() } returns null
	every { mock.getSegments() } returns arrayOf(Cell.Segment.A, Cell.Segment.F)
	return mock
}

// ==================== Semaphores ====================

fun createMockSemaphoreReal(
	name: String,
	isAllowing: Boolean
): DynamicRailSemaphore {
	val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
	val dynamicSemaphore = createDynamicInstance(staticSemaphore)
	val signal = if (isAllowing) Signal.FREE else Signal.STOP
	dynamicSemaphore.signal = signal
	return dynamicSemaphore
}

fun createMockSemaphoreMock(isAllowing: Boolean): DynamicRailSemaphore {
	val semaphore = mockk<DynamicRailSemaphore>(relaxed = true)
	val signal = if (isAllowing) Signal.FREE else Signal.STOP
	every { semaphore.signal } returns signal
	every { semaphore.toString() } returns "Semaphore:$signal"
	return semaphore
}

fun createMockRailSemaphore(): RailSemaphore {
	val mock = mockk<RailSemaphore>(relaxed = true)
	every { mock.getSpatialType() } returns Cell.SpatialType.HORIZONTAL
	every { mock.getOrientation() } returns true
	every { mock.getName() } returns "TestSemaphore"
	return mock
}

fun createMockDynamicSemaphore(
	staticRef: RailSemaphore,
	signal: Signal
): DynamicRailSemaphore {
	val mock = mockk<DynamicRailSemaphore>(relaxed = true)
	every { mock.staticRef } returns staticRef
	every { mock.signal } returns signal
	every { mock.getSpatialType() } returns staticRef.getSpatialType()
	return mock
}

// ==================== Path & Network Elements ====================

fun createMockPath(vararg tracks: SimpleTrack): ArrayPath {
	val totalLength = tracks.sumOf { it.length() }
	val mock = mockk<ArrayPath>(relaxed = true)
	every { mock.length() } returns totalLength
	every { mock.toString() } returns "Path[${tracks.size} segments, ${totalLength}m]"
	return mock
}

fun createMockInOut(name: String): DynamicInOut {
	val mock = mockk<DynamicInOut>()
	every { mock.name } returns name
	every { mock.toString() } returns "InOut:$name"
	return mock
}

fun createMockSwitch(name: String): RailSwitch {
	val mock = mockk<RailSwitch>()
	every { mock.toString() } returns "Switch:$name"
	return mock
}

// ==================== Mock Implementations ====================

/**
 * Mock implementation of NodeCell for testing track endpoints.
 */
class MockNodeCell(
	name: String,
	private val speed: Double = 80.0,
	spatialType: SpatialType = SpatialType.HORIZONTAL
) : NodeCell(spatialType, name),
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

	override fun joins(): Set<Segment> = setOf(Segment.F, Segment.A)

	override fun withName(newName: String): MockNodeCell = MockNodeCell(newName, speed, getSpatialType())
}
