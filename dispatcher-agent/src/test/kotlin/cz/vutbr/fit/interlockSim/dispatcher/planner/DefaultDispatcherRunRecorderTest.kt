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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultDispatcherRunRecorder].
 *
 * Covers the acceptance criteria from SP2c.22 (Issue #845):
 * - [finish] idempotency
 * - `ticksByOutcome.values.sum() == totalTicks` invariant
 * - In-flight tick not counted
 * - [onActionOutcome] attribution routing
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DefaultDispatcherRunRecorderTest {
	private val defaultParams =
		RunParameters(
			tickPeriodMs = 500L,
			historyN = 10,
			temperature = 0.0,
			maxActionsPerTick = 3,
			model = "",
			seed = null
		)

	private fun recorder(): DefaultDispatcherRunRecorder =
		DefaultDispatcherRunRecorder(
			runId = "test-run-001",
			arm = DispatcherArm.RULE_BASED,
			params = defaultParams
		)

	@Test
	fun `runId is preserved`() {
		val r = recorder()
		assertThat(r.runId).isEqualTo("test-run-001")
	}

	@Test
	fun `totalTicks is zero before any onTick call`() {
		val snap = recorder().snapshot()
		assertThat(snap.totalTicks).isEqualTo(0L)
	}

	@Test
	fun `ticksByOutcome values sum equals totalTicks after recording ticks`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 2.0))
		r.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, simTime = 3.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))

		val snap = r.snapshot()
		assertThat(snap.ticksByOutcome.values.sum()).isEqualTo(snap.totalTicks)
		assertThat(snap.totalTicks).isEqualTo(3L)
	}

	@Test
	fun `ticksByOutcome invariant holds for zero ticks`() {
		val snap = recorder().snapshot()
		assertThat(snap.ticksByOutcome.values.sum()).isEqualTo(0L)
		assertThat(snap.totalTicks).isEqualTo(0L)
	}

	@Test
	fun `finish is idempotent - second call returns same frozen snapshot`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))

		val first = r.finish(RunEndCause.MANUAL_STOP)
		// Record more ticks after finish — should not be included
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 2.0))

		val second = r.finish(RunEndCause.NATURAL_COMPLETION)

		// Both calls must return the same frozen snapshot
		assertThat(first).isEqualTo(second)
		// The second tick recorded after finish must not appear
		assertThat(first.totalTicks).isEqualTo(1L)
		// End cause from the first call wins
		assertThat(first.endCause).isEqualTo(RunEndCause.MANUAL_STOP)
	}

	@Test
	fun `finish sets endCause correctly`() {
		val r = recorder()
		val snap = r.finish(RunEndCause.NATURAL_COMPLETION)
		assertThat(snap.endCause).isEqualTo(RunEndCause.NATURAL_COMPLETION)
		assertThat(snap.completedNaturally).isTrue()
	}

	@Test
	fun `snapshot returns null endCause while run is in progress`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))
		val snap = r.snapshot()
		assertThat(snap.endCause).isNull()
	}

	@Test
	fun `in-flight tick at stop is not counted`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))
		// Freeze the snapshot — this represents stopping mid-inference
		val snap = r.finish(RunEndCause.MANUAL_STOP)
		// The "in-flight" tick we didn't call onTick for must not appear
		assertThat(snap.totalTicks).isEqualTo(1L)
	}

	@Test
	fun `onActionOutcome counts rejection correctly`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.REJECTED_BY_VALIDATOR, rejection = RejectionCode.UNKNOWN_TRAIN))
		r.onActionOutcome(makeOutcome(phase = ActionPhase.REJECTED_BY_VALIDATOR, rejection = RejectionCode.UNKNOWN_TRAIN))

		val snap = r.snapshot()
		assertThat(snap.rejectionsByCode[RejectionCode.UNKNOWN_TRAIN.name] ?: 0L).isEqualTo(2L)
	}

	@Test
	fun `onActionOutcome counts apply failure correctly`() {
		val r = recorder()
		r.onActionOutcome(
			makeOutcome(phase = ActionPhase.APPLIED_THEN_FAILED, applyFailure = ApplyFailureCode.ALL_PATHS_BLOCKED)
		)

		val snap = r.snapshot()
		assertThat(snap.applyFailuresByCode[ApplyFailureCode.ALL_PATHS_BLOCKED.name] ?: 0L).isEqualTo(1L)
	}

	@Test
	fun `onActionOutcome tracks unattributed applies when tickIndex is minus one`() {
		val r = recorder()
		r.onActionOutcome(
			makeOutcome(
				phase = ActionPhase.APPLIED,
				author = ActionAuthor.LLM,
				tickIndex = -1L
			)
		)
		val snap = r.snapshot()
		assertThat(snap.unattributedApplies).isEqualTo(1L)
	}

	@Test
	fun `c7Clean is true when no RULE_FALLBACK or SAFETY_NET actions were observed`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.LLM))
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.RULE_BASED))

		val snap = r.snapshot()
		assertThat(snap.c7Clean).isTrue()
	}

	@Test
	fun `c7Clean is false when a RULE_FALLBACK action was observed`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.RULE_FALLBACK))

		val snap = r.snapshot()
		assertThat(snap.c7Clean).isFalse()
	}

	@Test
	fun `c7Clean is false when a SAFETY_NET action was observed`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.SAFETY_NET))

		val snap = r.snapshot()
		assertThat(snap.c7Clean).isFalse()
	}

	@Test
	fun `c7Clean is false when both RULE_FALLBACK and SAFETY_NET actions were observed`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.RULE_FALLBACK))
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.SAFETY_NET))

		val snap = r.snapshot()
		assertThat(snap.c7Clean).isFalse()
	}

	@Test
	fun `c7Clean stays false in the frozen finish snapshot once a violation is observed`() {
		val r = recorder()
		r.onActionOutcome(makeOutcome(phase = ActionPhase.APPLIED, author = ActionAuthor.SAFETY_NET))

		val snap = r.finish(RunEndCause.MANUAL_STOP)
		assertThat(snap.c7Clean).isFalse()
	}

	@Test
	fun `snapshot arm and params match constructor arguments`() {
		val r =
			DefaultDispatcherRunRecorder(
				runId = "x",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params = defaultParams.copy(model = "qwen2.5:7b-instruct")
			)
		val snap = r.snapshot()
		assertThat(snap.arm).isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(snap.params.model).isEqualTo("qwen2.5:7b-instruct")
	}

	@Test
	fun `llmSuccessRate is zero when totalTicks is zero`() {
		val snap = recorder().snapshot()
		assertThat(snap.llmSuccessRate).isEqualTo(0.0)
	}

	@Test
	fun `llmSuccessRate reflects LLM_ACTIONS and LLM_NO_OP ticks`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0)) // success
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 2.0)) // success
		// not a success
		r.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, simTime = 3.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))

		val snap = r.snapshot()
		// 2 successes out of 3 ticks
		assertThat(snap.llmSuccessRate).isEqualTo(2.0 / 3.0)
	}

	@Test
	fun `timeoutNoOpByCause DEADLINE_MISS is counted`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, simTime = 1.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))

		val snap = r.snapshot()
		assertThat(snap.timeoutNoOpByCause[TimeoutNoOpCause.DEADLINE_MISS.name] ?: 0L).isEqualTo(1L)
	}

	@Test
	fun `snapshot has correct schemaVersion`() {
		val snap = recorder().snapshot()
		assertThat(snap.schemaVersion).isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
	}

	@Test
	fun `logFinalSummary does not throw`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 1.0))
		r.finish(RunEndCause.MANUAL_STOP)
		// Must not throw
		r.logFinalSummary()
	}

	// ── Latency percentiles (Issue #834, SP2c.11) ─────────────────────────────

	@Test
	fun `latency fields are zero when no ticks carried a latency`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))

		val snap = r.snapshot()
		assertThat(snap.latencyP50Ms).isEqualTo(0L)
		assertThat(snap.latencyP95Ms).isEqualTo(0L)
		assertThat(snap.latencyMaxMs).isEqualTo(0L)
	}

	@Test
	fun `latency fields are zero when the recorder has seen no ticks at all`() {
		val snap = recorder().snapshot()
		assertThat(snap.latencyP50Ms).isEqualTo(0L)
		assertThat(snap.latencyP95Ms).isEqualTo(0L)
		assertThat(snap.latencyMaxMs).isEqualTo(0L)
	}

	@Test
	fun `a single latency sample is reported for p50, p95 and max`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0, latencyMs = 250L))

		val snap = r.snapshot()
		assertThat(snap.latencyP50Ms).isEqualTo(250L)
		assertThat(snap.latencyP95Ms).isEqualTo(250L)
		assertThat(snap.latencyMaxMs).isEqualTo(250L)
	}

	@Test
	fun `an even-sized latency sample set computes p50 by nearest-rank`() {
		val r = recorder()
		listOf(400L, 100L, 300L, 200L).forEach { latency ->
			r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0, latencyMs = latency))
		}

		val snap = r.snapshot()
		// sorted: 100, 200, 300, 400 -> nearest-rank(50) = ceil(0.5 * 4) = rank 2 -> 200
		assertThat(snap.latencyP50Ms).isEqualTo(200L)
		assertThat(snap.latencyMaxMs).isEqualTo(400L)
	}

	@Test
	fun `an odd-sized latency sample set computes p50 as the middle element`() {
		val r = recorder()
		listOf(500L, 100L, 300L).forEach { latency ->
			r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0, latencyMs = latency))
		}

		val snap = r.snapshot()
		// sorted: 100, 300, 500 -> middle is 300
		assertThat(snap.latencyP50Ms).isEqualTo(300L)
		assertThat(snap.latencyMaxMs).isEqualTo(500L)
	}

	@Test
	fun `p95 is not confused with max on a larger latency sample set`() {
		val r = recorder()
		(1..20).map { it * 10L }.shuffled().forEach { latency ->
			r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0, latencyMs = latency))
		}

		val snap = r.snapshot()
		// sorted: 10, 20, ..., 200 -> nearest-rank(95) = ceil(0.95 * 20) = 19th element = 190
		assertThat(snap.latencyP95Ms).isEqualTo(190L)
		assertThat(snap.latencyMaxMs).isEqualTo(200L)
	}

	@Test
	fun `ticks with a null latency do not contribute a sample`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 1.0, latencyMs = null))
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 2.0, latencyMs = 100L))

		val snap = r.snapshot()
		assertThat(snap.latencyP50Ms).isEqualTo(100L)
		assertThat(snap.latencyMaxMs).isEqualTo(100L)
	}

	@Test
	fun `latency samples recorded after finish do not affect the frozen snapshot`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0, latencyMs = 100L))
		val frozen = r.finish(RunEndCause.MANUAL_STOP)
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 2.0, latencyMs = 9000L))

		assertThat(frozen.latencyMaxMs).isEqualTo(100L)
	}

	// ── Railway outcomes (Issue #834, SP2c.11) ───────────────────────────────

	@Test
	fun `railway outcome figures are absent - not zero - when none were recorded`() {
		val outcome = recorder().snapshot().railwayOutcome

		// Every figure must be null. A `0` here would read as "the railway moved nothing",
		// which is a measurement; the truth is that nothing measured it.
		assertThat(outcome.journeysCompleted).isNull()
		assertThat(outcome.trainsEntered).isNull()
		assertThat(outcome.trainsExited).isNull()
		assertThat(outcome.maxConcurrentTrains).isNull()
		assertThat(outcome.blockTransitions).isNull()
		assertThat(outcome.conflicts).isNull()
		assertThat(outcome.failedReservations).isNull()
	}

	@Test
	fun `recordRailwayOutcome figures appear in the snapshot`() {
		val r = recorder()
		r.recordRailwayOutcome(
			RailwayOutcome(
				journeysCompleted = 7L,
				trainsEntered = 13L,
				trainsExited = 11L,
				maxConcurrentTrains = 2L,
				blockTransitions = 173L,
				conflicts = 4L,
				failedReservations = 9L
			)
		)

		val outcome = r.snapshot().railwayOutcome
		assertThat(outcome.journeysCompleted).isEqualTo(7L)
		assertThat(outcome.trainsEntered).isEqualTo(13L)
		assertThat(outcome.trainsExited).isEqualTo(11L)
		assertThat(outcome.maxConcurrentTrains).isEqualTo(2L)
		assertThat(outcome.blockTransitions).isEqualTo(173L)
		assertThat(outcome.conflicts).isEqualTo(4L)
		assertThat(outcome.failedReservations).isEqualTo(9L)
	}

	/**
	 * A measured zero and an unmeasured figure must not collapse into the same value: a run in
	 * which the dispatcher admitted no train at all is a finding, and a run nobody measured is not.
	 */
	@Test
	fun `a measured zero is reported as zero and stays distinguishable from absent`() {
		val r = recorder()
		r.recordRailwayOutcome(RailwayOutcome(trainsEntered = 0L))

		val outcome = r.snapshot().railwayOutcome
		assertThat(outcome.trainsEntered).isEqualTo(0L)
		// Nothing was recorded for the remaining figures, so they stay absent.
		assertThat(outcome.trainsExited).isNull()
	}

	@Test
	fun `railway outcome recorded after finish does not affect the frozen snapshot`() {
		val r = recorder()
		r.recordRailwayOutcome(RailwayOutcome(trainsEntered = 1L))
		val frozen = r.finish(RunEndCause.MANUAL_STOP)
		r.recordRailwayOutcome(RailwayOutcome(trainsEntered = 99L))

		assertThat(frozen.railwayOutcome.trainsEntered).isEqualTo(1L)
	}

	@Test
	fun `logFinalSummary does not throw when the railway outcome is absent`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 1.0))
		r.finish(RunEndCause.MANUAL_STOP)
		// Must not throw even though every railway figure is null.
		r.logFinalSummary()
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private fun makeOutcome(
		phase: ActionPhase,
		rejection: RejectionCode? = null,
		applyFailure: ApplyFailureCode? = null,
		author: ActionAuthor = ActionAuthor.LLM,
		tickIndex: Long = 0L
	): ActionOutcome =
		ActionOutcome(
			phase = phase,
			rejection = rejection,
			applyFailure = applyFailure,
			authored =
				AuthoredAction(
					author = author,
					reason = "test",
					decisionKind = "RequestRoute",
					tickIndex = tickIndex
				)
		)
}
