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
 * ### Track Facilities (6 factories)
 * - createMockTrack() - General track with length/speed
 * - createMockReservedTrack() - Track in RESERVED state
 * - createMockOccupiedTrack() - Track in OCCUPIED state
 * - createMockBlockedTrack() - Track marked as blocked
 * - createMockTrackBlock() - TrackBlock with endpoints
 * - createMockTrackBlockPart() - Partial track segment
 *
 * ### Semaphores (4 factories)
 * - createMockSemaphoreReal() - Real RailSemaphore instance
 * - createMockSemaphoreMock() - Pure MockK semaphore
 * - createMockRailSemaphore() - Static rail semaphore
 * - createMockDynamicSemaphore() - Dynamic signal control
 *
 * ### Path & Network Elements (3 factories)
 * - createMockPath() - Path from track segments
 * - createMockInOut() - Entry/exit point
 * - createMockSwitch() - Rail switch junction
 *
 * ### Occupants & Nodes (2 factories)
 * - createMockNodeCell() - Track endpoint
 * - createMockTrackOccupant() - Train/occupant
 *
 * ### Mock Implementations (1 class)
 * - MockNodeCell - Concrete NodeCell (abstract base)
 *
 * ## MockK Migration History
 *
 * - Phase 1 (2026-01-20): Mockito → MockK (8 simulation tests)
 * - Phase 1 (2026-02-05): MockTrainOccupant, MockTrackBlock → MockK factories
 * - Phase 2 (2026-02-05): MockNodeCell → createMockNodeCell(), remove Mockito dependency
 * - Phase 3 (2026-02-05): MockTrackOccupant → createMockTrackOccupant(), remove class
 * - Phase 4 (2026-02-05): Consolidate 17 inline factories to central repository
 *
 * ## File Size Management
 *
 * **Current:** 465 lines (15 factories)
 * **Threshold:** 600 lines (split recommended above this)
 * **Capacity:** ~5 more factories before split
 *
 * If file grows beyond 600 lines, consider splitting into:
 * - `TrackFacilityMocks.kt` - Track, TrackBlock, TrackOccupant factories
 * - `SemaphoreMocks.kt` - Semaphore, RailSemaphore, DynamicSemaphore factories
 * - `NetworkElementMocks.kt` - Path, InOut, Switch, NodeCell factories
 *
 * @since 2006/2007 (Original thesis project)
 * @see Issue #332 - MockK migration plan (Phases 1-4 complete)
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
): TrackOccupant =
	mockk(relaxed = true) {
		every { this@mockk.name } returns name
		every { this@mockk.distanceToSemaphore() } returns distanceToSemaphore
		every { this@mockk.nextSemaphore() } returns nextSemaphore
		every { this@mockk.toString() } returns name
	}

// ==================== Track Facilities ====================

/**
 * Creates a mock SimpleTrack for testing path operations.
 *
 * Consolidated from DeadlockDetectionTest and TrainPathInteractionTest.
 *
 * @param name Track identifier for toString()
 * @param length Track length in meters
 * @param maxSpeed Maximum allowed speed in m/s (default: 20.0)
 * @return MockK instance of SimpleTrack
 *
 * @since Phase 4 (2026-02-05) - Factory consolidation
 * @see createMockReservedTrack Track in RESERVED state
 * @see createMockOccupiedTrack Track in OCCUPIED state
 */
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

/**
 * Creates a mock SimpleTrack in RESERVED state.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (RESERVED state)
 */
fun createMockReservedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[RESERVED]"
	return mock
}

/**
 * Creates a mock SimpleTrack in OCCUPIED state.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (OCCUPIED state)
 */
fun createMockOccupiedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[OCCUPIED]"
	return mock
}

/**
 * Creates a mock SimpleTrack marked as blocked.
 *
 * Extracted from TrainPathInteractionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (blocked)
 */
fun createMockBlockedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name"
	return mock
}

/**
 * Creates a mock TrackBlock with endpoints.
 *
 * Extracted from DefaultRailWayNetGridTest.
 *
 * @return MockK instance of TrackBlock
 */
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

/**
 * Creates a mock TrackBlockPart segment.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @param trackBlock Parent TrackBlock reference
 * @param name Part identifier (optional)
 * @return MockK instance of TrackBlockPart
 */
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

