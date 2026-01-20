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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Tests for DynamicTrack wrapper class
 *
 * Verifies:
 * - Dynamic state management (FREE/RESERVED/OCCUPIED transitions)
 * - Stable identity (equals/hashCode based on static object)
 * - State transition validation
 */
class DynamicTrackTest {
	private lateinit var staticTrack1: TrackFacility
	private lateinit var staticTrack2: TrackFacility
	private lateinit var dynamicTrack1: DynamicTrack
	private lateinit var dynamicTrack2: DynamicTrack
	private lateinit var separator1: PathSeparator
	private lateinit var separator2: PathSeparator
	private lateinit var occupant: TrackOccupant

	@BeforeEach
	fun setUp() {
		// Create path separators for track ends
		separator1 = mock(OrientedPathSeparator::class.java)
		separator2 = mock(OrientedPathSeparator::class.java)

		// Create static tracks (SimpleTrackBlock implements TrackFacility)
		staticTrack1 = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
		staticTrack2 = SimpleTrackBlock(separator1, separator2, 200.0, 40.0, 40.0)

		// Create dynamic wrappers
		dynamicTrack1 = DynamicTrack(staticTrack1)
		dynamicTrack2 = DynamicTrack(staticTrack2)

		// Create mock occupant (train)
		occupant = mock(TrackOccupant::class.java)
	}

