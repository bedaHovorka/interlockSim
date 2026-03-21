/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Train Public API
 * Added: 2026-02-06 (Public Train API for animation)
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for Train Public API for animation and external observation.
 *
 * Tests the Kotlin property-style accessors for idiomatic Kotlin usage.
 * These properties replaced Java-style getters to follow Kotlin best practices.
 *
 * Coverage:
 * - Kotlin property accessors (trainNumber, trainVelocity, trainAcceleration, trainLength)
 * - Position properties (totalDistance, frontPosition, frontSection)
 * - Origin and separator properties (originInOut, trainEntrySeparator)
 * - Property value validation
 *
 * @since 2026-02-06 (Public Train API for animation)
 */
class TrainPublicAPITest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	/**
	 * Creates a minimal valid timetable for testing.
	 * @param length Train length in meters
	 */
	private fun createTimetableWithLength(length: Double): Timetable {
		val mockInOut = mockk<DynamicInOut>()
		every { mockInOut.name } returns "MOCK_IN"

		val mockOutOut = mockk<DynamicInOut>()
		every { mockOutOut.name } returns "MOCK_OUT"

		return Timetable(mockInOut, mockOutOut, Time(0.0), Time(0.0), length)
	}

	@Nested
	@DisplayName("Kotlin property accessors")
	inner class PropertyAccessorTests {
		@Test
		fun trainNumber_delegatesToGetNumber() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val numberFromProperty = train.trainNumber
			val numberFromGetter = train.getNumber()

			// Assert - Both should return the same value
			assertThat(numberFromProperty).isEqualTo(numberFromGetter)
			assertThat(numberFromProperty).isNotNull()
		}

		@Test
		fun trainVelocity_delegatesToGetVelocity() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val velocityFromProperty = train.trainVelocity
			val velocityFromGetter = train.getVelocity()

			// Assert - Both should return the same value (initially 0.0)
			assertThat(velocityFromProperty).isEqualTo(velocityFromGetter)
			assertThat(velocityFromProperty).isEqualTo(0.0)
		}

		@Test
		fun trainAcceleration_delegatesToGetAcceleration() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val accelFromProperty = train.trainAcceleration
			val accelFromGetter = train.getAcceleration()

			// Assert - Both should return the same value (initially 0.0)
			assertThat(accelFromProperty).isEqualTo(accelFromGetter)
			assertThat(accelFromProperty).isEqualTo(0.0)
		}

		@Test
		fun trainLength_delegatesToGetLength() {
			// Arrange
			val expectedLength = 150.0
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val lengthFromProperty = train.trainLength
			val lengthFromGetter = train.getLength()

			// Assert - Both should return the same value
			assertThat(lengthFromProperty).isEqualTo(lengthFromGetter)
			assertThat(lengthFromProperty).isEqualTo(expectedLength)
		}
	}

	@Nested
	@DisplayName("Position property accessors")
	inner class PositionPropertyTests {
		@Test
		fun totalDistance_returnsExpectedValue() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val distance = train.totalDistance

			// Assert - Initially 0.0 before train starts moving
			assertThat(distance).isEqualTo(0.0)
		}

		@Test
		fun frontPosition_returnsExpectedValue() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val position = train.frontPosition

			// Assert - Initially 0.0 before train starts moving
			assertThat(position).isEqualTo(0.0)
		}

		@Test
		fun frontSection_returnsExpectedValue() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val section = train.frontSection

			// Assert - Initially null until train starts moving
			assertThat(section).isEqualTo(null)
		}
	}

	@Nested
	@DisplayName("Origin and separator property accessors")
	inner class OriginPropertyTests {
		@Test
		fun originInOut_returnsExpectedValue() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val origin = train.originInOut

			// Assert - Should return the origin InOut from timetable
			assertThat(origin).isNotNull()
		}

		@Test
		fun trainEntrySeparator_returnsExpectedValue() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val separator = train.trainEntrySeparator

			// Assert - Initially null until train enters a section
			assertThat(separator).isEqualTo(null)
		}
	}

	@Nested
	@DisplayName("API consistency validation")
	inner class APIConsistencyTests {
		@Test
		fun allPropertiesReturnExpectedValues() {
			// Arrange
			val expectedLength = 100.0
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act & Assert - Validate all properties return expected values
			assertThat(train.trainNumber).isNotNull()
			assertThat(train.trainVelocity).isEqualTo(0.0)
			assertThat(train.trainAcceleration).isEqualTo(0.0)
			assertThat(train.trainLength).isEqualTo(expectedLength)
			assertThat(train.totalDistance).isEqualTo(0.0)
			assertThat(train.frontPosition).isEqualTo(0.0)
			// frontSection is null before train starts
			// originInOut is set from timetable
			assertThat(train.originInOut).isNotNull()
			// trainEntrySeparator is null before train enters section
		}

		@Test
		fun trainLengthMatchesTimetableLength() {
			// Arrange
			val expectedLength = 175.5
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val length = train.trainLength

			// Assert - Should return timetable length
			assertThat(length).isEqualTo(expectedLength)
			assertThat(train.getLength()).isEqualTo(expectedLength)
		}
	}
}
