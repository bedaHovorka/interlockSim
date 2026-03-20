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
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for Time class.
 *
 * This test class focuses on validating:
 * - Time object construction and initialization
 * - Value storage and retrieval
 * - Comparison operations (implements Comparable<Time>)
 * - Equals and hashCode contract validation
 * - Edge cases with boundary times and special values
 *
 * The Time class is a simple value object representing a point in time as a Double value.
 * It is used in Timetable for scheduling trains and managing their entry/exit times.
 */
@DisplayName("Time Tests")
class TimeTest {
	@Nested
	@DisplayName("Construction and Validation")
	inner class ConstructionAndValidationTests {
		@Test
		fun `time created from zero seconds`() {
			// Arrange & Act
			val time = Time(0.0)

			// Assert
			assertThat(time.value).isEqualTo(0.0)
		}

		@Test
		fun `time created from positive seconds`() {
			// Arrange & Act
			val time = Time(60.5)

			// Assert
			assertThat(time.value).isEqualTo(60.5)
		}

		@Test
		fun `time created from large value`() {
			// Arrange & Act
			val time = Time(86400.0) // One day in seconds

			// Assert
			assertThat(time.value).isEqualTo(86400.0)
		}

		@Test
		fun `time stores exact value without modification`() {
			// Arrange
			val expectedValue = 123.456789

			// Act
			val time = Time(expectedValue)

			// Assert - Time should store the exact value as provided
			assertThat(time.value).isEqualTo(expectedValue)
		}
	}

	@Nested
	@DisplayName("Comparison Operations")
	inner class ComparisonOperationsTests {
		@Test
		fun `equal times compare as zero difference`() {
			// Arrange
			val time1 = Time(60.0)
			val time2 = Time(60.0)

			// Act & Assert
			assertThat(time1.compareTo(time2)).isEqualTo(0)
		}

		@Test
		fun `smaller time compares as negative`() {
			// Arrange
			val time1 = Time(30.0)
			val time2 = Time(60.0)

			// Act & Assert
			assertThat(time1.compareTo(time2) < 0).isTrue()
		}

		@Test
		fun `larger time compares as positive`() {
			// Arrange
			val time1 = Time(90.0)
			val time2 = Time(60.0)

			// Act & Assert
			assertThat(time1.compareTo(time2) > 0).isTrue()
		}

		@Test
		fun `equals and hashCode are consistent`() {
			// Arrange
			val time1 = Time(45.5)
			val time2 = Time(45.5)

			// Act & Assert
			// Equal objects must have equal hashCode
			assertThat(time1 == time2).isTrue()
			assertThat(time1.hashCode()).isEqualTo(time2.hashCode())
		}
	}

	@Nested
	@DisplayName("Equals Operation")
	inner class EqualsOperationTests {
		@Test
		fun `time equals itself`() {
			// Arrange
			val time = Time(30.0)

			// Act & Assert
			assertThat(time == time).isTrue()
		}

		@Test
		fun `times with same value are equal`() {
			// Arrange
			val time1 = Time(50.0)
			val time2 = Time(50.0)

			// Act & Assert
			assertThat(time1 == time2).isTrue()
		}

		@Test
		fun `times with different values are not equal`() {
			// Arrange
			val time1 = Time(50.0)
			val time2 = Time(51.0)

			// Act & Assert
			assertThat(time1 == time2).isFalse()
		}

		@Test
		fun `time is not equal to null`() {
			// Arrange
			val time = Time(30.0)

			// Act & Assert
			assertThat(time == null).isFalse()
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCasesTests {
		@Test
		fun `time at midnight zero seconds`() {
			// Arrange & Act
			val time = Time(0.0)

			// Assert
			assertThat(time.value).isEqualTo(0.0)
			assertThat(time.compareTo(Time(0.1)) < 0).isTrue()
		}

		@Test
		fun `time at end of day boundary`() {
			// Arrange & Act
			val time = Time(86399.999) // 23:59:59.999

			// Assert
			assertThat(time.value).isEqualTo(86399.999)
			assertThat(time.compareTo(Time(86400.0)) < 0).isTrue()
		}

		@Test
		fun `time with fractional seconds`() {
			// Arrange & Act
			val time = Time(123.456789)

			// Assert
			assertThat(time.value).isEqualTo(123.456789)
		}

		@Test
		fun `compareTo implements transitivity`() {
			// Arrange
			val time1 = Time(10.0)
			val time2 = Time(20.0)
			val time3 = Time(30.0)

			// Act & Assert
			// If time1 < time2 and time2 < time3, then time1 < time3
			val comparison12 = time1.compareTo(time2)
			val comparison23 = time2.compareTo(time3)
			val comparison13 = time1.compareTo(time3)

			assertThat(comparison12 < 0).isTrue()
			assertThat(comparison23 < 0).isTrue()
			assertThat(comparison13 < 0).isTrue()
		}
	}

	@Nested
	@DisplayName("Hash Code Contract")
	inner class HashCodeContractTests {
		@Test
		fun `different times have different hashCode (usually)`() {
			// Arrange
			val time1 = Time(1.0)
			val time2 = Time(2.0)

			// Act & Assert
			// Different values should (usually) have different hashes
			// This is not strictly required by the contract, but expected in most cases
			assertThat(time1.hashCode()).isNotEqualTo(time2.hashCode())
		}

		@Test
		fun `hashCode remains consistent across calls`() {
			// Arrange
			val time = Time(42.0)

			// Act
			val hash1 = time.hashCode()
			val hash2 = time.hashCode()

			// Assert
			assertThat(hash1).isEqualTo(hash2)
		}

		@Test
		fun `zero value produces valid hashCode`() {
			// Arrange & Act
			val time = Time(0.0)

			// Assert - should not throw and should return an int
			val hash = time.hashCode()
			assertThat(hash).isEqualTo(time.hashCode())
		}
	}

	@Nested
	@DisplayName("Comparable Interface")
	inner class ComparableInterfaceTests {
		@Test
		fun `times are sorted correctly in collection`() {
			// Arrange
			val times =
				listOf(
					Time(30.0),
					Time(10.0),
					Time(50.0),
					Time(20.0)
				)

			// Act
			val sorted = times.sorted()

			// Assert
			assertThat(sorted[0].value).isEqualTo(10.0)
			assertThat(sorted[1].value).isEqualTo(20.0)
			assertThat(sorted[2].value).isEqualTo(30.0)
			assertThat(sorted[3].value).isEqualTo(50.0)
		}

		@Test
		fun `compareTo with same value returns zero`() {
			// Arrange
			val time1 = Time(100.0)
			val time2 = Time(100.0)

			// Act & Assert
			assertThat(time1.compareTo(time2)).isEqualTo(0)
		}

		@Test
		fun `compareTo respects double ordering`() {
			// Arrange
			val time1 = Time(1.1)
			val time2 = Time(1.2)

			// Act & Assert
			assertThat(time1.compareTo(time2) < 0).isTrue()
			assertThat(time2.compareTo(time1) > 0).isTrue()
		}
	}
}
