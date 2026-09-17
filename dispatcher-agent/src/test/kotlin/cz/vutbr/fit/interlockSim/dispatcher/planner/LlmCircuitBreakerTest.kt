/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LlmCircuitBreaker] (Issue #1058).
 *
 * All state transitions are keyed on plain `simTime: Double` values passed in by the test, per
 * the class's "why simulation time, not wall-clock" design note — no coroutines, no clock, no
 * real waits anywhere in this file.
 */
class LlmCircuitBreakerTest {
	@Test
	fun `starts closed and always allows an attempt`() {
		val breaker = LlmCircuitBreaker()

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.CLOSED)
		assertThat(breaker.shouldAttempt(simTime = 0.0)).isTrue()
	}

	@Test
	fun `opens after failureThreshold consecutive failures`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 3, cooldownSeconds = 60.0)

		breaker.recordFailure(simTime = 10.0)
		breaker.recordFailure(simTime = 20.0)
		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.CLOSED)

		breaker.recordFailure(simTime = 30.0)

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
	}

	@Test
	fun `a single failure opens the breaker when failureThreshold is 1`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)

		breaker.recordFailure(simTime = 0.0)

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
	}

	@Test
	fun `shouldAttempt returns false while inside the cooldown window`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 100.0)

		assertThat(breaker.shouldAttempt(simTime = 130.0)).isFalse()
		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
	}

	@Test
	fun `shouldAttempt transitions to HALF_OPEN and returns true once the cooldown elapses`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 100.0)

		val result = breaker.shouldAttempt(simTime = 160.0)

		assertThat(result).isTrue()
		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.HALF_OPEN)
	}

	@Test
	fun `a HALF_OPEN success closes the breaker and resets the failure count`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 100.0)
		breaker.shouldAttempt(simTime = 160.0)

		breaker.recordSuccess()

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.CLOSED)
		assertThat(breaker.consecutiveFailures).isEqualTo(0)
	}

	@Test
	fun `a HALF_OPEN failure re-opens the breaker immediately`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 3, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)
		breaker.recordFailure(simTime = 1.0)
		breaker.recordFailure(simTime = 2.0)
		breaker.shouldAttempt(simTime = 62.0)

		breaker.recordFailure(simTime = 62.0)

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.OPEN)
		assertThat(breaker.openCount).isEqualTo(2L)
	}

	@Test
	fun `a success at any point resets the consecutive failure count`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 3, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)
		breaker.recordFailure(simTime = 1.0)

		breaker.recordSuccess()
		breaker.recordFailure(simTime = 2.0)
		breaker.recordFailure(simTime = 3.0)

		// Only 2 consecutive failures since the reset — must not have accumulated to 4 and opened.
		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.CLOSED)
	}

	@Test
	fun `each skip while OPEN is counted`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)

		breaker.shouldAttempt(simTime = 10.0)
		breaker.shouldAttempt(simTime = 20.0)

		assertThat(breaker.totalSkips).isEqualTo(2L)
	}

	@Test
	fun `rejects a non-positive failureThreshold`() {
		assertFailure { LlmCircuitBreaker(failureThreshold = 0) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	fun `rejects a non-positive cooldownSeconds`() {
		assertFailure { LlmCircuitBreaker(cooldownSeconds = 0.0) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	fun `rejects a non-finite cooldownSeconds`() {
		// +Inf parses as a Double and passes `> 0`, so a plain range check would accept it — but
		// an infinite cooldown means the breaker never probes recovery again, the exact "wedged
		// OPEN" state the check exists to prevent. NaN is non-finite too (and fails `> 0` anyway).
		assertFailure { LlmCircuitBreaker(cooldownSeconds = Double.POSITIVE_INFINITY) }
			.isInstanceOf<IllegalArgumentException>()
		assertFailure { LlmCircuitBreaker(cooldownSeconds = Double.NaN) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	fun `HALF_OPEN grants exactly one probe at a time`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)

		// Cooldown elapsed: caller 1 is granted the probe.
		assertThat(breaker.shouldAttempt(simTime = 60.0)).isTrue()

		// Caller 2 arrives while that probe is still in flight (concurrent plan() calls). Both
		// reaching the LLM would race their breaker verdicts against each other, so the second
		// caller is told to skip instead — counted like any other OPEN-window skip.
		assertThat(breaker.shouldAttempt(simTime = 61.0)).isFalse()
		assertThat(breaker.totalSkips).isEqualTo(1L)
	}

	@Test
	fun `a failed probe re-arms the single-probe guard for the next cooldown window`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)
		breaker.shouldAttempt(simTime = 60.0)
		breaker.recordFailure(simTime = 60.0)

		assertThat(breaker.shouldAttempt(simTime = 121.0)).isTrue()
		assertThat(breaker.shouldAttempt(simTime = 122.0)).isFalse()
	}

	@Test
	fun `an abandoned probe can be attempted again`() {
		// A probe coroutine cancelled mid-inference never reaches recordSuccess/recordFailure.
		// plan()'s try/finally calls abandonProbe() so the in-flight flag is released — without
		// it the breaker would grant no probe ever again while stuck in HALF_OPEN.
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 0.0)
		assertThat(breaker.shouldAttempt(simTime = 60.0)).isTrue()
		assertThat(breaker.shouldAttempt(simTime = 61.0)).isFalse()

		breaker.abandonProbe()

		assertThat(breaker.state).isEqualTo(LlmCircuitBreaker.State.HALF_OPEN)
		assertThat(breaker.shouldAttempt(simTime = 61.0)).isTrue()
	}
}
