/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Coverage Polish Tests - Tracks Package
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test coverage polish for tracks package - targeting 85%+ coverage.
 *
 * Focus areas:
 * - SimpleTrackBlock UnsupportedOperationException methods
 * - SimpleTrack constructor variants and boundary conditions
 * - AbstractTrack DynamicPathSeparator handling
 * - SimpleTrackBlock name management
 * - DynamicTrack equals and state validation
 */
@DisplayName("Tracks Package - Coverage Polish Tests")
class TracksPolishTest {

	private lateinit var separator1: PathSeparator
	private lateinit var separator2: PathSeparator
	private lateinit var occupant: TrackOccupant

	@BeforeEach
	fun setUp() {
		separator1 = mockk<OrientedPathSeparator>()
		separator2 = mockk<OrientedPathSeparator>()
		occupant = mockk<TrackOccupant>()
	}

	@Nested
	@DisplayName("SimpleTrackBlock - UnsupportedOperationException Methods")
	inner class SimpleTrackBlockUnsupportedMethods {

		@Test
		fun `getState throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)

			assertFailure {
				block.getState()
			}.isInstanceOf(UnsupportedOperationException::class)
				.message().isNotNull().contains("Phase 1" as CharSequence, "DynamicTrack wrapper" as CharSequence)
		}

		@Test
		fun `isFreeFrom throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.isFreeFrom(separator1)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("DynamicTrack wrapper")
		}

