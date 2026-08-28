/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.PointF
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AnimationState] and related data classes.
 *
 * Tests immutability, data class semantics (equality, copy), and edge cases.
 */
class AnimationStateTest {
	@Test
	fun `AnimationState EMPTY has zero simulation time and empty collections`() {
		// Assert
		assertThat(AnimationState.EMPTY.simulationTime).isEqualTo(0.0)
		assertThat(AnimationState.EMPTY.trainStates).isEmpty()
		assertThat(AnimationState.EMPTY.trackStates).isEmpty()
		assertThat(AnimationState.EMPTY.signalStates).isEmpty()
		assertThat(AnimationState.EMPTY.switchStates).isEmpty()
	}

	@Test
	fun `AnimationState is immutable data class`() {
		// Arrange
		val state =
			AnimationState(
				simulationTime = 123.45,
				trainStates = mapOf(1 to mockTrainState(1)),
				trackStates = mapOf(mockk<TrackBlock>() to mockTrackState()),
				signalStates = mapOf(mockk<RailSemaphore>() to mockSignalState()),
				switchStates = emptyMap()
			)

		// Act & Assert - data class copy creates new instance
		val copy = state.copy(simulationTime = 200.0)
		assertThat(copy).isNotSameInstanceAs(state)
		assertThat(copy.simulationTime).isEqualTo(200.0)
		assertThat(state.simulationTime).isEqualTo(123.45) // Original unchanged
	}

	@Test
	fun `AnimationState with populated collections`() {
		// Arrange
		val trackBlock = mockk<TrackBlock>()
		val semaphore = mockk<RailSemaphore>()

		val state =
			AnimationState(
				simulationTime = 50.0,
				trainStates =
					mapOf(
						1 to mockTrainState(1),
						2 to mockTrainState(2)
					),
				trackStates =
					mapOf(
						trackBlock to mockTrackState()
					),
				signalStates =
					mapOf(
						semaphore to mockSignalState()
					),
				switchStates = emptyMap()
			)

		// Assert
		assertThat(state.simulationTime).isEqualTo(50.0)
		assertThat(state.trainStates).hasSize(2)
		assertThat(state.trackStates).hasSize(1)
		assertThat(state.signalStates).hasSize(1)
	}

	@Test
	fun `TrainState data class properties`() {
		// Arrange & Act
		val trainState =
			TrainState(
				trainNumber = 42,
				position = 100.5,
				velocity = 25.0,
				acceleration = 2.0,
				frontGridLocation = PointF(5f, 10f),
				length = 50.0,
				travelingRight = true
			)

		// Assert
		assertThat(trainState.trainNumber).isEqualTo(42)
		assertThat(trainState.position).isEqualTo(100.5)
		assertThat(trainState.velocity).isEqualTo(25.0)
		assertThat(trainState.acceleration).isEqualTo(2.0)
		assertThat(trainState.frontGridLocation).isEqualTo(PointF(5f, 10f))
		assertThat(trainState.length).isEqualTo(50.0)
		assertThat(trainState.travelingRight).isTrue()
	}

	@Test
	fun `TrainState with null frontGridLocation`() {
		// Arrange & Act
		val trainState =
			TrainState(
				trainNumber = 1,
				position = 0.0,
				velocity = 0.0,
				acceleration = 0.0,
				frontGridLocation = null, // Grid location not calculable
				length = 50.0,
				travelingRight = false
			)

		// Assert
		assertThat(trainState.frontGridLocation).isNull()
		assertThat(trainState.travelingRight).isFalse()
	}

	@Test
	fun `TrackState data class properties`() {
		// Arrange
		val trackBlock = mockk<TrackBlock>()

		// Act
		val trackState =
			TrackState(
				trackBlock = trackBlock,
				state = TrackFacility.State.OCCUPIED
			)

		// Assert
		assertThat(trackState.trackBlock).isSameInstanceAs(trackBlock)
		assertThat(trackState.state).isEqualTo(TrackFacility.State.OCCUPIED)
	}

	@Test
	fun `TrackState with all possible states`() {
		// Arrange
		val trackBlock = mockk<TrackBlock>()

		// Act & Assert - Test all three possible states
		val freeState = TrackState(trackBlock, TrackFacility.State.FREE)
		assertThat(freeState.state).isEqualTo(TrackFacility.State.FREE)

		val reservedState = TrackState(trackBlock, TrackFacility.State.RESERVED)
		assertThat(reservedState.state).isEqualTo(TrackFacility.State.RESERVED)

		val occupiedState = TrackState(trackBlock, TrackFacility.State.OCCUPIED)
		assertThat(occupiedState.state).isEqualTo(TrackFacility.State.OCCUPIED)
	}

	@Test
	fun `SignalState data class properties`() {
		// Arrange
		val semaphore = mockk<RailSemaphore>()

		// Act
		val signalState =
			SignalState(
				semaphore = semaphore,
				signal = Signal.S40 // Allow 40 km/h
			)

		// Assert
		assertThat(signalState.semaphore).isSameInstanceAs(semaphore)
		assertThat(signalState.signal).isEqualTo(Signal.S40)
	}

	@Test
	fun `SignalState with STOP signal`() {
		// Arrange
		val semaphore = mockk<RailSemaphore>()

		// Act
		val signalState =
			SignalState(
				semaphore = semaphore,
				signal = Signal.STOP
			)

		// Assert
		assertThat(signalState.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `AnimationState equality based on all properties`() {
		// Arrange - reuse same instances for equality comparison
		val trackBlock = mockk<TrackBlock>()
		val semaphore = mockk<RailSemaphore>()
		val trainState = mockTrainState(1)
		val trackState = mockTrackState()
		val signalState = mockSignalState()

		val state1 =
			AnimationState(
				simulationTime = 10.0,
				trainStates = mapOf(1 to trainState),
				trackStates = mapOf(trackBlock to trackState),
				signalStates = mapOf(semaphore to signalState),
				switchStates = emptyMap()
			)

		val state2 =
			AnimationState(
				simulationTime = 10.0,
				trainStates = mapOf(1 to trainState),
				trackStates = mapOf(trackBlock to trackState),
				signalStates = mapOf(semaphore to signalState),
				switchStates = emptyMap()
			)

		// Act & Assert
		assertThat(state1).isEqualTo(state2)
		assertThat(state1.hashCode()).isEqualTo(state2.hashCode())
	}

	// Helper methods for creating mock state objects

	private fun mockTrainState(trainNumber: Int) =
		TrainState(
			trainNumber = trainNumber,
			position = 0.0,
			velocity = 0.0,
			acceleration = 0.0,
			frontGridLocation = null,
			length = 50.0,
			travelingRight = true
		)

	private fun mockTrackState() =
		TrackState(
			trackBlock = mockk(),
			state = TrackFacility.State.FREE
		)

	private fun mockSignalState() =
		SignalState(
			semaphore = mockk(),
			signal = Signal.STOP
		)
}
