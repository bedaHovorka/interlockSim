/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.TimeUnit

/**
 * Unit coverage for [TerminalFallbackGuard] (SP2c.8, Issue #831).
 *
 * The guard answers one question: "did the LLM fail to produce output for [threshold]
 * consecutive ticks?" It must NOT engage for a correctly-idle LLM that emits `no_op` authored
 * [ActionAuthor.LLM], and must engage immediately on an unhandled emission exception via
 * [TerminalFallbackGuard.engageImmediately].
 *
 * Key invariant: only [ActionAuthor.TIMEOUT_NOOP] increments the counter. Any other author
 * (including [ActionAuthor.LLM], [ActionAuthor.RULE_BASED], [ActionAuthor.RULE_FALLBACK]) resets
 * it to 0. A healthy idle station emitting `no_op` authored [ActionAuthor.LLM] must never trip
 * the guard (500-tick test).
 *
 * @since Issue #831 (SP2c.8 — Goal 10 terminal fallback guard redesign)
 */
@DisplayName("SP2c.8 — TerminalFallbackGuard (#831)")
@Timeout(10, unit = TimeUnit.SECONDS)
class TerminalFallbackGuardTest {
	private fun record(vararg authors: ActionAuthor): TickRecord =
		TickRecord(
			tick = 1L,
			simTime = 10.0,
			stateDigest = "digest",
			actions =
				authors.mapIndexed { index, author ->
					AttributedAction(
						commandId = CommandId(index.toLong()),
						tick = 1L,
						action = DispatchAction.NoOp,
						author = author
					)
				},
			verdicts = authors.map { ValidationVerdict.Valid },
			outcomes = emptyList()
		)

	// ── Basic initial state ────────────────────────────────────────────────

