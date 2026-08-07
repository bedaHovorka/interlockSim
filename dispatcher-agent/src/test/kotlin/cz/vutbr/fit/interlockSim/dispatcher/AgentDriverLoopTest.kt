/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [AgentDriverLoop] (Issue #847 round 4, finding R4-2).
 *
 * ## The defect this class exists to close
 *
 * The dispatcher-agent driver used to be a bare loop installed straight onto
 * `ShuntingLoop.agentDriverAction`:
 *
 * ```kotlin
 * loop.agentDriverAction = {
 *     while (loop.isSimActive()) { driver.runCycle() }
 * }
 * ```
 *
 * run by `platformStartDaemonThread`, which is `Thread { runBlocking { action() } }` with **no**
 * uncaught-exception handler. Anything escaping `runCycle()` therefore killed the driver thread
 * silently while the simulation kept ticking to its requested end time — a run that produced no
 * further decisions but still exited 0 and still reported a full-length simulated horizon.
 *
 * That is the fingerprint of round 3's run 1: the last planner summary was logged at
 * `simTime=216.0s, totalCycles=10` and no later summary ever appeared, yet the run continued to
 * 600.9 s.
 *
 * These tests pin the three properties that make such a death impossible to miss: a failing cycle
 * is logged and counted rather than fatal, a persistently failing loop stops loudly instead of
 * spinning, and cancellation still propagates.
 */
@DisplayName("AgentDriverLoop — a failing cycle must never kill the driver silently")
class AgentDriverLoopTest {
	@Test
	@DisplayName("runs cycles until the loop is no longer active")
	fun runsUntilInactive() {
		var cycles = 0
		val loop =
			AgentDriverLoop(
				isActive = { cycles < 3 },
				runCycle = {
					cycles++
					true
				}
			)

		runBlocking { loop.run() }

		assertThat(cycles, "cycles run").isEqualTo(3)
		assertThat(loop.stoppedByFailures, "stoppedByFailures").isFalse()
		assertThat(loop.failureCount, "failureCount").isEqualTo(0)
	}

	@Test
	@DisplayName("a throwing cycle is counted and the loop continues")
	fun throwingCycleDoesNotEndTheLoop() {
		var cycles = 0
		val loop =
			AgentDriverLoop(
				isActive = { cycles < 3 },
				runCycle = {
					cycles++
					if (cycles == 1) error("boom") else true
				}
			)

		runBlocking { loop.run() }

		assertThat(cycles, "cycles attempted").isEqualTo(3)
		assertThat(loop.failureCount, "failureCount").isEqualTo(1)
		assertThat(loop.stoppedByFailures, "stoppedByFailures").isFalse()
		assertThat(loop.lastFailure, "lastFailure").isNotNull()
	}

	@Test
	@DisplayName("consecutive failures past the bound stop the loop instead of spinning forever")
	fun persistentFailuresStopTheLoop() {
		var cycles = 0
		val loop =
			AgentDriverLoop(
				isActive = { true },
				runCycle = {
					cycles++
					error("always fails")
				},
				maxConsecutiveFailures = 4
			)

		runBlocking { loop.run() }

		assertThat(cycles, "cycles attempted").isEqualTo(4)
		assertThat(loop.failureCount, "failureCount").isEqualTo(4)
		assertThat(loop.stoppedByFailures, "stoppedByFailures").isTrue()
	}

	@Test
	@DisplayName("a successful cycle resets the consecutive-failure count")
	fun successResetsTheConsecutiveCount() {
		var cycles = 0
		val loop =
			AgentDriverLoop(
				isActive = { cycles < 9 },
				// Fails on every third cycle: never three failures in a row, so the loop must
				// run to completion even though it accumulates more failures than the bound.
				runCycle = {
					cycles++
					if (cycles % 3 == 0) error("intermittent") else true
				},
				maxConsecutiveFailures = 2
			)

		runBlocking { loop.run() }

		assertThat(cycles, "cycles attempted").isEqualTo(9)
		assertThat(loop.failureCount, "failureCount").isEqualTo(3)
		assertThat(loop.stoppedByFailures, "stoppedByFailures").isFalse()
	}

	@Test
	@DisplayName("CancellationException propagates and is not counted as a cycle failure")
	fun cancellationPropagates() {
		val loop =
			AgentDriverLoop(
				isActive = { true },
				runCycle = { throw CancellationException("shutting down") }
			)

		val thrown = assertThrows<CancellationException> { runBlocking { loop.run() } }

		assertThat(thrown, "propagated exception").isInstanceOf(CancellationException::class)
		assertThat(loop.failureCount, "failureCount").isEqualTo(0)
	}

	@Test
	@DisplayName("cycle count reflects every attempt, so a silent death is visible in the summary")
	fun cycleCountIsObservable() {
		var remaining = 5
		val loop =
			AgentDriverLoop(
				isActive = { remaining > 0 },
				runCycle = {
					remaining--
					true
				}
			)

		runBlocking { loop.run() }

		assertThat(loop.cycleCount, "cycleCount").isEqualTo(5L)
		assertThat(loop.cycleCount, "cycleCount").isGreaterThanOrEqualTo(loop.failureCount.toLong())
	}
}
