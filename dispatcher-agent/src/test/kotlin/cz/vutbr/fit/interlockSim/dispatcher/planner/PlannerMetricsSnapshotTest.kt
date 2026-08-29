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
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlannerMetricsSnapshot].
 *
 * Covers the `init` invariant that ties [PlannerMetricsSnapshot.ollamaSuccessCount] to the
 * [TickOutcome.countsAsLlmSuccess] partition of [PlannerMetricsSnapshot.outcomeCounts], and the
 * three derived figures the snapshot computes from the breakdown.
 */
@DisplayName("PlannerMetricsSnapshot")
class PlannerMetricsSnapshotTest {
	@Test
	fun `derived figures follow from the stored breakdown`() {
		val snapshot =
			PlannerMetricsSnapshot(
				ollamaSuccessCount = 3L,
				outcomeCounts =
					mapOf(
						TickOutcome.LLM_ACTIONS to 2L,
						TickOutcome.LLM_NO_OP to 1L,
						TickOutcome.RULE_FALLBACK to 4L
					)
			)

		assertThat(snapshot.totalCycles).isEqualTo(7L)
		assertThat(snapshot.fallbackCount).isEqualTo(4L)
		assertThat(snapshot.ollamaSuccessRate).isEqualTo(3.0 / 7.0)
	}

	@Test
	fun `empty snapshot reports zero figures`() {
		val snapshot = PlannerMetricsSnapshot(ollamaSuccessCount = 0L, outcomeCounts = emptyMap())

		assertThat(snapshot.totalCycles).isEqualTo(0L)
		assertThat(snapshot.fallbackCount).isEqualTo(0L)
		assertThat(snapshot.ollamaSuccessRate).isEqualTo(0.0)
	}

	@Test
	fun `constructor rejects ollamaSuccessCount that disagrees with the partition`() {
		assertFailure {
			PlannerMetricsSnapshot(
				ollamaSuccessCount = 3L,
				outcomeCounts =
					mapOf(
						TickOutcome.LLM_ACTIONS to 2L,
						TickOutcome.RULE_FALLBACK to 1L
					)
			)
		}.isInstanceOf(IllegalArgumentException::class)
			.hasMessage(
				"ollamaSuccessCount=3 must equal the outcomeCounts sum over " +
					"LLM-success outcomes (2); see the success/fallback partition on " +
					"PlannerMetricsSnapshot"
			)
	}
}