/**
 * Creates a real RailSemaphore instance (integration-style).
 *
 * Extracted from DeadlockDetectionTest.
 * Uses actual RailSemaphore object wrapped in DynamicRailSemaphore.
 *
 * **Note:** This is NOT a pure mock - it uses real RailSemaphore objects.
 * For pure mocks, use createMockSemaphoreMock().
 *
 * @param name Semaphore identifier
 * @param isAllowing true = FREE signal, false = STOP signal
 * @return Real DynamicRailSemaphore instance
 *
 * @see createMockSemaphoreMock Pure MockK alternative
 */
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

/**
 * Creates a pure MockK semaphore (unit-testing style).
 *
 * Extracted from TrainPathInteractionTest.
 * Pure MockK object without real RailSemaphore.
 *
 * **Note:** This is a pure mock. For integration-style real objects,
 * use createMockSemaphoreReal().
 *
 * @param isAllowing true = FREE signal, false = STOP signal
 * @return MockK instance of DynamicRailSemaphore
 *
 * @see createMockSemaphoreReal Real RailSemaphore alternative
 */
fun createMockSemaphoreMock(isAllowing: Boolean): DynamicRailSemaphore {
	val semaphore = mockk<DynamicRailSemaphore>(relaxed = true)
	val signal = if (isAllowing) Signal.FREE else Signal.STOP
	every { semaphore.signal } returns signal
	every { semaphore.toString() } returns "Semaphore:$signal"
	return semaphore
}

/**
 * Creates a mock RailSemaphore.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @return MockK instance of RailSemaphore
 */
fun createMockRailSemaphore(): RailSemaphore {
	val mock = mockk<RailSemaphore>(relaxed = true)
	every { mock.getSpatialType() } returns Cell.SpatialType.HORIZONTAL
	every { mock.getOrientation() } returns true
	every { mock.getName() } returns "TestSemaphore"
	return mock
}

/**
 * Creates a mock DynamicRailSemaphore with signal control.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @param staticRef Static RailSemaphore reference
 * @param signal Initial signal state
 * @return MockK instance of DynamicRailSemaphore
 */
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

/**
 * Creates a mock ArrayPath from track segments.
 *
 * Consolidated from DeadlockDetectionTest and TrainPathInteractionTest.
 *
 * @param tracks Variable number of track segments
 * @return MockK instance of ArrayPath
 *
 * @since Phase 4 (2026-02-05) - Factory consolidation
 */
fun createMockPath(vararg tracks: SimpleTrack): ArrayPath {
	val totalLength = tracks.sumOf { it.length() }
	val mock = mockk<ArrayPath>(relaxed = true)
	every { mock.length() } returns totalLength
	every { mock.toString() } returns "Path[${tracks.size} segments, ${totalLength}m]"
	return mock
}

/**
 * Creates a mock DynamicInOut (entry/exit point).
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name InOut identifier
 * @return MockK instance of DynamicInOut
 */
fun createMockInOut(name: String): DynamicInOut {
	val mock = mockk<DynamicInOut>()
	every { mock.name } returns name
	every { mock.toString() } returns "InOut:$name"
	return mock
}

/**
 * Creates a mock RailSwitch junction.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Switch identifier
 * @return MockK instance of RailSwitch
 */
fun createMockSwitch(name: String): RailSwitch {
	val mock = mockk<RailSwitch>()
	every { mock.toString() } returns "Switch:$name"
	return mock
}

// ==================== Mock Implementations ====================

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

// ==================== Track Facilities ====================

/**
 * Creates a mock SimpleTrack for testing path operations.
 *
 * Consolidated from DeadlockDetectionTest and TrainPathInteractionTest.
 *
 * @param name Track identifier for toString()
 * @param length Track length in meters
 * @param maxSpeed Maximum allowed speed in m/s (default: 20.0)
 * @return MockK instance of SimpleTrack
 *
 * @since Phase 4 (2026-02-05) - Factory consolidation
 * @see createMockReservedTrack Track in RESERVED state
 * @see createMockOccupiedTrack Track in OCCUPIED state
 */
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

/**
 * Creates a mock SimpleTrack in RESERVED state.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (RESERVED state)
 */
fun createMockReservedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[RESERVED]"
	return mock
}

/**
 * Creates a mock SimpleTrack in OCCUPIED state.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (OCCUPIED state)
 */
fun createMockOccupiedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name[OCCUPIED]"
	return mock
}

/**
 * Creates a mock SimpleTrack marked as blocked.
 *
 * Extracted from TrainPathInteractionTest.
 *
 * @param name Track identifier
 * @param length Track length in meters
 * @return MockK instance of SimpleTrack (blocked)
 */
