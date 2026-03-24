package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotEmpty
import kotlin.test.Test

class PlatformIdentityTest {

	/**
	 * Two data class instances with equal values must get DIFFERENT identity codes,
	 * because [platformIdentityCode] is identity-based (per-instance), not value-based.
	 */
	@Test
	fun `equal data class instances get different identity codes`() {
		data class Point(val x: Int, val y: Int)

		val a = Point(1, 2)
		val b = Point(1, 2)

		// Sanity: the two instances are equal by value
		assertThat(a).isEqualTo(b)

		// Identity codes must differ because they are separate instances
		assertThat(platformIdentityCode(a)).isNotEqualTo(platformIdentityCode(b))
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
