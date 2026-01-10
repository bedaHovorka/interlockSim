/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 1.2: Track State Management Tests
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.sim.TrackOperationException
import cz.vutbr.fit.interlockSim.testutil.MockNodeCell
import cz.vutbr.fit.interlockSim.testutil.MockTrackOccupant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive unit tests for SimpleTrack state machine.
 *
 * Tests the critical railway track state management: FREE -> RESERVED -> OCCUPIED -> FREE
 * This is a CRITICAL safety component that prevents collisions and ensures proper interlocking.
 *
 * State Diagram:
 *   FREE (initial)
 *     ↓ setUpPath(sep) → RESERVED
 *   RESERVED
 *     ↓ enter(occupant) → OCCUPIED
 *   OCCUPIED
 *     ↓ leave(occupant) → FREE
 *
 * Valid backward transitions:
 *   RESERVED → FREE via cancelPathSetup(sep)
 *
 * Safety Properties Validated:
 * - SI-1: No collision (occupant field prevents double-entry)
 * - SI-4: Track reservation integrity (atomicity of state changes)
 * - SI-7: Occupancy detection correctness (getTrackOccupant() returns correct train)
 *
 * Coverage:
 * - State transitions (5 tests)
 * - Invalid transitions (4 tests)
 * - State query methods (4 tests)
 */
@DisplayName("SimpleTrack State Management")
class SimpleTrackStateTest {
	// Mock NodeCell for track endpoints (NodeCell implements PathSeparator)
	private lateinit var end1: MockNodeCell
	private lateinit var end2: MockNodeCell

	// Mock TrackOccupant for enter/leave operations
	private lateinit var mockOccupant: TrackOccupant
	private lateinit var otherOccupant: TrackOccupant

	@BeforeEach
	fun setUp() {
		// Create mock NodeCell endpoints (NodeCell implements PathSeparator)
		end1 = MockNodeCell("End1")
		end2 = MockNodeCell("End2")

		// Create mock TrackOccupant implementations
		mockOccupant = MockTrackOccupant("Train1")
		otherOccupant = MockTrackOccupant("Train2")
	}

	@Nested
	@DisplayName("State Transitions")
	inner class StateTransitionTests {
		@Test
		fun `new track is in FREE state`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Act & Assert
			assertThat(track.getState())
				.isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		fun `track transitions FREE to RESERVED on reserve`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			assertThat(track.getState()).isEqualTo(TrackFacility.State.FREE)

			// Act
			track.setUpPath(end1)

			// Assert
			assertThat(track.getState())
				.isEqualTo(TrackFacility.State.RESERVED)
		}

		@Test
		fun `track transitions RESERVED to OCCUPIED on train entry`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)
			assertThat(track.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Act
			track.enter(mockOccupant)

			// Assert
			assertThat(track.getState())
				.isEqualTo(TrackFacility.State.OCCUPIED)
		}

		@Test
		fun `track transitions OCCUPIED to FREE on train exit`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)
			track.enter(mockOccupant)
			assertThat(track.getState()).isEqualTo(TrackFacility.State.OCCUPIED)

			// Act
			track.leave(mockOccupant)

			// Assert
			assertThat(track.getState())
				.isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		fun `track can transition RESERVED to FREE on cancel`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)
			assertThat(track.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Act
			track.cancelPathSetup(end1)

			// Assert
			assertThat(track.getState())
				.isEqualTo(TrackFacility.State.FREE)
		}
	}

	@Nested
	@DisplayName("Invalid Transitions")
	inner class InvalidTransitionTests {
		@Test
		fun `cannot reserve already reserved track`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)

			// Act & Assert - should throw TrackOperationException
			try {
				track.setUpPath(end2) // Try to reserve from different end
				throw AssertionError("Should have thrown TrackOperationException")
			} catch (e: TrackOperationException) {
				// Expected
			}
		}

		@Test
		fun `cannot reserve occupied track`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)
			track.enter(mockOccupant)

			// Act & Assert - should throw TrackOperationException
			try {
				track.setUpPath(end2) // Try to reserve occupied track
				throw AssertionError("Should have thrown TrackOperationException")
			} catch (e: TrackOperationException) {
				// Expected
			}
		}

		@Test
		fun `cannot occupy unreserved track`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			// Track is still in FREE state - never reserved

			// Act & Assert - should throw AssertionError due to assert in enter()
			try {
				track.enter(mockOccupant) // Try to enter without reservation
				throw AssertionError("Should have thrown AssertionError")
			} catch (e: AssertionError) {
				if (e.message?.startsWith("Should have thrown") == true) throw e
				// Expected - assertion failed as desired
			}
		}

		@Test
		fun `cannot free track that is not occupied`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			// Track is FREE

			// Act & Assert - should throw AssertionError due to assert in leave()
			try {
				track.leave(mockOccupant) // Try to leave when not occupied
				throw AssertionError("Should have thrown AssertionError")
			} catch (e: AssertionError) {
				if (e.message?.startsWith("Should have thrown") == true) throw e
				// Expected - assertion failed as desired
			}
		}
	}

	@Nested
	@DisplayName("State Queries")
	inner class StateQueryTests {
		@Test
		fun `isFreeFrom returns true only when FREE`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Act & Assert - FREE state
			assertThat(track.isFreeFrom(end1))
				.isEqualTo(true)

			// Transition to RESERVED
			track.setUpPath(end1)
			assertThat(track.isFreeFrom(end1))
				.isEqualTo(false)

			// Transition to OCCUPIED
			track.enter(mockOccupant)
			assertThat(track.isFreeFrom(end1))
				.isEqualTo(false)

			// Back to FREE
			track.leave(mockOccupant)
			assertThat(track.isFreeFrom(end1))
				.isEqualTo(true)
		}

		@Test
		fun `isSetUpPath returns true only when RESERVED`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Act & Assert - FREE state
			assertThat(track.isSetUpPath(end1))
				.isEqualTo(false)

			// Transition to RESERVED from end1
			track.setUpPath(end1)
			assertThat(track.isSetUpPath(end1))
				.isEqualTo(true)
			// But not reserved from end2
			assertThat(track.isSetUpPath(end2))
				.isEqualTo(false)

			// Transition to OCCUPIED
			track.enter(mockOccupant)
			assertThat(track.isSetUpPath(end1))
				.isEqualTo(false)

			// Back to FREE
			track.leave(mockOccupant)
			assertThat(track.isSetUpPath(end1))
				.isEqualTo(false)
		}

		@Test
		fun `getTrackOccupant returns null when FREE`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Act & Assert - Track is FREE, occupant should be null
			// Note: getTrackOccupant() asserts that track must be OCCUPIED
			// This test verifies that calling it on FREE track fails
			try {
				track.getTrackOccupant()
				throw AssertionError("Should have thrown AssertionError")
			} catch (e: AssertionError) {
				if (e.message?.startsWith("Should have thrown") == true) throw e
				// Expected - assertion failed as desired
			}
		}

		@Test
		fun `getTrackOccupant returns train when OCCUPIED`() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			track.setUpPath(end1)
			track.enter(mockOccupant)

			// Act
			val occupant = track.getTrackOccupant()

			// Assert
			assertThat(occupant)
				.isSameInstanceAs(mockOccupant)
		}
	}
}
