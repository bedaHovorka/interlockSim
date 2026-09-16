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
}
