/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Train
 * Phase 4 test implementation - 2025
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.withMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for {@link Train}.
 *
 * NOTE: Train is a complex jDisco Process that uses coroutines and discrete-event
 * simulation. Full testing would require integration tests with jDisco framework.
 * These unit tests focus on verifiable aspects like construction and basic properties.
 *
 * Coverage:
 * - Train construction and basic properties
 * - TrackOccupant interface compliance
 * - Basic validation of constructor parameters
 *
 * Limitations:
 * - Cannot fully test jDisco Process behavior without running simulation
 * - Complex movement and acceleration logic requires integration testing
 * - Semaphore interactions tested only at construction level
 */
class TrainTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	@Nested
	@DisplayName("Constructor validation")
	inner class ConstructorTests {
		@Test
		fun constructor_validArguments_createsTrainInstance() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)

			// Act
			val train = Train(mockContext, timetable)

			// Assert
			assertThat(train).isNotNull()
		}

		@Test
		fun constructor_nullContext_throwsException() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)

			// Act & Assert
			assertFailure { Train(null as cz.vutbr.fit.interlockSim.context.SimulationContext?, timetable) }
				.isInstanceOf<cz.vutbr.fit.interlockSim.exceptions.SimulationException>()
		}

		@Test
		fun constructor_nullTimetable_throwsException() {
			// Act & Assert
			assertFailure { Train(mockContext, null as Timetable?) }
				.isInstanceOf<cz.vutbr.fit.interlockSim.exceptions.SimulationException>()
		}

		@Test
		fun constructor_zeroLengthTimetable_createsTrainWithZeroLength() {
			// Arrange
			val timetable = createTimetableWithLength(0.0)

			// Act
			val train = Train(mockContext, timetable)

			// Assert
			assertThat(train).isNotNull()
			assertThat(train.getLength()).isEqualTo(0.0)
		}

		@Test
		fun constructor_negativeLengthTimetable_createsTrainWithNegativeLength() {
			// Negative lengths document current behavior (may be invalid)
			// Arrange
			val timetable = createTimetableWithLength(-50.0)

			// Act
			val train = Train(mockContext, timetable)

			// Assert
			assertThat(train).isNotNull()
			assertThat(train.getLength()).isEqualTo(-50.0)
		}
	}

	@Nested
	@DisplayName("TrackOccupant interface")
	inner class TrackOccupantTests {
		@Test
		fun getLength_afterConstruction_returnsTrainLength() {
			// Arrange
			val expectedLength = 150.0
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val actualLength = train.getLength()

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength)
		}

		@Test
		fun getLength_verySmallTrain_returnsCorrectLength() {
			// Arrange
			val expectedLength = 0.1 // Very small train
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val actualLength = train.getLength()

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength)
		}

		@Test
		fun getLength_veryLongTrain_returnsCorrectLength() {
			// Arrange
			val expectedLength = 1000.0 // Very Long train
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val actualLength = train.getLength()

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength)
		}
	}

	@Nested
	@DisplayName("Distance to semaphore")
	inner class DistanceToSemaphoreTests {
		@Test
		fun distanceToSemaphore_withNullPath_returnsZero() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act - Train has no path set initially
			val distance = train.distanceToSemaphore()

			// Assert - Should return 0.0 when no path exists
			assertThat(distance).isEqualTo(0.0)
		}
	}

	@Nested
	@DisplayName("String representation")
	inner class ToStringTests {
		@Test
		fun toString_returnsNonNullString() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val result = train.toString()

			// Assert
			assertThat(result)
				.isNotNull()
				.withMessage("Train toString should return a non-null string")
		}

		@Test
		fun toString_differentTrains_mayProduceDifferentStrings() {
			// Arrange
			val timetable1 = createTimetableWithLength(100.0)
			val timetable2 = createTimetableWithLength(200.0)
			val train1 = Train(mockContext, timetable1)
			val train2 = Train(mockContext, timetable2)

			// Act
			val result1 = train1.toString()
			val result2 = train2.toString()

			// Assert - Trains should have some string representation
			// (may or may not be different - depends on implementation)
			assertThat(result1).isNotNull()
			assertThat(result2).isNotNull()
		}
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a timetable with specified train length for testing using MockK.
	 * This allows tests to run without complex InOut setup.
	 *
	 * @param length the train length to set in the timetable
	 * @return a timetable configured with the given length
	 */
	private fun createTimetableWithLength(length: Double): Timetable {
		// Mock InOut objects needed for timetable
		val mockInOut = mockk<DynamicInOut>()
		every { mockInOut.name } returns "MOCK_IN"
		every { mockInOut.toString() } returns "InOut:MOCK_IN"

		val mockOutOut = mockk<DynamicInOut>()
		every { mockOutOut.name } returns "MOCK_OUT"
		every { mockOutOut.toString() } returns "InOut:MOCK_OUT"

		// Create timetable with mocked InOuts and specified length
		return Timetable(mockInOut, mockOutOut, Time(0.0), Time(0.0), length)
	}
}
