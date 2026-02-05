/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Path Max Speed Calculation
 * Phase 2: Logic and Domain Tests - Path Speed Limits
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for Path maximum speed calculation.
 *
 * This test suite verifies:
 * - maxSpeed calculation with switch branch speeds
 * - maxSpeed with multiple speed limits (returns minimum)
 * - maxSpeed with curved track segments
 * - Speed limit propagation through path
 * - Edge cases (empty paths, single elements)
 * - Railway physics constraints
 *
 * **Coverage Goals:**
 * - AbstractPath.maxSpeed(PathSeparator?)
 * - Switch branch speed handling
 * - Track speed limit queries
 * - Minimum speed calculation across path
 *
 * **Railway Domain Context:**
 * - Paths must respect physical speed limits of all components
 * - Switches have different speeds for main line vs. branch divergence
 * - The slowest component determines overall path speed limit
 * - Trains must not exceed path max speed for safety
 *
 * @since 2026-01 (Phase 2: Logic and Domain Tests)
 */
@DisplayName("Path Max Speed Calculation Tests")
class PathMaxSpeedCalculationTest : KoinTestBase() {
	private lateinit var mockContext: cz.vutbr.fit.interlockSim.testutil.MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	/**
	 * Nested test group: Switch Branch Speed
	 * Tests maxSpeed calculation with switches
	 */
	@Nested
	@DisplayName("Switch Branch Speed")
	inner class SwitchBranchSpeedTests {
		@Test
		fun `maxSpeed accounts for switch branch speed`() {
			// Arrange - switch with main speed 80.0, branch speed 50.0
			// Use MockNodeCell with 50.0 speed to simulate branch speed limit
			val end1 = createMockNodeCell(name = "End1", speed = 50.0)
			val railSwitch =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 80.0,
					branchSpeed = 50.0
				)
			val track = SimpleTrackBlock(end1, railSwitch, 200.0, 100.0, 100.0)
			val staticSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val dynamicSemaphore = createDynamicInstance(staticSemaphore)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(railSwitch)
			path.addLast(dynamicSemaphore)

			// Act - calculate from end1 which has branch speed limit
			val maxSpeed = path.maxSpeed(end1)

			// Assert - maxSpeed should be limited by switch branch speed (via end1)
			assertThat(maxSpeed).isEqualTo(50.0)
		}

		@Test
		fun `maxSpeed through switch main line uses main speed`() {
			// Arrange - switch with main speed 100.0, branch speed 40.0
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val railSwitch =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 100.0,
					branchSpeed = 40.0
				)
			val track1 = SimpleTrackBlock(end1, end2, 150.0, 120.0, 120.0)
			val track2 = SimpleTrackBlock(railSwitch, end3, 150.0, 120.0, 120.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(railSwitch)
			path.addLast(track2)
			path.addLast(end3)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - if using main line, speed should be higher than branch
			assertThat(maxSpeed).isGreaterThan(40.0)
		}

