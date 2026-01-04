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
package cz.vutbr.fit.interlockSim.sim;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext;

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
class TrainTest {

	private MockSimulationContext mockContext;

	@BeforeEach
	void setUp() {
		mockContext = new MockSimulationContext();
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorTests {

		@Test
		void constructor_validArguments_createsTrainInstance() {
			// Arrange
			Timetable timetable = createTimetableWithLength(150.0);

			// Act
			Train train = new Train(mockContext, timetable);

			// Assert
			assertThat(train).isNotNull();
		}

		@Test
		void constructor_nullContext_throwsException() {
			// Arrange
			Timetable timetable = createTimetableWithLength(150.0);

			// Act & Assert
			assertThatThrownBy(() -> new Train(null, timetable))
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void constructor_nullTimetable_throwsException() {
			// Act & Assert
			assertThatThrownBy(() -> new Train(mockContext, null))
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void constructor_zeroLengthTimetable_createsTrainWithZeroLength() {
			// Arrange
			Timetable timetable = createTimetableWithLength(0.0);

			// Act
			Train train = new Train(mockContext, timetable);

			// Assert
			assertThat(train).isNotNull();
			assertThat(train.getLength()).isEqualTo(0.0);
		}

		@Test
		void constructor_negativeLengthTimetable_createsTrainWithNegativeLength() {
			// Negative lengths document current behavior (may be invalid)
			// Arrange
			Timetable timetable = createTimetableWithLength(-50.0);

			// Act
			Train train = new Train(mockContext, timetable);

			// Assert
			assertThat(train).isNotNull();
			assertThat(train.getLength()).isEqualTo(-50.0);
		}
	}

	@Nested
	@DisplayName("TrackOccupant interface")
	class TrackOccupantTests {

		@Test
		void getLength_afterConstruction_returnsTrainLength() {
			// Arrange
			double expectedLength = 150.0;
			Timetable timetable = createTimetableWithLength(expectedLength);
			Train train = new Train(mockContext, timetable);

			// Act
			double actualLength = train.getLength();

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength);
		}

		@Test
		void getLength_verySmallTrain_returnsCorrectLength() {
			// Arrange
			double expectedLength = 0.1; // Very small train
			Timetable timetable = createTimetableWithLength(expectedLength);
			Train train = new Train(mockContext, timetable);

			// Act
			double actualLength = train.getLength();

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength);
		}

		@Test
		void getLength_veryLongTrain_returnsCorrectLength() {
			// Arrange
			double expectedLength = 1000.0; // Very long train
			Timetable timetable = createTimetableWithLength(expectedLength);
			Train train = new Train(mockContext, timetable);

			// Act
			double actualLength = train.getLength();

			// Assert
			assertThat(actualLength).isEqualTo(expectedLength);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTests {

		@Test
		void toString_returnsNonNullString() {
			// Arrange
			Timetable timetable = createTimetableWithLength(150.0);
			Train train = new Train(mockContext, timetable);

			// Act
			String result = train.toString();

			// Assert
			assertThat(result).isNotNull()
				.as("Train toString should return a non-null string");
		}

		@Test
		void toString_differentTrains_mayProduceDifferentStrings() {
			// Arrange
			Timetable timetable1 = createTimetableWithLength(100.0);
			Timetable timetable2 = createTimetableWithLength(200.0);
			Train train1 = new Train(mockContext, timetable1);
			Train train2 = new Train(mockContext, timetable2);

			// Act
			String result1 = train1.toString();
			String result2 = train2.toString();

			// Assert - Trains should have some string representation
			// (may or may not be different - depends on implementation)
			assertThat(result1).isNotNull();
			assertThat(result2).isNotNull();
		}
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a timetable with specified train length for testing using Mockito.
	 * This allows tests to run without complex InOut setup.
	 *
	 * @param length the train length to set in the timetable
	 * @return a timetable configured with the given length
	 */
	private Timetable createTimetableWithLength(double length) {
		// Mock InOut objects needed for timetable
		InOut mockInOut = mock(InOut.class);
		when(mockInOut.getName()).thenReturn("MOCK_IN");
		when(mockInOut.toString()).thenReturn("InOut:MOCK_IN");

		InOut mockOutOut = mock(InOut.class);
		when(mockOutOut.getName()).thenReturn("MOCK_OUT");
		when(mockOutOut.toString()).thenReturn("InOut:MOCK_OUT");

		// Create timetable with mocked InOuts and specified length
		return new Timetable(mockInOut, mockOutOut, null, null, length);
	}
}
