package cz.vutbr.fit.interlockSim.testutil

import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.verify
import kotlin.test.Test
import kotlin.test.assertEquals

interface SmokeTarget {
	fun greet(name: String): String
}

class MokkerySmokeTest {
	@Test
	fun `mokkery mocks an interface and verifies the call`() {
		val target = mock<SmokeTarget>()
		every { target.greet("world") } returns "hello world"

		val result = target.greet("world")

		assertEquals("hello world", result)
		verify { target.greet("world") }
	}
}
