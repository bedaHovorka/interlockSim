/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Unit tests for the [TickOutcome] taxonomy (Issue #842 — Goal 10 SP2c.19).
 *
 * Verifies:
 * - [TickOutcome.tickClass] and [TickOutcome.countsAsLlmSuccess] match the taxonomy table.
 * - [TickRecord]'s `timeoutNoOpCause` invariant, swept over every outcome/cause combination.
 * - The encodings that the legacy fallback-reason taxonomy (deleted in Issue #713) mapped onto,
 *   and the safety rule behind its `EMPTY_NO_TOOLS` split — see the "Historical" section of
 *   [TickOutcome]'s KDoc.
 *
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
@DisplayName("TickOutcome taxonomy")
class TickOutcomeTest {
	// ── tickClass mapping ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("tickClass mapping")
	inner class TickClassMapping {
		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["LLM_ACTIONS", "LLM_NO_OP", "LLM_REPAIRED"])
		fun `success outcomes map to TickClass SUCCESS`(outcome: TickOutcome) {
			assertThat(outcome.tickClass).isEqualTo(TickClass.SUCCESS)
		}

		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["LLM_SILENT_NONACTIONABLE"])
		fun `LLM_SILENT_NONACTIONABLE maps to TickClass NONACTIONABLE`(outcome: TickOutcome) {
			assertThat(outcome.tickClass).isEqualTo(TickClass.NONACTIONABLE)
		}

		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["TIMEOUT_NOOP", "LLM_EXCEPTION"])
		fun `degraded outcomes map to TickClass DEGRADED`(outcome: TickOutcome) {
			assertThat(outcome.tickClass).isEqualTo(TickClass.DEGRADED)
		}

		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["LLM_ABANDONED", "RULE_FALLBACK"])
		fun `run-failure outcomes map to TickClass RUN_FAILURE`(outcome: TickOutcome) {
			assertThat(outcome.tickClass).isEqualTo(TickClass.RUN_FAILURE)
		}

		@Test
		fun `every TickOutcome is covered and buckets split 3-1-2-2 across the four classes`() {
			// The `when` in tickClass is exhaustive, so a future TickOutcome value added
			// without updating the mapping would fail to compile. This test additionally
			// pins down the exact 3-SUCCESS / 1-NONACTIONABLE / 2-DEGRADED / 2-RUN_FAILURE
			// split from the Issue #842/#927 taxonomy table, so a wrong (but still exhaustive)
			// reassignment is still caught.
			val byClass = TickOutcome.entries.groupingBy { it.tickClass }.eachCount()

			assertThat(byClass[TickClass.SUCCESS]).isEqualTo(3)
			assertThat(byClass[TickClass.NONACTIONABLE]).isEqualTo(1)
			assertThat(byClass[TickClass.DEGRADED]).isEqualTo(2)
			assertThat(byClass[TickClass.RUN_FAILURE]).isEqualTo(2)
		}
	}

	// ── countsAsLlmSuccess mapping ────────────────────────────────────────────

	@Nested
	@DisplayName("countsAsLlmSuccess mapping")
	inner class CountsAsLlmSuccessMapping {
		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["LLM_ACTIONS", "LLM_NO_OP", "LLM_REPAIRED"])
		fun `success outcomes count as LLM success`(outcome: TickOutcome) {
			assertThat(outcome.countsAsLlmSuccess).isTrue()
		}

		@ParameterizedTest
		@EnumSource(
			TickOutcome::class,
			names = ["LLM_ACTIONS", "LLM_NO_OP", "LLM_REPAIRED"],
			mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE
		)
		fun `non-success outcomes do not count as LLM success`(outcome: TickOutcome) {
			assertThat(outcome.countsAsLlmSuccess).isFalse()
		}
	}

	// ── countsTowardActionableRate mapping (Issue #927) ───────────────────────

	@Nested
	@DisplayName("countsTowardActionableRate mapping")
	inner class CountsTowardActionableRateMapping {
		@ParameterizedTest
		@EnumSource(TickOutcome::class, names = ["LLM_SILENT_NONACTIONABLE"])
		fun `LLM_SILENT_NONACTIONABLE does not count toward the actionable-rate denominator`(outcome: TickOutcome) {
			assertThat(outcome.countsTowardActionableRate).isFalse()
		}

		@ParameterizedTest
		@EnumSource(
			TickOutcome::class,
			names = ["LLM_SILENT_NONACTIONABLE"],
			mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE
		)
		fun `every other outcome counts toward the actionable-rate denominator`(outcome: TickOutcome) {
			assertThat(outcome.countsTowardActionableRate).isTrue()
		}
	}

	// ── TickRecord invariant ──────────────────────────────────────────────────

	@Nested
	@DisplayName("TickRecord timeoutNoOpCause invariant")
	inner class TickRecordInvariant {
		@Test
		fun `TIMEOUT_NOOP without a cause throws IllegalArgumentException`() {
			try {
				TickRecord(outcome = TickOutcome.TIMEOUT_NOOP, simTime = 1.0)
				error("Expected IllegalArgumentException")
			} catch (e: IllegalArgumentException) {
				assertThat(e.message).isNotNull()
			}
		}

		@Test
		fun `non-TIMEOUT_NOOP outcome with a cause throws IllegalArgumentException`() {
			try {
				TickRecord(
					outcome = TickOutcome.LLM_NO_OP,
					simTime = 1.0,
					timeoutNoOpCause = TimeoutNoOpCause.EMPTY_UNPARSEABLE
				)
				error("Expected IllegalArgumentException")
			} catch (e: IllegalArgumentException) {
				assertThat(e.message).isNotNull()
			}
		}

		@Test
		fun `TIMEOUT_NOOP with a cause is constructed successfully`() {
			val record =
				TickRecord(
					outcome = TickOutcome.TIMEOUT_NOOP,
					simTime = 2.5,
					timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS
				)
			assertThat(record.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.DEADLINE_MISS)
		}

		@Test
		fun `non-TIMEOUT_NOOP outcome without a cause is constructed successfully`() {
			val record = TickRecord(outcome = TickOutcome.LLM_ACTIONS, simTime = 3.0)
			assertThat(record.timeoutNoOpCause).isNull()
		}

		@Test
		@DisplayName("a cause is accepted on TIMEOUT_NOOP and rejected on every other outcome")
		fun `TickRecord accepts a cause on exactly one outcome`() {
			// Replaces the projection-era guard that every projected (outcome, cause) pair was one
			// TickRecord would accept. With that bridge gone, the same drift is caught at the source:
			// sweep the whole product of outcomes and causes (plus the no-cause case) and assert that
			// TickRecord accepts precisely the TIMEOUT_NOOP-with-a-cause combinations.
			val causes: List<TimeoutNoOpCause?> = listOf(null) + TimeoutNoOpCause.entries
			TickOutcome.entries.forEach { outcome ->
				causes.forEach { cause ->
					val legal = (outcome == TickOutcome.TIMEOUT_NOOP) == (cause != null)
					val accepted =
						try {
							TickRecord(outcome = outcome, simTime = 0.0, timeoutNoOpCause = cause)
							true
						} catch (_: IllegalArgumentException) {
							false
						}
					assertThat(accepted, name = "TickRecord($outcome, $cause) accepted").isEqualTo(legal)
				}
			}
		}
	}

	// ── Legacy fallback-reason encodings (Issue #713 — bridge deleted) ────────

	/**
	 * The legacy three-value fallback-reason enum and the projection bridge that mapped it onto
	 * [TickOutcome] were deleted in Issue #713, but the encodings they projected onto are still
	 * this taxonomy's contract, and are still how every figure recorded before that migration has
	 * to be read — see the "Historical" section of [TickOutcome]'s KDoc.
	 *
	 * These tests assert those encodings directly against [TickOutcome] and [TickRecord], so the
	 * bridge's removal did not take its invariants with it. The one that matters most is the
	 * safety rule the split exists to enforce: an empty response can never be distinguished from a
	 * dead model, so it must never be scored as a success unless the silence was independently
	 * explained (Issue #834).
	 */
	@Nested
	@DisplayName("legacy fallback-reason encodings")
	inner class LegacyFallbackEncodings {
		@Test
		@DisplayName("an unexplained empty response is a degraded TIMEOUT_NOOP + EMPTY_UNPARSEABLE")
		fun `an unexplained empty response is not scored as a success`() {
			val record =
				TickRecord(
					outcome = TickOutcome.TIMEOUT_NOOP,
					simTime = 1.0,
					timeoutNoOpCause = TimeoutNoOpCause.EMPTY_UNPARSEABLE
				)

			assertThat(record.outcome.countsAsLlmSuccess).isFalse()
			assertThat(record.outcome.tickClass).isEqualTo(TickClass.DEGRADED)
			assertThat(record.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.EMPTY_UNPARSEABLE)
		}

		@Test
		@DisplayName("an independently explained empty cycle is LLM_NO_OP: a success carrying no cause")
		fun `an explained empty cycle is scored as a success`() {
			val record = TickRecord(outcome = TickOutcome.LLM_NO_OP, simTime = 1.0)

			assertThat(record.outcome.countsAsLlmSuccess).isTrue()
			assertThat(record.outcome.tickClass).isEqualTo(TickClass.SUCCESS)
			assertThat(record.timeoutNoOpCause).isNull()
		}

		@Test
		@DisplayName("the two halves of the EMPTY_NO_TOOLS split stay on opposite sides of the partition")
		fun `the halves of the empty-cycle split never agree`() {
			// The whole reason one legacy value had to become two: whether an empty cycle scores as a
			// success depends entirely on whether its silence was independently explained. If these
			// two ever agreed, the safety rule would have been quietly dropped.
			assertThat(TickOutcome.LLM_NO_OP.countsAsLlmSuccess)
				.isNotEqualTo(TickOutcome.TIMEOUT_NOOP.countsAsLlmSuccess)
		}

		@Test
		@DisplayName("a missed inference deadline is TIMEOUT_NOOP + DEADLINE_MISS")
		fun `a missed deadline is a degraded TIMEOUT_NOOP`() {
			val record =
				TickRecord(
					outcome = TickOutcome.TIMEOUT_NOOP,
					simTime = 1.0,
					timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS
				)

			assertThat(record.outcome.countsAsLlmSuccess).isFalse()
			assertThat(record.outcome.tickClass).isEqualTo(TickClass.DEGRADED)
			assertThat(record.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.DEADLINE_MISS)
		}

		@Test
		@DisplayName("a throwable on the LLM path is LLM_EXCEPTION: degraded, and carrying no cause")
		fun `an LLM-path throwable is a degraded LLM_EXCEPTION`() {
			val record = TickRecord(outcome = TickOutcome.LLM_EXCEPTION, simTime = 1.0)

			assertThat(record.outcome.countsAsLlmSuccess).isFalse()
			assertThat(record.outcome.tickClass).isEqualTo(TickClass.DEGRADED)
			assertThat(record.timeoutNoOpCause).isNull()
		}
	}
}