	@Test
	@DisplayName("a fresh guard starts Running and not engaged")
	fun freshGuardIsRunning() {
		val guard = TerminalFallbackGuard()
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.engaged).isFalse()
	}

	@Test
	@DisplayName("an empty tick record leaves the guard Running")
	fun emptyRecordLeavesRunning() {
		val guard = TerminalFallbackGuard()
		guard.observe(record())
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
	}

	// ── Single-tick counter semantics ─────────────────────────────────────

	/**
	 * A single tick of any author leaves the guard Running, because:
	 * - TIMEOUT_NOOP increments counter to 1, which is < threshold=5
	 * - every other author resets counter to 0
	 */
	@ParameterizedTest(name = "single tick with author {0} leaves the guard Running")
	@EnumSource(ActionAuthor::class)
	@DisplayName("every author in a single tick leaves the guard Running (threshold not yet reached)")
	fun singleTickAlwaysLeavesRunning(author: ActionAuthor) {
		val guard = TerminalFallbackGuard()
		guard.observe(record(author))
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.engaged).isFalse()
	}

	// ── Counter threshold ─────────────────────────────────────────────────

	@Test
	@DisplayName("4 consecutive TIMEOUT_NOOP ticks ⇒ still Running (counter below threshold)")
	fun fourTimeoutsStillRunning() {
		val guard = TerminalFallbackGuard(threshold = 5)
		repeat(4) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.engaged).isFalse()
	}

	@Test
	@DisplayName("5th consecutive TIMEOUT_NOOP tick ⇒ Failed(LLM_ABANDONED)")
	fun fifthTimeoutEngagesGuard() {
		val guard = TerminalFallbackGuard(threshold = 5)
		repeat(5) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		assertThat(guard.engaged).isTrue()
	}

	@Test
	@DisplayName("4 TIMEOUT_NOOP then one LLM no_op resets counter; guard stays Running")
	fun timeoutThenLlmNoOpResetsCounter() {
		val guard = TerminalFallbackGuard(threshold = 5)
		// 4 timeouts build up the counter to 4
		repeat(4) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		// LLM no_op resets the counter to 0
		guard.observe(record(ActionAuthor.LLM))
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		// 4 more timeouts after the reset — still below threshold
		repeat(4) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.engaged).isFalse()
	}

	/**
	 * The single most likely way to get SP2c.8 wrong: if LLM no_op did NOT reset the
	 * counter, every healthy idle station would trip the guard in 5 ticks.
	 */
	@Test
	@DisplayName("500 consecutive LLM no_op ticks ⇒ Running throughout (healthy idle station)")
	fun fiveHundredLlmNoOpsNeverFail() {
		val guard = TerminalFallbackGuard(threshold = 5)
		repeat(500) { guard.observe(record(ActionAuthor.LLM)) }
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.engaged).isFalse()
	}

	// ── engageImmediately — exception path ────────────────────────────────

	@Test
	@DisplayName("engageImmediately() without prior ticks ⇒ immediate Failed(LLM_ABANDONED)")
	fun engageImmediatelyFails() {
		val guard = TerminalFallbackGuard(threshold = 5)
		guard.engageImmediately()
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		assertThat(guard.engaged).isTrue()
	}

	@Test
	@DisplayName("engageImmediately() after 1 timeout ⇒ immediate Failed (no need to reach threshold)")
	fun engageImmediatelyOverridesCounter() {
		val guard = TerminalFallbackGuard(threshold = 5)
		guard.observe(record(ActionAuthor.TIMEOUT_NOOP))
		guard.engageImmediately()
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
	}

	@Test
	@DisplayName("engageImmediately() is idempotent — calling twice does not double-fire callback")
	fun engageImmediatelyIsIdempotent() {
		var callbackCount = 0
		val guard = TerminalFallbackGuard(threshold = 5, onAbandoned = { callbackCount++ })
		guard.engageImmediately()
		guard.engageImmediately()
		assertThat(callbackCount).isEqualTo(1)
	}

	// ── onAbandoned callback ──────────────────────────────────────────────

	@Test
	@DisplayName("onAbandoned callback fires exactly once when threshold is reached")
	fun onAbandonedFiresOnce() {
		var callbackCount = 0
		var receivedReason: FailureReason? = null
		val guard = TerminalFallbackGuard(threshold = 5) { reason ->
			callbackCount++
			receivedReason = reason
		}
		repeat(5) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		assertThat(callbackCount).isEqualTo(1)
		assertThat(receivedReason).isEqualTo(FailureReason.LLM_ABANDONED)
	}

	@Test
	@DisplayName("onAbandoned does not fire again on subsequent observe() calls after engagement")
	fun onAbandonedDoesNotFireAfterEngagement() {
		var callbackCount = 0
		val guard = TerminalFallbackGuard(threshold = 5) { callbackCount++ }
		repeat(5) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		// Additional ticks after engagement — callback must not fire again
		repeat(5) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		assertThat(callbackCount).isEqualTo(1)
	}

	// ── Terminal once engaged ──────────────────────────────────────────────

	/**
	 * Would fail if the early `if (_engaged) return` guard were removed and a later
	 * clean record were allowed to revert the failure.
	 */
	@Test
	@DisplayName("Failed is terminal — later clean records never revert it")
	fun failedIsTerminal() {
		val guard = TerminalFallbackGuard(threshold = 5)
		repeat(5) { guard.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		guard.observe(record(ActionAuthor.LLM))
		guard.observe(record(ActionAuthor.RULE_BASED))
		guard.observe(record())
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
	}

	@Test
	@DisplayName("observing many clean records never leaves Running (no TIMEOUT_NOOP — no failure)")
	fun manyCleanRecordsStayRunning() {
		val guard = TerminalFallbackGuard()
		repeat(50) { guard.observe(record(ActionAuthor.RULE_BASED, ActionAuthor.LLM)) }
		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.currentOutcome).isNotEqualTo(RunOutcome.Completed)
	}

	// ── Instance isolation ─────────────────────────────────────────────────

	@Test
	@DisplayName("two guards are independent — one engaging does not affect the other")
	fun guardsAreIndependent() {
		val failing = TerminalFallbackGuard(threshold = 5)
		val clean = TerminalFallbackGuard(threshold = 5)

		repeat(5) { failing.observe(record(ActionAuthor.TIMEOUT_NOOP)) }
		clean.observe(record(ActionAuthor.RULE_BASED))

		assertThat(failing.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		assertThat(clean.currentOutcome).isEqualTo(RunOutcome.Running)
	}
}
