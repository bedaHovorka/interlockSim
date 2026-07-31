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
import assertk.assertions.isNotEqualTo
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
 * Unit coverage for [TerminalFallbackGuard] (SP2c.5, Issue #828).
 *
 * The guard exists to answer exactly one question — "did the LLM abandon this run?" — and the
 * whole value of the type is that it answers `false` for a deliberate rule-based run. Getting
 * that distinction wrong would mark every P10 determinism gate run as FAILED.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
@DisplayName("SP2c.5 — TerminalFallbackGuard (#828)")
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

	@Test
	@DisplayName("a fresh guard starts Running")
	fun freshGuardIsRunning() {
		assertThat(TerminalFallbackGuard().currentOutcome).isEqualTo(RunOutcome.Running)
	}

	@Test
	@DisplayName("an empty tick record leaves the guard Running")
	fun emptyRecordLeavesRunning() {
		val guard = TerminalFallbackGuard()

		guard.observe(record())

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
	}

	/**
	 * The rule that matters: only [ActionAuthor.RULE_FALLBACK] is terminal. Would fail if
	 * `observe` widened its predicate to any other author.
	 */
	@ParameterizedTest(name = "author {0} leaves the run Running")
	@EnumSource(
		ActionAuthor::class,
		names = ["LLM", "TIMEOUT_NOOP", "RULE_BASED", "OPERATOR"]
	)
	@DisplayName("every non-fallback author leaves the run Running")
	fun nonFallbackAuthorsLeaveRunning(author: ActionAuthor) {
		val guard = TerminalFallbackGuard()

		guard.observe(record(author))

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
	}

	@Test
	@DisplayName("a RULE_FALLBACK action transitions to Failed(LLM_ABANDONED)")
	fun ruleFallbackFails() {
		val guard = TerminalFallbackGuard()

		guard.observe(record(ActionAuthor.RULE_FALLBACK))

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
	}

	@Test
	@DisplayName("a single RULE_FALLBACK among several actions is enough to fail the run")
	fun oneFallbackAmongManyFails() {
		val guard = TerminalFallbackGuard()

		guard.observe(record(ActionAuthor.RULE_BASED, ActionAuthor.RULE_FALLBACK, ActionAuthor.LLM))

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
	}

	/**
	 * Would fail if the early `if (outcomeState is Failed) return` guard were removed and a later
	 * clean record were allowed to overwrite the failure.
	 */
	@Test
	@DisplayName("Failed is terminal — later clean records never revert it")
	fun failedIsTerminal() {
		val guard = TerminalFallbackGuard()

		guard.observe(record(ActionAuthor.RULE_FALLBACK))
		guard.observe(record(ActionAuthor.RULE_BASED))
		guard.observe(record())

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
	}

	@Test
	@DisplayName("observing many clean records never leaves Running")
	fun manyCleanRecordsStayRunning() {
		val guard = TerminalFallbackGuard()

		repeat(50) { guard.observe(record(ActionAuthor.RULE_BASED, ActionAuthor.LLM)) }

		assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
		assertThat(guard.currentOutcome).isNotEqualTo(RunOutcome.Completed)
	}

	@Test
	@DisplayName("two guards are independent — one failing does not affect the other")
	fun guardsAreIndependent() {
		val failing = TerminalFallbackGuard()
		val clean = TerminalFallbackGuard()

		failing.observe(record(ActionAuthor.RULE_FALLBACK))
		clean.observe(record(ActionAuthor.RULE_BASED))

		assertThat(failing.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
		assertThat(clean.currentOutcome).isEqualTo(RunOutcome.Running)
	}
}
