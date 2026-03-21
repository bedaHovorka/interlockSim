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
import kotlin.test.Test

/**
 * Unit tests for Time class - Construction and Validation.
 */
class TimeConstructionTest {
	@Test
	fun `time created from zero seconds`() {
		val time = Time(0.0)
		assertThat(time.value).isEqualTo(0.0)
	}

	@Test
	fun `time created from positive seconds`() {
		val time = Time(60.5)
		assertThat(time.value).isEqualTo(60.5)
	}

	@Test
	fun `time created from large value`() {
		val time = Time(86400.0)
		assertThat(time.value).isEqualTo(86400.0)
	}

	@Test
	fun `time stores exact value without modification`() {
		val expectedValue = 123.456789
		val time = Time(expectedValue)
		assertThat(time.value).isEqualTo(expectedValue)
	}
}

/**
 * Unit tests for Time class - Comparison Operations.
 */
class TimeComparisonTest {
	@Test
	fun `equal times compare as zero difference`() {
		val time1 = Time(60.0)
		val time2 = Time(60.0)
		assertThat(time1.compareTo(time2)).isEqualTo(0)
	}

	@Test
	fun `smaller time compares as negative`() {
		val time1 = Time(30.0)
		val time2 = Time(60.0)
		assertThat(time1.compareTo(time2) < 0).isTrue()
	}

	@Test
	fun `larger time compares as positive`() {
		val time1 = Time(90.0)
		val time2 = Time(60.0)
		assertThat(time1.compareTo(time2) > 0).isTrue()
	}

	@Test
	fun `equals and hashCode are consistent`() {
		val time1 = Time(45.5)
		val time2 = Time(45.5)
		assertThat(time1 == time2).isTrue()
		assertThat(time1.hashCode()).isEqualTo(time2.hashCode())
	}
}

/**
 * Unit tests for Time class - Equals Operation.
 */
class TimeEqualsTest {
	@Test
	fun `time equals itself`() {
		val time = Time(30.0)
		assertThat(time == time).isTrue()
	}

	@Test
	fun `times with same value are equal`() {
		val time1 = Time(50.0)
		val time2 = Time(50.0)
		assertThat(time1 == time2).isTrue()
	}

	@Test
	fun `times with different values are not equal`() {
		val time1 = Time(50.0)
		val time2 = Time(51.0)
		assertThat(time1 == time2).isFalse()
	}

	@Test
	fun `time is not equal to null`() {
		val time = Time(30.0)
		assertThat(time == null).isFalse()
	}
}

/**
 * Unit tests for Time class - Edge Cases.
 */
class TimeEdgeCasesTest {
	@Test
	fun `time at midnight zero seconds`() {
		val time = Time(0.0)
		assertThat(time.value).isEqualTo(0.0)
		assertThat(time.compareTo(Time(0.1)) < 0).isTrue()
	}

	@Test
	fun `time at end of day boundary`() {
		val time = Time(86399.999)
		assertThat(time.value).isEqualTo(86399.999)
		assertThat(time.compareTo(Time(86400.0)) < 0).isTrue()
	}

	@Test
	fun `time with fractional seconds`() {
		val time = Time(123.456789)
		assertThat(time.value).isEqualTo(123.456789)
	}

	@Test
	fun `compareTo implements transitivity`() {
		val time1 = Time(10.0)
		val time2 = Time(20.0)
		val time3 = Time(30.0)
		assertThat(time1.compareTo(time2) < 0).isTrue()
		assertThat(time2.compareTo(time3) < 0).isTrue()
		assertThat(time1.compareTo(time3) < 0).isTrue()
	}
}

/**
 * Unit tests for Time class - Hash Code Contract.
 */
class TimeHashCodeContractTest {
	@Test
	fun `different times have different hashCode usually`() {
		val time1 = Time(1.0)
		val time2 = Time(2.0)
		assertThat(time1.hashCode()).isNotEqualTo(time2.hashCode())
	}

	@Test
	fun `hashCode remains consistent across calls`() {
		val time = Time(42.0)
		val hash1 = time.hashCode()
		val hash2 = time.hashCode()
		assertThat(hash1).isEqualTo(hash2)
	}

	@Test
	fun `zero value produces valid hashCode`() {
		val time = Time(0.0)
		val hash = time.hashCode()
		assertThat(hash).isEqualTo(time.hashCode())
	}
}

/**
 * Unit tests for Time class - Comparable Interface.
 */
class TimeComparableTest {
	@Test
	fun `times are sorted correctly in collection`() {
		val times = listOf(Time(30.0), Time(10.0), Time(50.0), Time(20.0))
		val sorted = times.sorted()
		assertThat(sorted[0].value).isEqualTo(10.0)
		assertThat(sorted[1].value).isEqualTo(20.0)
		assertThat(sorted[2].value).isEqualTo(30.0)
		assertThat(sorted[3].value).isEqualTo(50.0)
	}

	@Test
	fun `compareTo with same value returns zero`() {
		val time1 = Time(100.0)
		val time2 = Time(100.0)
		assertThat(time1.compareTo(time2)).isEqualTo(0)
	}

	@Test
	fun `compareTo respects double ordering`() {
		val time1 = Time(1.1)
		val time2 = Time(1.2)
		assertThat(time1.compareTo(time2) < 0).isTrue()
		assertThat(time2.compareTo(time1) > 0).isTrue()
	}
}