fun createMockBlockedTrack(
	name: String,
	length: Double
): SimpleTrack {
	val mock = mockk<SimpleTrack>(relaxed = true)
	every { mock.length() } returns length
	every { mock.toString() } returns "Track:$name"
	return mock
}

/**
 * Creates a mock TrackBlock with endpoints.
 *
 * Extracted from DefaultRailWayNetGridTest.
 *
 * @return MockK instance of TrackBlock
 */
fun createMockTrackBlock(): TrackBlock = mockk<TrackBlock>(relaxed = true) {
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

/**
 * Creates a mock TrackBlockPart segment.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @param trackBlock Parent TrackBlock reference
 * @param name Part identifier (optional)
 * @return MockK instance of TrackBlockPart
 */
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

/**
 * Creates a real RailSemaphore instance (integration-style).
 *
 * Extracted from DeadlockDetectionTest.
 * Uses actual RailSemaphore object wrapped in DynamicRailSemaphore.
 *
 * **Note:** This is NOT a pure mock - it uses real RailSemaphore objects.
 * For pure mocks, use createMockSemaphoreMock().
 *
 * @param name Semaphore identifier
 * @param isAllowing true = FREE signal, false = STOP signal
 * @return Real DynamicRailSemaphore instance
 *
 * @see createMockSemaphoreMock Pure MockK alternative
 */
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

/**
 * Creates a pure MockK semaphore (unit-testing style).
 *
 * Extracted from TrainPathInteractionTest.
 * Pure MockK object without real RailSemaphore.
 *
 * **Note:** This is a pure mock. For integration-style real objects,
 * use createMockSemaphoreReal().
 *
 * @param isAllowing true = FREE signal, false = STOP signal
 * @return MockK instance of DynamicRailSemaphore
 *
 * @see createMockSemaphoreReal Real RailSemaphore alternative
 */
fun createMockSemaphoreMock(isAllowing: Boolean): DynamicRailSemaphore {
	val semaphore = mockk<DynamicRailSemaphore>(relaxed = true)
	val signal = if (isAllowing) Signal.FREE else Signal.STOP
	every { semaphore.signal } returns signal
	every { semaphore.toString() } returns "Semaphore:$signal"
	return semaphore
}

/**
 * Creates a mock RailSemaphore.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @return MockK instance of RailSemaphore
 */
fun createMockRailSemaphore(): RailSemaphore {
	val mock = mockk<RailSemaphore>(relaxed = true)
	every { mock.getSpatialType() } returns Cell.SpatialType.HORIZONTAL
	every { mock.getOrientation() } returns true
	every { mock.getName() } returns "TestSemaphore"
	return mock
}

/**
 * Creates a mock DynamicRailSemaphore with signal control.
 *
 * Extracted from AnimatedSimulationCellRendererTest.
 *
 * @param staticRef Static RailSemaphore reference
 * @param signal Initial signal state
 * @return MockK instance of DynamicRailSemaphore
 */
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

/**
 * Creates a mock ArrayPath from track segments.
 *
 * Consolidated from DeadlockDetectionTest and TrainPathInteractionTest.
 *
 * @param tracks Variable number of track segments
 * @return MockK instance of ArrayPath
 *
 * @since Phase 4 (2026-02-05) - Factory consolidation
 */
fun createMockPath(vararg tracks: SimpleTrack): ArrayPath {
	val totalLength = tracks.sumOf { it.length() }
	val mock = mockk<ArrayPath>(relaxed = true)
	every { mock.length() } returns totalLength
	every { mock.toString() } returns "Path[${tracks.size} segments, ${totalLength}m]"
	return mock
}

/**
 * Creates a mock DynamicInOut (entry/exit point).
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name InOut identifier
 * @return MockK instance of DynamicInOut
 */
fun createMockInOut(name: String): DynamicInOut {
	val mock = mockk<DynamicInOut>()
	every { mock.name } returns name
	every { mock.toString() } returns "InOut:$name"
	return mock
}

/**
 * Creates a mock RailSwitch junction.
 *
 * Extracted from DeadlockDetectionTest.
 *
 * @param name Switch identifier
 * @return MockK instance of RailSwitch
 */
fun createMockSwitch(name: String): RailSwitch {
	val mock = mockk<RailSwitch>()
	every { mock.toString() } returns "Switch:$name"
	return mock
}

// ==================== Mock Implementations ====================

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
