/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for PathElement Length Contribution
 * Issue #93 Enhancement: Polymorphic contributeToPathLength() method
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for PathElement length contribution.
 *
 * This test suite verifies the polymorphic `contributeToPathLength()` method
 * that eliminates instanceof checks for path length calculation.
 *
 * **What's Being Tested:**
 * - PathSeparators (InOut, Semaphore, Switch) contribute 0.0 length
 * - Tracks contribute their actual physical length
 * - Path total length equals sum of all element contributions
 * - Multiple tracks and separators in complex paths
 *
 * **Coverage Goals:**
 * - PathElement.contributeToPathLength() default implementation
 * - StaticTrack.contributeToPathLength() override
 * - PathSeparator zero-length contribution
 * - AbstractPath.length() integration
 *
 * **Railway Domain Context:**
 * - PathSeparators (semaphores, switches, entry/exit points) are logical control points
 *   with no physical track length - they occupy a single grid cell
 * - Tracks represent physical railway sections with measurable length in meters
 * - Path length is critical for train physics calculations (travel time, braking distance)
 * - Only track segments contribute to total path length
 *
 * **Design Pattern:**
 * - Uses polymorphism instead of instanceof checks (Issue #93)
 * - Default implementation in PathElement returns 0.0
 * - StaticTrack overrides to return actual length
 *
 * @since 2026-02 (Issue #93 - eliminate instanceof checks)
 * @see PathElement.contributeToPathLength
 * @see cz.vutbr.fit.interlockSim.objects.tracks.StaticTrack.contributeToPathLength
 * @see AbstractPath.length
 */
@DisplayName("PathElement Length Contribution Tests")
class PathLengthContributionTest : KoinTestBase() {

	private lateinit var mockContext: MockSimulationContext

	companion object {
		// Railway physics constants for testing
		private const val COMMON_TRACK_LENGTH_M = 1000.0  // Typical track section length (meters)
		private const val SHORT_TRACK_LENGTH_M = 200.0    // Short track section (meters)
		private const val LONG_TRACK_LENGTH_M = 2500.0    // Long track section (meters)
		private const val TYPICAL_MAX_SPEED_MPS = 80.0    // Typical max speed (m/s = 288 km/h)
		private const val HIGH_SPEED_MPS = 120.0          // High speed limit (m/s = 432 km/h)
		private const val LOW_SPEED_MPS = 40.0            // Low speed limit (m/s = 144 km/h)
	}

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	// ============================================
	// Helper Methods
	// ============================================

	/**
	 * Creates a pair of InOut elements (entry and exit).
	 */
	private fun createInOutPair(): Pair<InOut, InOut> {
		val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val outB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		return Pair(inA, outB)
	}

	/**
	 * Creates a track between two node cells.
	 */
	private fun createTrackBetween(
		from: NodeCell,
		to: NodeCell,
		length: Double = COMMON_TRACK_LENGTH_M,
		maxSpeed: Double = TYPICAL_MAX_SPEED_MPS
	): SimpleTrackBlock {
		return SimpleTrackBlock(from, to, length, maxSpeed)
	}

	/**
	 * Creates a simple path with InOut -> Track -> InOut structure.
	 */
	private fun createSimplePath(trackLength: Double = COMMON_TRACK_LENGTH_M): ArrayPath {
		val (inA, outB) = createInOutPair()
		val track = createTrackBetween(inA, outB, trackLength)

		val path = ArrayPath(mockContext)
		path.addLast(inA)
		path.addLast(track)
		path.addLast(outB)
		return path
	}

	// ============================================
	// PathSeparator Length Contribution Tests
	// ============================================

	@Nested
	@DisplayName("PathSeparator Length Contribution")
	inner class PathSeparatorTests {

		@Test
		fun `InOut contributes zero length`() {
			// Arrange: InOut separator
			val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)

			// Act: Get length contribution
			val contribution = inOut.contributeToPathLength()

			// Assert: Zero length (InOuts are logical points)
			assertThat(contribution).isEqualTo(0.0)
		}

		@Test
		fun `RailSemaphore contributes zero length`() {
			// Arrange: RailSemaphore separator
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			// Act: Get length contribution
			val contribution = semaphore.contributeToPathLength()

			// Assert: Zero length (Semaphores are logical control points)
			assertThat(contribution).isEqualTo(0.0)
		}

		@Test
		fun `RailSwitch contributes zero length`() {
			// Arrange: RailSwitch separator
			val railSwitch = RailSwitch(
				Cell.SpatialType.HORIZONTAL,
				RailSwitch.Type.SIMPLE_RIGHT_FALSE,
				mainSpeed = TYPICAL_MAX_SPEED_MPS,
				branchSpeed = LOW_SPEED_MPS
			)

			// Act: Get length contribution
			val contribution = railSwitch.contributeToPathLength()

			// Assert: Zero length (Switches are logical control points)
			assertThat(contribution).isEqualTo(0.0)
		}
	}

	// ============================================
	// Track Length Contribution Tests
	// ============================================

	@Nested
	@DisplayName("Track Length Contribution")
	inner class TrackTests {

		@Test
		fun `Track contributes actual length`() {
			// Arrange: Two InOut points and a track connecting them
			val (inA, outB) = createInOutPair()
			val track = createTrackBetween(inA, outB, COMMON_TRACK_LENGTH_M)

			// Act: Get length contribution
			val contribution = track.contributeToPathLength()

			// Assert: Track returns its physical length
			assertThat(contribution).isEqualTo(track.length())
		}

		@Test
		fun `Short track contributes correct length`() {
			// Arrange: Short track section
			val (inA, outB) = createInOutPair()
			val track = createTrackBetween(inA, outB, SHORT_TRACK_LENGTH_M)

			// Act: Get length contribution
			val contribution = track.contributeToPathLength()

			// Assert: Contribution equals short track length
			assertThat(contribution).isEqualTo(SHORT_TRACK_LENGTH_M)
		}

		@Test
		fun `Track contribution matches track length exactly`() {
			// Arrange: Track with specific length
			val (inA, outB) = createInOutPair()
			val expectedLength = LONG_TRACK_LENGTH_M
			val track = createTrackBetween(inA, outB, expectedLength, HIGH_SPEED_MPS)

			// Act: Get contribution and track length
			val contribution = track.contributeToPathLength()
			val trackLength = track.length()

			// Assert: Both methods return identical value
			assertThat(contribution).isEqualTo(trackLength)
			assertThat(contribution).isEqualTo(expectedLength)
		}
	}

	// ============================================
	// Path Total Length Calculation Tests
	// ============================================

	@Nested
	@DisplayName("Path Total Length Calculation")
	inner class PathIntegrationTests {

		@Test
		fun `Path with single track calculates correct total length`() {
			// Arrange: Simple path (InOut -> Track -> InOut)
			val path = createSimplePath(COMMON_TRACK_LENGTH_M)

			// Act: Calculate path length via contribution method
			var contributedLength = 0.0
			for (element in path) {
				contributedLength += element.contributeToPathLength()
			}

			// Assert: Contributed length matches path.length()
			// Only the track contributes (InOuts contribute 0.0)
			assertThat(contributedLength).isEqualTo(path.length())
			assertThat(contributedLength).isEqualTo(COMMON_TRACK_LENGTH_M)
		}

		@Test
		fun `Path with multiple tracks sums all track lengths`() {
			// Arrange: Path with two tracks
			val (inA, outB) = createInOutPair()
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val track1 = createTrackBetween(inA, semaphore, SHORT_TRACK_LENGTH_M, TYPICAL_MAX_SPEED_MPS)
			val track2 = createTrackBetween(semaphore, outB, LONG_TRACK_LENGTH_M, HIGH_SPEED_MPS)

			val path = ArrayPath(mockContext)
			path.addLast(inA)
			path.addLast(track1)
			path.addLast(semaphore)
			path.addLast(track2)
			path.addLast(outB)

			// Act: Calculate total contribution
			var contributedLength = 0.0
			for (element in path) {
				contributedLength += element.contributeToPathLength()
			}

			// Assert: Sum of both track lengths
			val expectedTotal = SHORT_TRACK_LENGTH_M + LONG_TRACK_LENGTH_M
			assertThat(contributedLength).isEqualTo(expectedTotal)
			assertThat(contributedLength).isEqualTo(path.length())
		}

		@Test
		fun `Path with tracks and multiple separator types calculates correctly`() {
			// Arrange: Complex path with InOut, Switch, Semaphore, and tracks
			val inA = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
			val railSwitch = RailSwitch(
				Cell.SpatialType.HORIZONTAL,
				RailSwitch.Type.SIMPLE_RIGHT_FALSE,
				mainSpeed = TYPICAL_MAX_SPEED_MPS,
				branchSpeed = LOW_SPEED_MPS
			)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
			val outB = InOut("Exit", true, Cell.SpatialType.HORIZONTAL)

			val track1 = createTrackBetween(inA, railSwitch, COMMON_TRACK_LENGTH_M, TYPICAL_MAX_SPEED_MPS)
			val track2 = createTrackBetween(railSwitch, semaphore, SHORT_TRACK_LENGTH_M, LOW_SPEED_MPS)
			val track3 = createTrackBetween(semaphore, outB, LONG_TRACK_LENGTH_M, HIGH_SPEED_MPS)

			val path = ArrayPath(mockContext)
			path.addLast(inA)
			path.addLast(track1)
			path.addLast(railSwitch)
			path.addLast(track2)
			path.addLast(semaphore)
			path.addLast(track3)
			path.addLast(outB)

			// Act: Calculate total contribution
			var contributedLength = 0.0
			for (element in path) {
				contributedLength += element.contributeToPathLength()
			}

			// Assert: Sum of all three tracks (separators contribute 0.0)
			val expectedTotal = COMMON_TRACK_LENGTH_M + SHORT_TRACK_LENGTH_M + LONG_TRACK_LENGTH_M
			assertThat(contributedLength).isEqualTo(expectedTotal)
			assertThat(contributedLength).isEqualTo(path.length())
		}
	}
}
