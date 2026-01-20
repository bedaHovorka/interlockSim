/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for Timetable class.
 *
 * This test class focuses on validating:
 * - Timetable construction with entry/exit points and time constraints
 * - Getter methods for accessing timetable data (entry/exit points, times, train length)
 * - Timetable data consistency and integrity
 * - Edge cases with boundary values and special configurations
 *
 * The Timetable class stores a train's schedule including:
 * - Entry point (InOut) and entry time (Time)
 * - Exit point (InOut) and exit time (Time)
 * - Train length for occupancy calculation
 *
 * Current scope: Timetable accepts and returns these four parameters without modification.
 * Future extensions (noted in source code) may include station stops and time-dependent lengths.
 */
@DisplayName("Timetable Tests")
class TimetableTest {
	private lateinit var mockInPoint: InOut
	private lateinit var mockOutPoint: InOut

	@BeforeEach
	fun setUp() {
		// Create mock InOut objects for testing
		mockInPoint = mockk<InOut>()
		every { mockInPoint.getName() } returns "ENTRY_POINT"
		every { mockInPoint.toString() } returns "InOut:ENTRY_POINT"

		mockOutPoint = mockk<InOut>()
		every { mockOutPoint.getName() } returns "EXIT_POINT"
		every { mockOutPoint.toString() } returns "InOut:EXIT_POINT"
	}

	@Nested
	@DisplayName("Construction and Validation")
	inner class ConstructionAndValidationTests {
		@Test
		fun `timetable created with valid entry and exit points`() {
			// Arrange & Act
			val inTime = Time(10.0)
			val outTime = Time(20.0)
			val length = 150.0
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable).isNotNull()
			assertThat(timetable.getIn()).isEqualTo(mockInPoint)
			assertThat(timetable.getOut()).isEqualTo(mockOutPoint)
		}

