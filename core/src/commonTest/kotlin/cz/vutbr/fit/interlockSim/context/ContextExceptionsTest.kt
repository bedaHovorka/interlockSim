/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for EmptyContextException and ContextCreationException constructor overloads.
 * Added for Issue #453 to cover all 4 ctor variants of each.
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.Test

class ContextExceptionsTest {
	private val testMessage = "context test failure"
	private val testCause = RuntimeException("root cause")

	@Test
	fun `EmptyContextException no-arg constructor`() {
		val e = EmptyContextException()
		assertThat(e).isInstanceOf(Exception::class)
		assertThat(e.message).isEqualTo(null)
		assertThat(e.cause).isEqualTo(null)
	}

	@Test
	fun `EmptyContextException message-only constructor`() {
		val e = EmptyContextException(testMessage)
		assertThat(e.message).isEqualTo(testMessage)
		assertThat(e.cause).isEqualTo(null)
	}

	@Test
	fun `EmptyContextException cause-only constructor`() {
		val e = EmptyContextException(testCause)
		assertThat(e.cause).isEqualTo(testCause)
	}

	@Test
	fun `EmptyContextException message+cause constructor`() {
		val e = EmptyContextException(testMessage, testCause)
		assertThat(e.message).isEqualTo(testMessage)
		assertThat(e.cause).isEqualTo(testCause)
	}

	@Test
	fun `ContextCreationException no-arg constructor`() {
		val e = ContextCreationException()
		assertThat(e).isInstanceOf(Exception::class)
		assertThat(e.cause).isEqualTo(null)
	}

	@Test
	fun `ContextCreationException cause-only constructor`() {
		val e = ContextCreationException(testCause)
		assertThat(e.cause).isEqualTo(testCause)
	}

	@Test
	fun `ContextCreationException string-only constructor`() {
		val e = ContextCreationException(testMessage)
		assertThat(e.message).isEqualTo(testMessage)
		assertThat(e.cause).isEqualTo(null)
	}

	@Test
	fun `ContextCreationException message+cause constructor preserves both`() {
		val e = ContextCreationException(testMessage, testCause)
		assertThat(e).isNotNull()
		assertThat(e.message).isEqualTo(testMessage)
		assertThat(e.cause).isEqualTo(testCause)
	}

	@Test
	fun `ContextCreationException message+null cause is allowed`() {
		val e = ContextCreationException(testMessage, null)
		assertThat(e.message).isEqualTo(testMessage)
		assertThat(e.cause).isEqualTo(null)
	}
}
