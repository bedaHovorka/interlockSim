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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for the SIGINT signal handler infrastructure.
 *
 * Note: We cannot safely test actual signal delivery in unit tests (sending SIGINT
 * to the test process would kill it). These tests verify the constants and initial state.
 *
 * @since Issue #436 (graceful SIGINT shutdown)
 */
class SignalHandlerTest {

	@Test
	fun `SIGINT exit code follows Unix convention of 128 plus signal number`() {
		assertEquals(130, SIGINT_EXIT_CODE)
	}

	@Test
	fun `interrupted flag is initially false`() {
		assertFalse(isInterrupted())
	}
}
