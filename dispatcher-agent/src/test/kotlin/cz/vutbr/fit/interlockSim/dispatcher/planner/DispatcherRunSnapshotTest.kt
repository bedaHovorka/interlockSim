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
import assertk.assertions.containsOnly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DispatcherRunSnapshot], covering the typed convenience view extension
 * functions and the `ticksByOutcome.values.sum() == totalTicks` constructor invariant.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DispatcherRunSnapshotTest {
	private fun snapshotWith(
		ticksByOutcome: Map<String, Long> = mapOf(TickOutcome.LLM_ACTIONS.name to 1L),
		totalTicks: Long = 1L,
		timeoutNoOpByCause: Map<String, Long> = mapOf(TimeoutNoOpCause.DEADLINE_MISS.name to 0L),
		rejectionsByCode: Map<String, Long> = mapOf(RejectionCode.UNKNOWN_TRAIN.name to 2L),
		applyFailuresByCode: Map<String, Long> = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED.name to 3L),
		actionsByAuthor: Map<String, Long> = mapOf(ActionAuthor.LLM.name to 4L)
	): DispatcherRunSnapshot =
		DispatcherRunSnapshot(
			runId = "typed-view-001",
			arm = DispatcherArm.RULE_BASED,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = "",
					seed = null
				),
			totalTicks = totalTicks,
			ticksByOutcome = ticksByOutcome,
			timeoutNoOpByCause = timeoutNoOpByCause,
			llmSuccessRate = 1.0,
			noOpRate = 0.0,
			invalidOutputRate = 0.0,
			repairSuccessRate = 0.0,
			emittedByActionType = emptyMap(),
			rejectionsByCode = rejectionsByCode,
			applyFailuresByCode = applyFailuresByCode,
			validAt1 = 0.0,
			correctAt1 = null,
			oracleAgreementAt1 = null,
			latencyP50Ms = 0L,
			latencyP95Ms = 0L,
			latencyMaxMs = 0L,
			actionsByAuthor = actionsByAuthor,
			unattributedApplies = 0L,
			terminalFallbackEngaged = false,
			terminalFallbackTickIndex = null,
			c7Clean = true,
			completedNaturally = true,
			endCause = RunEndCause.NATURAL_COMPLETION
		)

	@Test
	fun `ticksByOutcomeTyped restores TickOutcome enum keys`() {
		val snap = snapshotWith(ticksByOutcome = mapOf(TickOutcome.LLM_ACTIONS.name to 1L))
		val typed = snap.ticksByOutcomeTyped()
		assertThat(typed.keys).containsOnly(TickOutcome.LLM_ACTIONS)
		assertThat(typed[TickOutcome.LLM_ACTIONS]).isEqualTo(1L)
	}

	@Test
	fun `timeoutNoOpByCauseTyped restores TimeoutNoOpCause enum keys`() {
		val snap = snapshotWith(timeoutNoOpByCause = mapOf(TimeoutNoOpCause.DEADLINE_MISS.name to 5L))
		val typed = snap.timeoutNoOpByCauseTyped()
		assertThat(typed.keys).containsOnly(TimeoutNoOpCause.DEADLINE_MISS)
		assertThat(typed[TimeoutNoOpCause.DEADLINE_MISS]).isEqualTo(5L)
	}

	@Test
	fun `rejectionsByCodeTyped restores RejectionCode enum keys`() {
		val snap = snapshotWith(rejectionsByCode = mapOf(RejectionCode.UNKNOWN_TRAIN.name to 2L))
		val typed = snap.rejectionsByCodeTyped()
		assertThat(typed.keys).containsOnly(RejectionCode.UNKNOWN_TRAIN)
		assertThat(typed[RejectionCode.UNKNOWN_TRAIN]).isEqualTo(2L)
	}

	@Test
	fun `applyFailuresByCodeTyped restores ApplyFailureCode enum keys`() {
		val snap = snapshotWith(applyFailuresByCode = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED.name to 3L))
		val typed = snap.applyFailuresByCodeTyped()
		assertThat(typed.keys).containsOnly(ApplyFailureCode.ALL_PATHS_BLOCKED)
		assertThat(typed[ApplyFailureCode.ALL_PATHS_BLOCKED]).isEqualTo(3L)
	}

	@Test
	fun `actionsByAuthorTyped restores ActionAuthor enum keys`() {
		val snap = snapshotWith(actionsByAuthor = mapOf(ActionAuthor.LLM.name to 4L))
		val typed = snap.actionsByAuthorTyped()
		assertThat(typed.keys).containsOnly(ActionAuthor.LLM)
		assertThat(typed[ActionAuthor.LLM]).isEqualTo(4L)
	}

	@Test
	fun `constructor rejects ticksByOutcome that does not sum to totalTicks`() {
		assertFailure {
			snapshotWith(ticksByOutcome = mapOf(TickOutcome.LLM_ACTIONS.name to 2L), totalTicks = 1L)
		}.isInstanceOf(IllegalArgumentException::class)
			.hasMessage("ticksByOutcome.values.sum()=2 must equal totalTicks=1")
	}
}
