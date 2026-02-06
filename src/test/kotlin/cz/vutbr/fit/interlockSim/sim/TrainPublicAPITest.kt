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
import jDisco.Time
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for Train Public API for animation and external observation.
 *
 * Tests the Kotlin property-style accessors added to support idiomatic Kotlin usage
 * alongside existing Java-style getters.
 *
 * Coverage:
 * - Kotlin property accessors (trainNumber, trainVelocity, trainAcceleration, trainLength)
 * - Position properties (totalDistance, frontPosition, frontSection)
 * - Origin and separator properties (originInOut, trainEntrySeparator)
 * - Compatibility with Java-style getters
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
		fun totalDistance_delegatesToGetTotalDistance() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val distanceFromProperty = train.totalDistance
			val distanceFromGetter = train.getTotalDistance()

			// Assert - Both should return the same value (initially 0.0)
			assertThat(distanceFromProperty).isEqualTo(distanceFromGetter)
			assertThat(distanceFromProperty).isEqualTo(0.0)
		}

		@Test
		fun frontPosition_delegatesToGetFrontPosition() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val positionFromProperty = train.frontPosition
			val positionFromGetter = train.getFrontPosition()

			// Assert - Both should return the same value (initially 0.0)
			assertThat(positionFromProperty).isEqualTo(positionFromGetter)
			assertThat(positionFromProperty).isEqualTo(0.0)
		}

		@Test
		fun frontSection_delegatesToGetFrontSection() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val sectionFromProperty = train.frontSection
			val sectionFromGetter = train.getFrontSection()

			// Assert - Both should return the same value (initially null)
			assertThat(sectionFromProperty).isEqualTo(sectionFromGetter)
			// Note: Initially null until train starts moving
		}
	}

	@Nested
	@DisplayName("Origin and separator property accessors")
	inner class OriginPropertyTests {
		@Test
		fun originInOut_delegatesToGetOriginInOut() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val originFromProperty = train.originInOut
			val originFromGetter = train.getOriginInOut()

			// Assert - Both should return the same value
			assertThat(originFromProperty).isEqualTo(originFromGetter)
			assertThat(originFromProperty).isNotNull()
		}

		@Test
		fun trainEntrySeparator_delegatesToGetEntrySeparator() {
			// Arrange
			val timetable = createTimetableWithLength(150.0)
			val train = Train(mockContext, timetable)

			// Act
			val separatorFromProperty = train.trainEntrySeparator
			val separatorFromGetter = train.getEntrySeparator()

			// Assert - Both should return the same value (initially null)
			assertThat(separatorFromProperty).isEqualTo(separatorFromGetter)
			// Note: Initially null until train enters a section
		}
	}

	@Nested
	@DisplayName("API consistency validation")
	inner class APIConsistencyTests {
		@Test
		fun allPropertiesConsistentWithGetters() {
			// Arrange
			val expectedLength = 100.0
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act & Assert - Validate all properties delegate correctly
			assertThat(train.trainNumber).isEqualTo(train.getNumber())
			assertThat(train.trainVelocity).isEqualTo(train.getVelocity())
			assertThat(train.trainAcceleration).isEqualTo(train.getAcceleration())
			assertThat(train.trainLength).isEqualTo(train.getLength())
			assertThat(train.totalDistance).isEqualTo(train.getTotalDistance())
			assertThat(train.frontPosition).isEqualTo(train.getFrontPosition())
			assertThat(train.frontSection).isEqualTo(train.getFrontSection())
			assertThat(train.originInOut).isEqualTo(train.getOriginInOut())
			assertThat(train.trainEntrySeparator).isEqualTo(train.getEntrySeparator())
		}

		@Test
		fun trainLengthMatchesTimetableLength() {
			// Arrange
			val expectedLength = 175.5
			val timetable = createTimetableWithLength(expectedLength)
			val train = Train(mockContext, timetable)

			// Act
			val lengthFromProperty = train.trainLength
			val lengthFromGetter = train.getLength()

			// Assert - Both methods should return timetable length
			assertThat(lengthFromProperty).isEqualTo(expectedLength)
			assertThat(lengthFromGetter).isEqualTo(expectedLength)
		}
	}
}
