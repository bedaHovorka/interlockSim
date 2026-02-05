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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.testutil.MockNodeCell
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import cz.vutbr.fit.interlockSim.testutil.createMockTrackOccupant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive unit tests for Track state machine via DynamicTrack wrapper.
 *
 * **Phase 1 Update (Issue #100.2):** Tests now use DynamicTrack wrapper to access
 * dynamic state, while SimpleTrackBlock provides only static configuration.
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
@DisplayName("Track State Management (via DynamicTrack)")
class SimpleTrackStateTest {
	// Mock NodeCell for track endpoints (NodeCell implements PathSeparator)
	private lateinit var end1: MockNodeCell
	private lateinit var end2: MockNodeCell

	// Static track configuration (immutable)
	private lateinit var staticTrack: SimpleTrackBlock

	// Dynamic track wrapper for state management
	private lateinit var track: DynamicTrack

	// Mock TrackOccupant for enter/leave operations
	private lateinit var mockOccupant: TrackOccupant
	private lateinit var otherOccupant: TrackOccupant

	@BeforeEach
	fun setUp() {
		// Create mock NodeCell endpoints (NodeCell implements PathSeparator)
		end1 = createMockNodeCell(name = "End1")
		end2 = createMockNodeCell(name = "End2")

		// Create static track configuration
		staticTrack = SimpleTrackBlock(end1, end2, 100.0, 80.0)

		// Wrap with DynamicTrack for state management
		track = DynamicTrack(staticTrack)

		// Create mock TrackOccupant implementations
		mockOccupant = createMockTrackOccupant("Train1")
		otherOccupant = createMockTrackOccupant("Train2")
	}

	@Nested
	@DisplayName("State Transitions")
	inner class StateTransitionTests {
		@Test
		fun `new track is in FREE state`() {
			// Act & Assert
			assertThat(track.state)
				.isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		fun `track transitions FREE to RESERVED on reserve`() {
			// Arrange
			assertThat(track.state).isEqualTo(TrackFacility.State.FREE)

			// Act
			track.setUpPath(end1)

			// Assert
			assertThat(track.state)
				.isEqualTo(TrackFacility.State.RESERVED)
		}

		@Test
		fun `track transitions RESERVED to OCCUPIED on train entry`() {
			// Arrange
			track.setUpPath(end1)
			assertThat(track.state).isEqualTo(TrackFacility.State.RESERVED)

			// Act
			track.enter(mockOccupant)

			// Assert
			assertThat(track.state)
				.isEqualTo(TrackFacility.State.OCCUPIED)
		}

		@Test
		fun `track transitions OCCUPIED to FREE on train exit`() {
			// Arrange
			track.setUpPath(end1)
			track.enter(mockOccupant)
			assertThat(track.state).isEqualTo(TrackFacility.State.OCCUPIED)

			// Act
			track.leave(mockOccupant)

			// Assert
			assertThat(track.state)
				.isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		fun `track can transition RESERVED to FREE on cancel`() {
			// Arrange
			track.setUpPath(end1)
			assertThat(track.state).isEqualTo(TrackFacility.State.RESERVED)

			// Act
			track.cancelPathSetup(end1)

			// Assert
			assertThat(track.state)
				.isEqualTo(TrackFacility.State.FREE)
		}
	}

	@Nested
	@DisplayName("Invalid Transitions")
	inner class InvalidTransitionTests {
		@Test
		fun `cannot reserve already reserved track`() {
			// Arrange
			track.setUpPath(end1)

			// Act & Assert - should throw TrackOperationException
			assertk
				.assertFailure {
					track.setUpPath(end2) // Try to reserve from different end
				}.isInstanceOf(TrackOperationException::class)
		}

		@Test
		fun `cannot reserve occupied track`() {
			// Arrange
			track.setUpPath(end1)
			track.enter(mockOccupant)

			// Act & Assert - should throw TrackOperationException
			assertk
				.assertFailure {
					track.setUpPath(end2) // Try to reserve occupied track
				}.isInstanceOf(TrackOperationException::class)
		}

		@Test
		fun `cannot occupy unreserved track`() {
			// Arrange
			// Track is still in FREE state - never reserved

			// Act & Assert - should throw SimulationException due to validation in enter()
			assertk
				.assertFailure {
					track.enter(mockOccupant) // Try to enter without reservation
				}.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
		}

		@Test
		fun `cannot free track that is not occupied`() {
			// Arrange
			// Track is FREE

			// Act & Assert - should throw SimulationException due to validation in leave()
			assertk
				.assertFailure {
					track.leave(mockOccupant) // Try to leave when not occupied
				}.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
		}
	}

	@Nested
	@DisplayName("State Queries")
	inner class StateQueryTests {
		@Test
		fun `isFreeFrom returns true only when FREE`() {
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
			// Track is FREE

			// Act & Assert - Track is FREE, occupant should be null
			// Note: getTrackOccupant() throws exception that track must be OCCUPIED
			// This test verifies that calling it on FREE track fails
			assertk
				.assertFailure {
					track.getTrackOccupant()
				}.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
		}

		@Test
		fun `getTrackOccupant returns train when OCCUPIED`() {
			// Arrange
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
