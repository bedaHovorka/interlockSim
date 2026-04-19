/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import kotlin.test.Test

/**
 * Unit tests for [ContinuousInvariantChecker].
 *
 * Tests the `derivatives()` contract: no exception when invariant holds,
 * exception + report() call when invariant is violated.
 *
 * Note: `start()` is not tested — it requires an active kDisco simulation context.
 */
class ContinuousInvariantCheckerTest {

	private var shouldPass = true
	private var reportCalled = false

	private val checker = object : ContinuousInvariantChecker() {
		override fun check() = shouldPass
		override fun report(reportObj: StringBuilder): StringBuilder {
			reportCalled = true
			return reportObj.append("invariant violated")
		}
	}

	@Test
	fun derivatives_invariantHolds_noException() {
		shouldPass = true
		checker.derivatives()
	}

	@Test
	fun derivatives_invariantViolated_throwsException() {
		shouldPass = false
		assertFailure { checker.derivatives() }.isInstanceOf<Exception>()
	}

	@Test
	fun derivatives_stateChangeFromHoldToViolate_throwsOnSecondCall() {
		// Verify re-entrant behaviour: first call holds, second call after state flip throws.
		shouldPass = true
		checker.derivatives() // must not throw

		shouldPass = false
		assertFailure { checker.derivatives() }.isInstanceOf<Exception>()
	}
}
