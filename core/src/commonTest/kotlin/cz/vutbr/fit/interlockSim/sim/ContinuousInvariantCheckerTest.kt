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
import cz.hovorka.kdisco.DiscoException
import kotlin.test.Test

/**
 * Unit tests for [ContinuousInvariantChecker].
 *
 * Tests the `derivatives()` contract: no exception when invariant holds,
 * exception when invariant is violated.
 *
 * When the invariant fails, `derivatives()` tries to build the error message via
 * `Process.time()`, which throws [DiscoException] outside an active simulation — so
 * [DiscoException] is the actual exception observed here, not [cz.vutbr.fit.interlockSim.exceptions.SimulationException].
 * `report()` is therefore never reached in this test context.
 *
 * Note: `start()` is not tested — it requires an active kDisco simulation context.
 */
class ContinuousInvariantCheckerTest {

	private var shouldPass = true

	private val checker = object : ContinuousInvariantChecker() {
		override fun check() = shouldPass
		override fun report(reportObj: StringBuilder): StringBuilder =
			reportObj.append("invariant violated")
	}

	@Test
	fun derivatives_invariantHolds_noException() {
		shouldPass = true
		checker.derivatives()
	}

	@Test
	fun derivatives_invariantViolated_throwsException() {
		shouldPass = false
		assertFailure { checker.derivatives() }.isInstanceOf<DiscoException>()
	}

	@Test
	fun derivatives_stateChangeFromHoldToViolate_throwsOnSecondCall() {
		// First call holds, second call after state flip throws.
		shouldPass = true
		checker.derivatives()

		shouldPass = false
		assertFailure { checker.derivatives() }.isInstanceOf<DiscoException>()
	}
}
