/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the SIGINT signal handler infrastructure.
 *
 * Note: We cannot safely test actual signal delivery in unit tests (sending SIGINT
 * to the test process would kill it). These tests verify the constants and initial state.
 *
 * @since Issue #436 (graceful SIGINT shutdown)
 */
class SignalHandlerTest {
	@BeforeTest
	fun resetSignalState() {
		SignalState.INTERRUPTED.value = 0
	}

	@Test
	fun `SIGINT exit code follows Unix convention of 128 plus signal number`() {
		assertThat(SIGINT_EXIT_CODE).isEqualTo(130)
	}

	@Test
	fun `interrupted flag is initially false`() {
		assertThat(isInterrupted()).isFalse()
	}

	@Test
	fun `isInterrupted returns true after flag is set`() {
		SignalState.INTERRUPTED.value = 1
		assertThat(isInterrupted()).isTrue()
	}
}
