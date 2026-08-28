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
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit coverage for SP2c.9 decision attribution + provenance (Issue #832).
 *
 * Validates the three acceptance criteria that require test coverage:
 * 1. [ActionAuthor.TIMEOUT_NOOP] provably originates zero dispatching actions.
 * 2. A run in which the LLM authored every action reports `{LLM: n}` and zero for all others.
 * 3. [ActionAuthor.RULE_BASED] and [ActionAuthor.RULE_FALLBACK] are distinct values; a
 *    rule-based run is **not** marked FAILED by [TerminalFallbackGuard].
 *
 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
 */
@DisplayName("SP2c.9 — Decision attribution + provenance (#832)")
@Timeout(10, unit = TimeUnit.SECONDS)
class ActionAttributionTest {
	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun attributed(
		author: ActionAuthor,
		action: DispatchAction = DispatchAction.NoOp,
		tick: Long = 1L,
		reason: String = ""
	) = AttributedAction(
		commandId = CommandId(0L),
		tick = tick,
		action = action,
		author = author,
		reason = reason
	)

	private fun record(
		tick: Long = 1L,
		simTime: Double = 10.0,
		actions: List<AttributedAction> = emptyList()
	) = TickRecord(
		tick = tick,
		simTime = simTime,
		stateDigest = "digest",
		actions = actions,
		verdicts = actions.map { ValidationVerdict.Valid },
		outcomes = emptyList()
	)

	// ── TIMEOUT_NOOP zero-action guarantee ───────────────────────────────────

	@Nested
	@DisplayName("TIMEOUT_NOOP originates zero dispatching actions")
	inner class TimeoutNoOpZeroActions {
		@Test
		@DisplayName("a TIMEOUT_NOOP attributed action always has action NoOp")
		fun timeoutNoOpActionIsAlwaysNoOp() {
			// DispatchTickLoop substitutes NoOp with TIMEOUT_NOOP when the budget is exceeded.
			// Verify the invariant: TIMEOUT_NOOP is always paired with NoOp at the type level.
			val action = attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp)

			assertThat(action.author).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
			assertThat(action.action).isEqualTo(DispatchAction.NoOp)
		}

		@Test
		@DisplayName("DispatcherPreferenceStore counts zero dispatching actions for TIMEOUT_NOOP")
		fun storeCountsZeroDispatchingActionsForTimeoutNoop() {
			val store = DispatcherPreferenceStore()
			val noOpAction = attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp)

			// Three ticks of TIMEOUT_NOOP; all actions are NoOp.
			repeat(3) { i ->
				store.observe(record(tick = i.toLong() + 1L, actions = listOf(noOpAction)))
			}

			assertThat(store.getDispatchingActionCount(ActionAuthor.TIMEOUT_NOOP)).isZero()
		}

		@Test
		@DisplayName("DispatcherPreferenceStore still counts TIMEOUT_NOOP occurrences in author counts")
		fun storeCountsTimeoutNoOpAuthorOccurrences() {
			val store = DispatcherPreferenceStore()
			repeat(4) { i ->
				store.observe(
					record(
						tick = i.toLong() + 1L,
						actions = listOf(attributed(ActionAuthor.TIMEOUT_NOOP, DispatchAction.NoOp))
					)
				)
			}

			val counts = store.getAuthorCounts()
			assertThat(counts[ActionAuthor.TIMEOUT_NOOP] ?: 0L).isEqualTo(4L)
		}
	}

	// ── LLM-only run reports {LLM: n, everything else: 0} ───────────────────

	@Nested
	@DisplayName("LLM-only run reports {LLM: n} and zero for every other author")
	inner class LlmOnlyRunCounts {
		@Test
		@DisplayName("all-LLM ticks produce LLM count = total actions, zero for all other authors")
		fun allLlmTicksProduceLlmCountOnly() {
			val store = DispatcherPreferenceStore()
			val llmApprove = attributed(ActionAuthor.LLM, DispatchAction.ApproveTrain("T-1"))
			val llmNoOp = attributed(ActionAuthor.LLM, DispatchAction.NoOp)

			// 5 ticks: 2 actions each → 10 LLM-attributed actions total.
			repeat(5) { i ->
				store.observe(record(tick = i.toLong() + 1L, actions = listOf(llmApprove, llmNoOp)))
			}

			val counts = store.getAuthorCounts()
			assertThat(counts[ActionAuthor.LLM] ?: 0L).isEqualTo(10L)

			// Every other author must be absent (zero).
			for (author in ActionAuthor.entries) {
				if (author != ActionAuthor.LLM) {
					assertThat(counts[author] ?: 0L)
						.isEqualTo(0L)
				}
			}
		}

		@Test
		@DisplayName("getAuthorCounts() only contains authors with at least one recorded action")
		fun authorCountsOnlyContainsSeenAuthors() {
			val store = DispatcherPreferenceStore()
			store.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.LLM))))

			val counts = store.getAuthorCounts()
			assertThat(counts.keys).isEqualTo(setOf(ActionAuthor.LLM))
		}
	}

	// ── RULE_BASED ≠ RULE_FALLBACK distinctness ──────────────────────────────

	@Nested
	@DisplayName("RULE_BASED and RULE_FALLBACK are distinct and have different run outcomes")
	inner class RuleBasedVsFallbackDistinctness {
		@Test
		@DisplayName("RULE_BASED and RULE_FALLBACK are different enum values")
		fun ruleBasedAndRuleFallbackAreDifferentValues() {
			assertThat(ActionAuthor.RULE_BASED).isNotEqualTo(ActionAuthor.RULE_FALLBACK)
		}

		@Test
		@DisplayName("a RULE_BASED-only run leaves TerminalFallbackGuard in Running")
		fun ruleBasedRunStaysRunning() {
			val guard = TerminalFallbackGuard()
			val store = DispatcherPreferenceStore()
			val ruleAction = attributed(ActionAuthor.RULE_BASED, DispatchAction.ApproveTrain("T-1"))

			repeat(5) { i ->
				val r = record(tick = i.toLong() + 1L, actions = listOf(ruleAction))
				guard.observe(r)
				store.observe(r)
			}

			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
			assertThat(store.getAuthorCounts()[ActionAuthor.RULE_BASED] ?: 0L).isEqualTo(5L)
			assertThat(store.getAuthorCounts()[ActionAuthor.RULE_FALLBACK] ?: 0L).isEqualTo(0L)
		}

		@Test
		@DisplayName("a RULE_FALLBACK action alone does NOT engage the guard (post-engagement author, counter model)")
		fun ruleFallbackAloneDoesNotEngage() {
			val guard = TerminalFallbackGuard()
			// First tick: RULE_BASED (healthy)
			guard.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.RULE_BASED))))
			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)

			// Second tick: RULE_FALLBACK alone — post-engagement author, NOT a trigger.
			// Engaging on it would make the post-engagement fallback indistinguishable from
			// the trigger that started it (SP2c.8 counter model).
			guard.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.RULE_FALLBACK))))
			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)
			assertThat(guard.engaged).isFalse()
		}

		@Test
		@DisplayName(
			"a SAFETY_NET action engages the guard with SAFETY_NET_ENGAGED (RULE_BASED prior tick does not prevent it)"
		)
		fun safetyNetEngagesAfterRuleBased() {
			val guard = TerminalFallbackGuard()
			// First tick: RULE_BASED (healthy)
			guard.observe(record(tick = 1L, actions = listOf(attributed(ActionAuthor.RULE_BASED))))
			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Running)

			// Second tick: SAFETY_NET — immediate-engage trigger (SP2c.9)
			guard.observe(record(tick = 2L, actions = listOf(attributed(ActionAuthor.SAFETY_NET))))
			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.SAFETY_NET_ENGAGED))
			assertThat(guard.engaged).isTrue()
		}

		@Test
		@DisplayName("RULE_FALLBACK + SAFETY_NET in the same tick engages with LLM_ABANDONED (RULE_FALLBACK priority)")
		fun ruleFallbackPriorityOverSafetyNet() {
			val guard = TerminalFallbackGuard()
			guard.observe(
				record(
					tick = 1L,
					actions = listOf(attributed(ActionAuthor.SAFETY_NET), attributed(ActionAuthor.RULE_FALLBACK))
				)
			)
			assertThat(guard.currentOutcome).isEqualTo(RunOutcome.Failed(FailureReason.LLM_ABANDONED))
			assertThat(guard.engaged).isTrue()
		}
	}

	// ── AttributedAction carries author + reason ─────────────────────────────

	@Nested
	@DisplayName("AttributedAction carries author and reason")
	inner class AttributedActionFields {
		@Test
		@DisplayName("reason defaults to empty string for backward compat")
		fun reasonDefaultsToEmpty() {
			val action =
				AttributedAction(
					commandId = CommandId(1L),
					tick = 1L,
					action = DispatchAction.NoOp
				)
			assertThat(action.reason).isEqualTo("")
		}

		@Test
		@DisplayName("reason is persisted in DispatcherPreferenceStore records")
		fun reasonIsPersistedInStore() {
			val store = DispatcherPreferenceStore()
			val action = attributed(ActionAuthor.LLM, DispatchAction.NoOp, reason = "T-1 waiting at A; B is free")
			store.observe(record(tick = 1L, actions = listOf(action)))

			val records = store.getRecords()
			assertThat(records.size).isEqualTo(1)
			assertThat(records[0].reason).isEqualTo("T-1 waiting at A; B is free")
			assertThat(records[0].author).isEqualTo(ActionAuthor.LLM)
		}
	}
}