		@Test
		fun `setUpPath throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.setUpPath(separator1)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("path operations")
		}

		@Test
		fun `isSetUpPath throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.isSetUpPath(separator1)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("path operations")
		}

		@Test
		fun `cancelPathSetup throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.cancelPathSetup(separator1)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("path operations")
		}

		@Test
		fun `enter throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.enter(occupant)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("occupancy operations")
		}

		@Test
		fun `leave throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.leave(occupant)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("occupancy operations")
		}

		@Test
		fun `getTrackOccupant throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			assertFailure {
				block.getTrackOccupant()
			}.isInstanceOf(UnsupportedOperationException::class)
				.message()
				.isNotNull()
				.contains("occupancy operations")
		}

		@Test
		fun `getJoin throws UnsupportedOperationException`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			// SimpleTrackBlock implements TrackSection
			val section: TrackSection = block

			assertFailure {
				block.getJoin(separator1, section)
			}.isInstanceOf(UnsupportedOperationException::class)
				.message().isNotNull().contains("getJoin" as CharSequence)
		}
	}

	@Nested
	@DisplayName("SimpleTrackBlock - Constructor and Properties")
	inner class SimpleTrackBlockConstructorTests {

		@Test
		fun `constructor with NodeCell endpoints`() {
			val cell1 = InOut("A", true, SpatialType.HORIZONTAL)
			val cell2 = InOut("B", false, SpatialType.HORIZONTAL)

			val block = SimpleTrackBlock(cell1, cell2, 100.0, 30.0, 30.0)

			assertThat(block.length()).isEqualTo(100.0)
			assertThat(block.ends()).hasSize(2)
		}

		@Test
		fun `constructor with minimum length`() {
			val minLength = StaticTrack.MIN_LENGTH
			val block = SimpleTrackBlock(separator1, separator2, minLength, 30.0, 30.0)

			assertThat(block.length()).isEqualTo(minLength)
		}

		@Test
		fun `constructor with minimal max speed`() {
			val minSpeed = PathElement.MINIMAL_MAX_SPEED
			val block = SimpleTrackBlock(separator1, separator2, 100.0, minSpeed, minSpeed)

			assertThat(block.maxSpeed(separator1)).isEqualTo(minSpeed)
		}

		@Test
		fun `length property getter`() {
			val block = SimpleTrackBlock(separator1, separator2, 123.45, 30.0, 30.0)
			assertThat(block.length()).isEqualTo(123.45)
		}

		@Test
		fun `ends property returns array of separators`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val ends = block.ends()

			assertThat(ends).hasSize(2)
			assertThat(ends.toList()).contains(separator1)
			assertThat(ends.toList()).contains(separator2)
		}

		@Test
		fun `setName updates block name`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			
			block.setName("TestBlock")
			assertThat(block.toString()).contains("TestBlock")
		}

		@Test
		fun `toString with null name`() {
			// When name is null, toString() returns Arrays.toString(ends()) which is the array representation
			// e.g., "[OrientedPathSeparator(#52), OrientedPathSeparator(#53)]"
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val string = block.toString()

			assertThat(string).isNotNull()
			assertThat(string).contains("OrientedPathSeparator")
		}

		@Test
		fun `toString with name set`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			block.setName("Block1")
			
			val string = block.toString()
			assertThat(string).contains("Block1")
		}
	}

	@Nested
	@DisplayName("SimpleTrack - MaxSpeed Edge Cases")
	inner class SimpleTrackMaxSpeedTests {

		@Test
		fun `maxSpeed returns correct speed for first separator`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 40.0, 50.0)
			assertThat(block.maxSpeed(separator1)).isEqualTo(40.0)
		}

		@Test
		fun `maxSpeed returns correct speed for second separator`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 40.0, 50.0)
			assertThat(block.maxSpeed(separator2)).isEqualTo(50.0)
		}

		@Test
		fun `maxSpeed with different speeds from each end`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 60.0)
			
			assertThat(block.maxSpeed(separator1)).isEqualTo(30.0)
			assertThat(block.maxSpeed(separator2)).isEqualTo(60.0)
			assertThat(block.maxSpeed(separator1)).isNotEqualTo(block.maxSpeed(separator2))
		}
	}

	@Nested
	@DisplayName("DynamicTrack - Equals and Identity")
	inner class DynamicTrackEqualsTests {

		@Test
		fun `equals with null returns false`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			assertThat(dynamic == null).isFalse()
		}

		@Test
		fun `equals with different type returns false`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			assertThat(dynamic.equals("not a track")).isFalse()
		}

		@Test
		fun `equals with same static track returns true`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic1 = DynamicTrack(track)
			val dynamic2 = DynamicTrack(track)
			
			assertThat(dynamic1).isEqualTo(dynamic2)
		}

		@Test
		fun `equals with different static track returns false`() {
			val track1 = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val track2 = SimpleTrackBlock(separator1, separator2, 200.0, 40.0, 40.0)
			val dynamic1 = DynamicTrack(track1)
			val dynamic2 = DynamicTrack(track2)
			
			assertThat(dynamic1).isNotEqualTo(dynamic2)
		}

		@Test
		fun `hashCode consistent with equals`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic1 = DynamicTrack(track)
			val dynamic2 = DynamicTrack(track)
			
			assertThat(dynamic1.hashCode()).isEqualTo(dynamic2.hashCode())
		}
	}

	@Nested
	@DisplayName("DynamicTrack - State Validation")
	inner class DynamicTrackStateValidation {

		@Test
		fun `cancelPathSetup from different separator than reserved`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			// Reserve from separator1
			dynamic.setUpPath(separator1)
			assertThat(dynamic.isSetUpPath(separator1)).isTrue()
			
			// Try to cancel from separator2 (should fail)
			assertFailure {
				dynamic.cancelPathSetup(separator2)
			}.isInstanceOf(SimulationException::class)
		}

		@Test
		fun `state consistency after enter`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			// Must reserve before entering
			dynamic.setUpPath(separator1)
			dynamic.enter(occupant)
			
			assertThat(dynamic.state).isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(dynamic.getTrackOccupant()).isEqualTo(occupant)
		}

		@Test
		fun `reservedFrom is null when FREE`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			assertThat(dynamic.reservedFrom).isNull()
			assertThat(dynamic.state).isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		fun `reservedFrom is not null when RESERVED`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val dynamic = DynamicTrack(track)
			
			dynamic.setUpPath(separator1)
			
			assertThat(dynamic.reservedFrom).isNotNull()
			assertThat(dynamic.reservedFrom).isEqualTo(separator1)
			assertThat(dynamic.state).isEqualTo(TrackFacility.State.RESERVED)
		}
	}

	@Nested
	@DisplayName("AbstractTrack - Second End Resolution")
	inner class AbstractTrackSecondEndTests {

		@Test
		fun `ends returns both separators`() {
			val track = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			val ends = track.ends()
			
			assertThat(ends).hasSize(2)
			assertThat(ends[0]).isEqualTo(separator1)
			assertThat(ends[1]).isEqualTo(separator2)
		}

		@Test
		fun `track has correct length`() {
			val length = 234.56
			val track = SimpleTrackBlock(separator1, separator2, length, 30.0, 30.0)

			assertThat(track.length()).isEqualTo(length)
		}
	}

	@Nested
	@DisplayName("TrackSection - Boundary Conditions")
	inner class TrackSectionBoundaryTests {

		@Test
		fun `TrackSection with single block`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 30.0, 30.0)
			// SimpleTrackBlock implements TrackSection
			val section: TrackSection = block

			assertThat(section.length()).isEqualTo(100.0)
			assertThat(section.ends()).hasSize(2)
		}

		@Test
		fun `TrackSection maxSpeed uses block's max speed`() {
			val block = SimpleTrackBlock(separator1, separator2, 100.0, 45.0, 55.0)
			// SimpleTrackBlock implements TrackSection
			val section: TrackSection = block

			assertThat(section.maxSpeed(separator1)).isEqualTo(45.0)
			assertThat(section.maxSpeed(separator2)).isEqualTo(55.0)
		}
	}
}