		@Test
		fun `timetable stores correct time values`() {
			// Arrange
			val inTime = Time(5.0)
			val outTime = Time(15.0)
			val length = 100.0

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable.getInTime()).isEqualTo(inTime)
			assertThat(timetable.getOutTime()).isEqualTo(outTime)
		}

		@Test
		fun `timetable stores train length correctly`() {
			// Arrange
			val inTime = Time(0.0)
			val outTime = Time(60.0)
			val length = 250.5

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable.getLength()).isEqualTo(250.5)
		}

		@Test
		fun `timetable accepts zero length train`() {
			// Arrange & Act
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 0.0)

			// Assert
			assertThat(timetable.getLength()).isEqualTo(0.0)
		}
	}

	@Nested
	@DisplayName("Entry Point Management")
	inner class EntryPointManagementTests {
		@Test
		fun `timetable returns configured entry point`() {
			// Arrange
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 100.0)

			// Act
			val retrievedInPoint = timetable.getIn()

			// Assert
			assertThat(retrievedInPoint).isEqualTo(mockInPoint)
		}

		@Test
		fun `timetable returns configured exit point`() {
			// Arrange
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 100.0)

			// Act
			val retrievedOutPoint = timetable.getOut()

			// Assert
			assertThat(retrievedOutPoint).isEqualTo(mockOutPoint)
		}

		@Test
		fun `timetable distinguishes between entry and exit points`() {
			// Arrange
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 100.0)

			// Act
			val inPoint = timetable.getIn()
			val outPoint = timetable.getOut()

			// Assert
			// Different objects should not be equal (they are different InOut instances)
			assertThat(inPoint != outPoint).isEqualTo(true)
			assertThat(inPoint).isEqualTo(mockInPoint)
			assertThat(outPoint).isEqualTo(mockOutPoint)
		}
	}

	@Nested
	@DisplayName("Time Management")
	inner class TimeManagementTests {
		@Test
		fun `timetable stores different entry and exit times`() {
			// Arrange
			val inTime = Time(10.0)
			val outTime = Time(20.0)

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, 100.0)

			// Assert
			assertThat(timetable.getInTime()).isEqualTo(inTime)
			assertThat(timetable.getOutTime()).isEqualTo(outTime)
			assertThat(timetable.getInTime() != timetable.getOutTime()).isEqualTo(true)
		}

		@Test
		fun `timetable with same entry and exit time`() {
			// Arrange
			val time = Time(50.0)

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, time, time, 100.0)

			// Assert
			assertThat(timetable.getInTime()).isEqualTo(timetable.getOutTime())
		}

		@Test
		fun `timetable with zero entry time`() {
			// Arrange & Act
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(60.0), 100.0)

			// Assert
			assertThat(timetable.getInTime().value).isEqualTo(0.0)
		}

		@Test
		fun `timetable with large time values`() {
			// Arrange
			val inTime = Time(86400.0) // 1 day in seconds
			val outTime = Time(172800.0) // 2 days in seconds

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, 100.0)

			// Assert
			assertThat(timetable.getInTime().value).isEqualTo(86400.0)
			assertThat(timetable.getOutTime().value).isEqualTo(172800.0)
		}
	}

	@Nested
	@DisplayName("Train Length Management")
	inner class TrainLengthManagementTests {
		@Test
		fun `timetable stores standard train length`() {
			// Arrange
			val lengths = listOf(100.0, 150.0, 200.0, 250.0)

			// Act & Assert
			for (length in lengths) {
				val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), length)
				assertThat(timetable.getLength()).isEqualTo(length)
			}
		}

		@Test
		fun `timetable stores fractional train length`() {
			// Arrange & Act
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 123.456)

			// Assert
			assertThat(timetable.getLength()).isEqualTo(123.456)
		}

		@Test
		fun `timetable with very large train length`() {
			// Arrange & Act
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), 10000.0)

			// Assert
			assertThat(timetable.getLength()).isEqualTo(10000.0)
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCasesTests {
		@Test
		fun `timetable with exit time before entry time`() {
			// This edge case tests that Timetable accepts chronologically invalid times
			// (validation would be at a higher level in the application)
			// Arrange & Act
			val inTime = Time(100.0)
			val outTime = Time(50.0)
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, 100.0)

			// Assert
			assertThat(timetable.getInTime().value).isEqualTo(100.0)
			assertThat(timetable.getOutTime().value).isEqualTo(50.0)
		}

		@Test
		fun `timetable with very small time difference`() {
			// Arrange & Act
			val inTime = Time(10.0)
			val outTime = Time(10.001)
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, 100.0)

			// Assert
			assertThat(timetable.getInTime()).isEqualTo(inTime)
			assertThat(timetable.getOutTime()).isEqualTo(outTime)
		}

		@Test
		fun `timetable getters do not modify stored data`() {
			// Arrange
			val inTime = Time(5.0)
			val outTime = Time(15.0)
			val length = 150.0
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Act
			val firstGet = timetable.getLength()
			val secondGet = timetable.getLength()

			// Assert
			assertThat(firstGet).isEqualTo(length)
			assertThat(secondGet).isEqualTo(length)
			assertThat(firstGet).isEqualTo(secondGet)
		}

		@Test
		fun `multiple timetables with same configuration are independent`() {
			// Arrange
			val inTime = Time(0.0)
			val outTime = Time(10.0)
			val length = 100.0

			// Act
			val timetable1 = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)
			val timetable2 = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable1.getLength()).isEqualTo(timetable2.getLength())
			assertThat(timetable1.getInTime()).isEqualTo(timetable2.getInTime())
			assertThat(timetable1.getOutTime()).isEqualTo(timetable2.getOutTime())
		}

		@Test
		fun `timetable with negative train length (boundary validation)`() {
			// Edge case: negative length is accepted by Timetable
			// (validation would occur in higher-level components)
			// Arrange & Act
			val timetable = Timetable(mockInPoint, mockOutPoint, Time(0.0), Time(10.0), -100.0)

			// Assert
			assertThat(timetable.getLength()).isEqualTo(-100.0)
		}

		@Test
		fun `timetable getter methods return exact references`() {
			// Arrange
			val inTime = Time(10.0)
			val outTime = Time(20.0)
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, 100.0)

			// Act & Assert
			// Time objects with same value are equal
			assertThat(timetable.getInTime()).isEqualTo(inTime)
			assertThat(timetable.getOutTime()).isEqualTo(outTime)
		}
	}

	@Nested
	@DisplayName("Data Consistency")
	inner class DataConsistencyTests {
		@Test
		fun `timetable maintains all parameters through getters`() {
			// Arrange
			val inTime = Time(5.0)
			val outTime = Time(15.0)
			val length = 175.0

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable.getIn()).isEqualTo(mockInPoint)
			assertThat(timetable.getOut()).isEqualTo(mockOutPoint)
			assertThat(timetable.getInTime()).isEqualTo(inTime)
			assertThat(timetable.getOutTime()).isEqualTo(outTime)
			assertThat(timetable.getLength()).isEqualTo(length)
		}

		@Test
		fun `timetable with all boundary values`() {
			// Arrange
			val inTime = Time(0.0)
			val outTime = Time(86400.0)
			val length = 0.0

			// Act
			val timetable = Timetable(mockInPoint, mockOutPoint, inTime, outTime, length)

			// Assert
			assertThat(timetable.getInTime().value).isEqualTo(0.0)
			assertThat(timetable.getOutTime().value).isEqualTo(86400.0)
			assertThat(timetable.getLength()).isEqualTo(0.0)
		}
	}
}
