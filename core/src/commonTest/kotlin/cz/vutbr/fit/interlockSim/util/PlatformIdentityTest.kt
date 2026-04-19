package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import kotlin.test.Test

class PlatformIdentityTest {
	/**
	 * Identity codes for equal-by-value instances should generally differ.
	 * We create 100 instances and verify that not all collapse to the same code,
	 * which makes the test robust against rare hash collisions.
	 */
	@Test
	fun `equal data class instances generally get different identity codes`() {
		data class TestPoint(
			val x: Int,
			val y: Int
		)

		val instances = List(100) { TestPoint(1, 2) }

		// Sanity: all instances are equal by value
		assertThat(instances.distinct().size).isEqualTo(1)

		// Identity codes should not all be the same — at least 2 distinct values expected
		val distinctCodes = instances.map { platformIdentityCode(it) }.toSet()
		assertThat(distinctCodes.size).isGreaterThan(1)
	}

	@Test
	fun `same instance returns consistent identity code`() {
		val obj = object : Any() {}
		val code1 = platformIdentityCode(obj)
		val code2 = platformIdentityCode(obj)

		assertThat(code1).isNotEmpty()
		assertThat(code1).isEqualTo(code2)
	}
}