	@Test
	fun `initial state is FREE`() {
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)
	}

	@Test
	fun `initial occupant is null`() {
		assertFailure { dynamicTrack1.getTrackOccupant() }
			.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
			.message()
			.isNotNull()
			.contains("Track occupant should not be null")
	}

	@Test
	fun `initial reservation direction is null`() {
		assertThat(dynamicTrack1.reservedFrom).isNull()
	}

	@Test
	fun `can reserve track from FREE state`() {
		// Initially FREE
		assertThat(dynamicTrack1.isFreeFrom(separator1)).isTrue()

		// Reserve track
		dynamicTrack1.setUpPath(separator1)

		// Now RESERVED
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(dynamicTrack1.isSetUpPath(separator1)).isTrue()
		assertThat(dynamicTrack1.reservedFrom).isEqualTo(separator1)
	}

	@Test
	fun `cannot reserve track when not FREE`() {
		// Reserve track
		dynamicTrack1.setUpPath(separator1)

		// Try to reserve again
		assertFailure { dynamicTrack1.setUpPath(separator1) }
			.isInstanceOf(TrackOperationException::class.java)
	}

	@Test
	fun `can enter track from RESERVED state`() {
		// Reserve first
		dynamicTrack1.setUpPath(separator1)

		// Train enters
		dynamicTrack1.enter(occupant)

		// Now OCCUPIED
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(dynamicTrack1.getTrackOccupant()).isEqualTo(occupant)
		assertThat(dynamicTrack1.occupant).isEqualTo(occupant)
		assertThat(dynamicTrack1.reservedFrom).isNull() // Cleared on entry
	}

	@Test
	fun `cannot enter track when not RESERVED`() {
		// Try to enter without reserving
		assertFailure { dynamicTrack1.enter(occupant) }
			.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
	}

	@Test
	fun `cannot enter track when already occupied`() {
		// Reserve and enter
		dynamicTrack1.setUpPath(separator1)
		dynamicTrack1.enter(occupant)

		// Try to enter again with different occupant
		val occupant2 = mock(TrackOccupant::class.java)
		assertFailure { dynamicTrack1.enter(occupant2) }
			.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
			.message()
			.isNotNull()
			.contains("collision")
	}

	@Test
	fun `can leave track from OCCUPIED state`() {
		// Reserve, enter
		dynamicTrack1.setUpPath(separator1)
		dynamicTrack1.enter(occupant)

		// Train leaves
		dynamicTrack1.leave(occupant)

		// Now FREE
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)
		assertThat(dynamicTrack1.occupant).isNull()
	}

	@Test
	fun `cannot leave track with wrong occupant`() {
		// Reserve, enter
		dynamicTrack1.setUpPath(separator1)
		dynamicTrack1.enter(occupant)

		// Try to leave with different occupant
		val occupant2 = mock(TrackOccupant::class.java)
		assertFailure { dynamicTrack1.leave(occupant2) }
			.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
			.message()
			.isNotNull()
			.contains("mismatch")
	}

	@Test
	fun `can cancel reservation from RESERVED state`() {
		// Reserve
		dynamicTrack1.setUpPath(separator1)
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.RESERVED)

		// Cancel
		dynamicTrack1.cancelPathSetup(separator1)

		// Now FREE
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)
		assertThat(dynamicTrack1.reservedFrom).isNull()
	}

	@Test
	fun `cannot cancel reservation with wrong separator`() {
		// Reserve from separator1
		dynamicTrack1.setUpPath(separator1)

		// Try to cancel from separator2
		assertFailure { dynamicTrack1.cancelPathSetup(separator2) }
			.isInstanceOf(TrackOperationException::class)
			.message()
			.isNotNull()
			.contains("wrong end")
	}

	@Test
	fun `full state transition cycle FREE-RESERVED-OCCUPIED-FREE`() {
		// Start: FREE
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)
		assertThat(dynamicTrack1.isFreeFrom(separator1)).isTrue()

		// Reserve: FREE -> RESERVED
		dynamicTrack1.setUpPath(separator1)
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(dynamicTrack1.isSetUpPath(separator1)).isTrue()

		// Enter: RESERVED -> OCCUPIED
		dynamicTrack1.enter(occupant)
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(dynamicTrack1.getTrackOccupant()).isEqualTo(occupant)

		// Leave: OCCUPIED -> FREE
		dynamicTrack1.leave(occupant)
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)
		assertThat(dynamicTrack1.isFreeFrom(separator1)).isTrue()
	}

	@Test
	fun `state is independent for different dynamic wrappers`() {
		// Reserve track1
		dynamicTrack1.setUpPath(separator1)

		// track1 is RESERVED, track2 is FREE
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(dynamicTrack2.state).isEqualTo(TrackFacility.State.FREE)
	}

	@Test
	fun `equals is based on static object identity`() {
		// Same static object -> equal
		val anotherWrapper = DynamicTrack(staticTrack1)
		assertThat(dynamicTrack1).isEqualTo(anotherWrapper)

		// Different static object -> not equal
		assertThat(dynamicTrack1).isNotEqualTo(dynamicTrack2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		// Hash code should be same for same static object
		val anotherWrapper = DynamicTrack(staticTrack1)
		assertThat(dynamicTrack1.hashCode()).isEqualTo(anotherWrapper.hashCode())

		// Hash code should differ for different static objects
		assertThat(dynamicTrack1.hashCode()).isNotEqualTo(dynamicTrack2.hashCode())
	}

	@Test
	fun `hashCode is stable across state transitions`() {
		// Record initial hash
		val initialHash = dynamicTrack1.hashCode()

		// Go through state transitions
		dynamicTrack1.setUpPath(separator1)
		assertThat(dynamicTrack1.hashCode()).isEqualTo(initialHash)

		dynamicTrack1.enter(occupant)
		assertThat(dynamicTrack1.hashCode()).isEqualTo(initialHash)

		dynamicTrack1.leave(occupant)
		assertThat(dynamicTrack1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `equals is stable across state transitions`() {
		val anotherWrapper = DynamicTrack(staticTrack1)

		// Initially equal
		assertThat(dynamicTrack1).isEqualTo(anotherWrapper)

		// Change one wrapper's state
		dynamicTrack1.setUpPath(separator1)

		// Still equal (equality based on static object, not state)
		assertThat(dynamicTrack1).isEqualTo(anotherWrapper)

		dynamicTrack1.enter(occupant)
		assertThat(dynamicTrack1).isEqualTo(anotherWrapper)

		dynamicTrack1.leave(occupant)
		assertThat(dynamicTrack1).isEqualTo(anotherWrapper)
	}

	@Test
	fun `can use in hash-based collections`() {
		val set = mutableSetOf<DynamicTrack>()

		// Add first wrapper
		set.add(dynamicTrack1)
		assertThat(set).hasSize(1)

		// Add wrapper for same static object -> should not increase size
		val anotherWrapper1 = DynamicTrack(staticTrack1)
		set.add(anotherWrapper1)
		assertThat(set).hasSize(1)

		// Add wrapper for different static object -> should increase size
		set.add(dynamicTrack2)
		assertThat(set).hasSize(2)
	}

	@Test
	fun `toString includes state and occupant`() {
		dynamicTrack1.setUpPath(separator1)
		val str = dynamicTrack1.toString()

		assertThat(str).contains("Dynamic")
		assertThat(str).contains("RESERVED")
	}

	@Test
	fun `static object is accessible`() {
		assertThat(dynamicTrack1.staticRef).isSameAs(staticTrack1)
		assertThat(dynamicTrack2.staticRef).isSameAs(staticTrack2)
	}

	@Test
	fun `state property reflects current state`() {
		// Access via property
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.FREE)

		// Change via method
		dynamicTrack1.setUpPath(separator1)

		// Verify via property
		assertThat(dynamicTrack1.state).isEqualTo(TrackFacility.State.RESERVED)
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify delegation works - properties accessible directly
		assertThat(dynamicTrack1.length).isEqualTo(staticTrack1.length())
		assertThat(dynamicTrack1.ends).isEqualTo(staticTrack1.ends())

		// Verify for second track with different properties
		assertThat(dynamicTrack2.length).isEqualTo(staticTrack2.length())
		assertThat(dynamicTrack2.ends).isEqualTo(staticTrack2.ends())
	}
}