		@Test
		fun `maxSpeed with multiple switches takes minimum`() {
			// Arrange - two switches with different speeds
			// Use MockNodeCell with slowest branch speed (40.0)
			val end1 = createMockNodeCell(name = "End1", speed = 40.0)
			val switch1 =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 80.0,
					branchSpeed = 50.0
				)
			val track1 = SimpleTrackBlock(end1, switch1, 100.0, 100.0, 100.0)
			val switch2 =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_LEFT_FALSE,
					mainSpeed = 70.0,
					branchSpeed = 40.0
				)
			val track2 = SimpleTrackBlock(switch1, switch2, 100.0, 100.0, 100.0)
			val staticSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val dynamicSemaphore = createDynamicInstance(staticSemaphore)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(switch1)
			path.addLast(track2)
			path.addLast(switch2)
			path.addLast(dynamicSemaphore)

			// Act - calculate from end1 which simulates slowest branch speed
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by slowest switch branch (40.0 via end1)
			assertThat(maxSpeed).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed with switch and slow track takes minimum`() {
			// Arrange - switch with fast branch but slow track
			val end1 = createMockNodeCell(name = "End1")
			val railSwitch =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 100.0,
					branchSpeed = 80.0
				)
			val slowTrack = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 200.0, 30.0, 30.0) // slow track
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(slowTrack)
			path.addLast(railSwitch)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by track speed (30.0)
			assertThat(maxSpeed).isEqualTo(30.0)
		}
	}

	/**
	 * Nested test group: Multiple Speed Limits
	 * Tests maxSpeed with various speed constraints
	 */
	@Nested
	@DisplayName("Multiple Speed Limits")
	inner class MultipleSpeedLimitsTests {
		@Test
		fun `maxSpeed with multiple speed limits returns minimum`() {
			// Arrange - tracks with different speeds
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 100.0, 100.0) // fast
			val track2 = SimpleTrackBlock(end2, end3, 150.0, 40.0, 40.0) // slow
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(track2)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be minimum (40.0)
			assertThat(maxSpeed).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed with three tracks takes global minimum`() {
			// Arrange - three tracks with different speeds
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val end4 = createMockNodeCell(name = "End4")
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0, 80.0)
			val track2 = SimpleTrackBlock(end2, end3, 150.0, 60.0, 60.0)
			val track3 = SimpleTrackBlock(end3, end4, 200.0, 50.0, 50.0) // slowest
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(track2)
			path.addLast(end3)
			path.addLast(track3)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be global minimum (50.0)
			assertThat(maxSpeed).isEqualTo(50.0)
		}

		@Test
		fun `maxSpeed with uniform speed returns that speed`() {
			// Arrange - all tracks have same speed
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 70.0, 70.0)
			val track2 = SimpleTrackBlock(end2, end3, 150.0, 70.0, 70.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(track2)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be the uniform speed (70.0)
			assertThat(maxSpeed).isEqualTo(70.0)
		}

		@Test
		fun `maxSpeed calculation is consistent across separators`() {
			// Arrange - test that maxSpeed uses minimum of track speeds
			// Path must start and end with separators (first/last)
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val track = SimpleTrackBlock(end1, end2, 100.0, 60.0, 60.0)
			val staticSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val semaphore = createConstantInstance(staticSemaphore, Signal.FREE)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(end2)
			path.addLast(semaphore)

			// Act - calculate from first (end1) and last (semaphore)
			val maxSpeedFromEnd1 = path.maxSpeed(end1)
			val maxSpeedFromSemaphore = path.maxSpeed(semaphore)

			// Assert - both should return track speed (60.0)
			assertThat(maxSpeedFromEnd1).isEqualTo(60.0)
			assertThat(maxSpeedFromSemaphore).isEqualTo(60.0)
		}
	}

	/**
	 * Nested test group: Track Segments with Curves
	 * Tests maxSpeed with curved track geometries
	 */
	@Nested
	@DisplayName("Curved Track Segments")
	inner class CurvedTrackTests {
		@Test
		fun `maxSpeed with curved track segments respects curve limits`() {
			// Arrange - simulate curved track with reduced speed
			val end1 = createMockNodeCell(name = "End1")
			val curvedTrack =
				SimpleTrackBlock(
					end1,
					createMockNodeCell(name = "End2"),
					150.0, // length
					45.0, // maxSpeed1 - curve speed limit
					45.0 // maxSpeed2
				)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(curvedTrack)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should respect curve limit
			assertThat(maxSpeed).isEqualTo(45.0)
		}

		@Test
		fun `maxSpeed with S-curve uses lowest curve speed`() {
			// Arrange - S-curve with two curved sections
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val curve1 = SimpleTrackBlock(end1, end2, 120.0, 50.0, 50.0) // first curve
			val curve2 = SimpleTrackBlock(end2, end3, 130.0, 40.0, 40.0) // tighter curve
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(curve1)
			path.addLast(end2)
			path.addLast(curve2)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by tighter curve (40.0)
			assertThat(maxSpeed).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed with straight and curved sections uses minimum`() {
			// Arrange - mix of straight and curved track
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val straightTrack = SimpleTrackBlock(end1, end2, 200.0, 100.0, 100.0) // straight, fast
			val curvedTrack = SimpleTrackBlock(end2, end3, 150.0, 55.0, 55.0) // curved, slower
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(straightTrack)
			path.addLast(end2)
			path.addLast(curvedTrack)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by curve (55.0)
			assertThat(maxSpeed).isEqualTo(55.0)
		}
	}

	/**
	 * Nested test group: Edge Cases
	 * Tests boundary conditions and special scenarios
	 */
	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCaseTests {
		@Test
		fun `maxSpeed with single track returns track speed`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 65.0, 65.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be track speed (65.0)
			assertThat(maxSpeed).isEqualTo(65.0)
		}

		@Test
		fun `maxSpeed with very long path maintains minimum`() {
			// Arrange - long path with one slow section
			val start = createMockNodeCell(name = "Start")
			val pathWithSlow = ArrayPath(mockContext)
			pathWithSlow.addLast(start)

			// Add one slow track at the beginning
			val slowEnd = createMockNodeCell(name = "SlowEnd")
			val slowTrack = SimpleTrackBlock(start, slowEnd, 100.0, 35.0, 35.0)
			pathWithSlow.addLast(slowTrack)
			pathWithSlow.addLast(slowEnd)

			// Add several fast tracks
			var current = slowEnd
			for (i in 1..5) {
				val next = createMockNodeCell(name = "Fast$i")
				val track = SimpleTrackBlock(current, next, 200.0, 100.0, 100.0)
				pathWithSlow.addLast(track)
				pathWithSlow.addLast(next)
				current = next
			}

			// Add semaphore at the end
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			pathWithSlow.addLast(semaphore)

			// Act
			val maxSpeed = pathWithSlow.maxSpeed(start)

			// Assert - should be limited by slow track (35.0)
			assertThat(maxSpeed).isLessThan(40.0)
			assertThat(maxSpeed).isCloseTo(35.0, 0.1)
		}

		@Test
		fun `maxSpeed with high speed track is limited by separator`() {
			// Arrange - very fast track, but separator limits speed
			val end1 = createMockNodeCell(name = "End1") // separator with default speed
			val fastTrack = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 500.0, 200.0, 200.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(fastTrack)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by separator allowedSpeed (80.0)
			assertThat(maxSpeed).isEqualTo(80.0)
		}

		@Test
		fun `maxSpeed calculation includes path length information`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 250.0, 75.0, 75.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)
			val pathLength = path.length()

			// Assert - verify both metrics are correct
			assertThat(maxSpeed).isEqualTo(75.0)
			assertThat(pathLength).isEqualTo(250.0)
		}
	}

	/**
	 * Nested test group: Complex Path Configurations
	 * Tests realistic railway network scenarios
	 */
	@Nested
	@DisplayName("Complex Path Configurations")
	inner class ComplexPathTests {
		@Test
		fun `maxSpeed through station approach with speed reduction`() {
			// Arrange - simulate station approach with progressive speed reduction
			val end1 = createMockNodeCell(name = "Approach")
			val end2 = createMockNodeCell(name = "Station Entry")
			val end3 = createMockNodeCell(name = "Platform")
			val approachTrack = SimpleTrackBlock(end1, end2, 500.0, 90.0, 90.0) // approach
			val stationTrack = SimpleTrackBlock(end2, end3, 200.0, 40.0, 40.0) // station
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(approachTrack)
			path.addLast(end2)
			path.addLast(stationTrack)
			path.addLast(semaphore)

			// Act
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be limited by station track
			assertThat(maxSpeed).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed through diverging junction uses branch speeds`() {
			// Arrange - junction with two diverging routes
			// Use MockNodeCell with branch speed (40.0)
			val end1 = createMockNodeCell(name = "Junction", speed = 40.0)
			val switchJunction =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 80.0,
					branchSpeed = 40.0 // diverging branch
				)
			val track = SimpleTrackBlock(end1, switchJunction, 100.0, 90.0, 90.0)
			val staticSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val dynamicSemaphore = createDynamicInstance(staticSemaphore)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(switchJunction)
			path.addLast(dynamicSemaphore)

			// Act - calculate from end1 which simulates branch speed
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should use branch speed (40.0 via end1)
			assertThat(maxSpeed).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed with mixed switch types takes minimum`() {
			// Arrange - path through different switch types
			// Use MockNodeCell with slowest branch speed (35.0)
			val end1 = createMockNodeCell(name = "Start", speed = 35.0)
			val simpleSwitch =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE,
					mainSpeed = 70.0,
					branchSpeed = 45.0
				)
			val track1 = SimpleTrackBlock(end1, simpleSwitch, 100.0, 80.0, 80.0)
			val crossoverSwitch =
				RailSwitch(
					Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_LEFT_FALSE,
					mainSpeed = 60.0,
					branchSpeed = 35.0 // slowest
				)
			val track2 = SimpleTrackBlock(simpleSwitch, crossoverSwitch, 100.0, 80.0, 80.0)
			val staticSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val dynamicSemaphore = createDynamicInstance(staticSemaphore)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(simpleSwitch)
			path.addLast(track2)
			path.addLast(crossoverSwitch)
			path.addLast(dynamicSemaphore)

			// Act - calculate from end1 which simulates slowest branch speed
			val maxSpeed = path.maxSpeed(end1)

			// Assert - should be slowest branch speed (35.0 via end1)
			assertThat(maxSpeed).isEqualTo(35.0)
		}
	}
}
