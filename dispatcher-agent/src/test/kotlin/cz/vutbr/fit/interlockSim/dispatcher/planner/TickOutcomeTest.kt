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
 * - [TickRecord]'s `timeoutNoOpCause` invariant.
 * - [FallbackReason.toTickOutcome] correctly projects all three legacy [FallbackReason] values
 *   onto [TickOutcome], including the [FallbackReason.EMPTY_NO_TOOLS] split.
 * - [FallbackReason] and [PlannerCycleListener] are marked `@Deprecated`.
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
		fun `every TickOutcome is covered and buckets split 3-2-2 across the three classes`() {
			// The `when` in tickClass is exhaustive, so a future TickOutcome value added
			// without updating the mapping would fail to compile. This test additionally
			// pins down the exact 3-SUCCESS / 2-DEGRADED / 2-RUN_FAILURE split from the
			// Issue #842 taxonomy table, so a wrong (but still exhaustive) reassignment is
			// still caught.
			val byClass = TickOutcome.entries.groupingBy { it.tickClass }.eachCount()

			assertThat(byClass[TickClass.SUCCESS]).isEqualTo(3)
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
			names = ["TIMEOUT_NOOP", "LLM_EXCEPTION", "LLM_ABANDONED", "RULE_FALLBACK"]
		)
		fun `non-success outcomes do not count as LLM success`(outcome: TickOutcome) {
			assertThat(outcome.countsAsLlmSuccess).isFalse()
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
	}

	// ── FallbackReason to TickOutcome projection ──────────────────────────────

	@Nested
	@DisplayName("FallbackReason.toTickOutcome projection")
	inner class FallbackReasonProjection {
		@Test
		fun `EMPTY_NO_TOOLS without explicitNoOp projects to TIMEOUT_NOOP EMPTY_UNPARSEABLE`() {
			val projection = FallbackReason.EMPTY_NO_TOOLS.toTickOutcome()

			assertThat(projection.outcome).isEqualTo(TickOutcome.TIMEOUT_NOOP)
			assertThat(projection.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.EMPTY_UNPARSEABLE)
		}

		@Test
		fun `EMPTY_NO_TOOLS with explicitNoOp false projects to TIMEOUT_NOOP EMPTY_UNPARSEABLE`() {
			val projection = FallbackReason.EMPTY_NO_TOOLS.toTickOutcome(explicitNoOp = false)

			assertThat(projection.outcome).isEqualTo(TickOutcome.TIMEOUT_NOOP)
			assertThat(projection.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.EMPTY_UNPARSEABLE)
		}

		@Test
		fun `EMPTY_NO_TOOLS with explicitNoOp true projects to LLM_NO_OP (a success)`() {
			val projection = FallbackReason.EMPTY_NO_TOOLS.toTickOutcome(explicitNoOp = true)

			assertThat(projection.outcome).isEqualTo(TickOutcome.LLM_NO_OP)
			assertThat(projection.timeoutNoOpCause).isNull()
			assertThat(projection.outcome.countsAsLlmSuccess).isTrue()
		}

		@Test
		fun `TIMEOUT projects to TIMEOUT_NOOP DEADLINE_MISS`() {
			val projection = FallbackReason.TIMEOUT.toTickOutcome()

			assertThat(projection.outcome).isEqualTo(TickOutcome.TIMEOUT_NOOP)
			assertThat(projection.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.DEADLINE_MISS)
		}

		@Test
		fun `TIMEOUT ignores explicitNoOp and still projects to TIMEOUT_NOOP DEADLINE_MISS`() {
			val projection = FallbackReason.TIMEOUT.toTickOutcome(explicitNoOp = true)

			assertThat(projection.outcome).isEqualTo(TickOutcome.TIMEOUT_NOOP)
			assertThat(projection.timeoutNoOpCause).isEqualTo(TimeoutNoOpCause.DEADLINE_MISS)
		}

		@Test
		fun `EXCEPTION projects to LLM_EXCEPTION`() {
			val projection = FallbackReason.EXCEPTION.toTickOutcome()

			assertThat(projection.outcome).isEqualTo(TickOutcome.LLM_EXCEPTION)
			assertThat(projection.timeoutNoOpCause).isNull()
		}

		@Test
		fun `EXCEPTION ignores explicitNoOp and still projects to LLM_EXCEPTION`() {
			val projection = FallbackReason.EXCEPTION.toTickOutcome(explicitNoOp = true)

			assertThat(projection.outcome).isEqualTo(TickOutcome.LLM_EXCEPTION)
			assertThat(projection.timeoutNoOpCause).isNull()
		}

		@Test
		fun `every projected TickOutcome-cause pair satisfies the TickRecord invariant`() {
			// Every FallbackReason, under both explicitNoOp settings, must project to a pair
			// that TickRecord actually accepts — regression guard against the projection and
			// TickRecord's invariant drifting apart.
			FallbackReason.entries.forEach { reason ->
				listOf(false, true).forEach { explicitNoOp ->
					val projection = reason.toTickOutcome(explicitNoOp)
					TickRecord(
						outcome = projection.outcome,
						simTime = 0.0,
						timeoutNoOpCause = projection.timeoutNoOpCause
					)
				}
			}
		}
	}

	// ── Legacy deprecation ────────────────────────────────────────────────────

	@Nested
	@DisplayName("legacy types are marked @Deprecated")
	inner class LegacyDeprecation {
		@Test
		fun `FallbackReason is annotated Deprecated`() {
			assertThat(FallbackReason::class.annotations.any { it is Deprecated }).isTrue()
		}

		@Test
		fun `PlannerCycleListener is annotated Deprecated`() {
			assertThat(PlannerCycleListener::class.annotations.any { it is Deprecated }).isTrue()
		}
	}
}
