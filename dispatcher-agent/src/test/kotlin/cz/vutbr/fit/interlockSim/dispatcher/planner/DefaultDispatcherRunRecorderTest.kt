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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import ch.qos.logback.classic.Logger as LogbackLogger

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
	fun `finish captures the circuit breaker state and counters`() {
		val breaker = LlmCircuitBreaker(failureThreshold = 1, cooldownSeconds = 60.0)
		breaker.recordFailure(simTime = 1.0)
		breaker.shouldAttempt(simTime = 2.0)
		val recorder =
			DefaultDispatcherRunRecorder(
				runId = "breaker-run",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params = defaultParams,
				circuitBreaker = breaker
			)

		val snap = recorder.finish(RunEndCause.MANUAL_STOP)

		assertThat(snap.circuitBreakerState).isEqualTo(LlmCircuitBreaker.State.OPEN)
		assertThat(snap.circuitBreakerTotalSkips).isEqualTo(1L)
		assertThat(snap.circuitBreakerOpenCount).isEqualTo(1L)
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

	// ── actionableTickRate (Issue #927) ────────────────────────────────────────

	@Test
	fun `actionableTickRate is zero when totalTicks is zero`() {
		val snap = recorder().snapshot()
		assertThat(snap.actionableTickRate).isEqualTo(0.0)
	}

	@Test
	fun `actionableTickRate excludes LLM_SILENT_NONACTIONABLE ticks from both numerator and denominator`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0)) // actionable success
		r.onTick(TickRecord(TickOutcome.LLM_NO_OP, simTime = 2.0)) // actionable success
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 3.0)) // actionable, not a success
		r.onTick(TickRecord(TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 4.0)) // excluded entirely
		r.onTick(TickRecord(TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 5.0)) // excluded entirely

		val snap = r.snapshot()
		// totalTicks = 5, but the actionable-rate denominator excludes the two
		// LLM_SILENT_NONACTIONABLE ticks: 2 successes / 3 actionable ticks.
		assertThat(snap.totalTicks).isEqualTo(5L)
		assertThat(snap.actionableTickRate).isEqualTo(2.0 / 3.0)
	}

	@Test
	fun `llmSuccessRate keeps its own contract unchanged — every tick still counts in its denominator`() {
		// #927 does not change llmSuccessRate itself, only introduces actionableTickRate
		// alongside it — this pins that llmSuccessRate's denominator still includes
		// LLM_SILENT_NONACTIONABLE ticks (they are not an LLM success, so they dilute the rate,
		// same as a RULE_FALLBACK tick would).
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0)) // success
		r.onTick(TickRecord(TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 2.0)) // not a success

		val snap = r.snapshot()
		assertThat(snap.totalTicks).isEqualTo(2L)
		assertThat(snap.llmSuccessRate).isEqualTo(1.0 / 2.0)
		// actionableTickRate excludes the LLM_SILENT_NONACTIONABLE tick from the denominator too,
		// so it reads 1.0 (1 success / 1 actionable tick) — structurally higher than
		// llmSuccessRate for the same data, which is the whole point of #927.
		assertThat(snap.actionableTickRate).isEqualTo(1.0)
	}

	@Test
	fun `actionableTickRate is zero when every tick is LLM_SILENT_NONACTIONABLE`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 1.0))
		r.onTick(TickRecord(TickOutcome.LLM_SILENT_NONACTIONABLE, simTime = 2.0))

		val snap = r.snapshot()
		assertThat(snap.totalTicks).isEqualTo(2L)
		// Actionable-rate denominator is 0 -> reported as 0.0, the same "absent denominator"
		// convention as llmSuccessRate/noOpRate use for a zero-tick run.
		assertThat(snap.actionableTickRate).isEqualTo(0.0)
	}

	@Test
	fun `timeoutNoOpByCause DEADLINE_MISS is counted`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, simTime = 1.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))

		val snap = r.snapshot()
		assertThat(snap.timeoutNoOpByCause[TimeoutNoOpCause.DEADLINE_MISS.name] ?: 0L).isEqualTo(1L)
	}

	/** Issue #1342: a cycle cut by num_predict is counted under its own cause. */
	@Test
	fun `timeoutNoOpByCause THINKING_TRUNCATED is counted and zero by default`() {
		val r = recorder()
		assertThat(r.snapshot().timeoutNoOpByCause[TimeoutNoOpCause.THINKING_TRUNCATED.name]).isEqualTo(0L)

		r.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, simTime = 1.0, timeoutNoOpCause = TimeoutNoOpCause.THINKING_TRUNCATED))

		assertThat(r.snapshot().timeoutNoOpByCause[TimeoutNoOpCause.THINKING_TRUNCATED.name]).isEqualTo(1L)
	}

	/** Issue #1342: the model profile captured at run start reaches the snapshot unchanged. */
	@Test
	fun `the model profile reaches the snapshot and the frozen finish copy`() {
		val profile = ModelProfileRecord(tag = "qwen2.5:7b-capped", modelfileSha256 = null, numPredict = null)
		val r =
			DefaultDispatcherRunRecorder(
				runId = "profile-run",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params = defaultParams,
				modelProfile = profile
			)

		assertThat(r.snapshot().modelProfile).isEqualTo(profile)
		assertThat(r.finish(RunEndCause.NATURAL_COMPLETION).modelProfile).isEqualTo(profile)
		assertThat(recorder().snapshot().modelProfile, "a rule-based run has no model profile").isNull()
	}

	/**
	 * Issue #1342 (owner ruling of 2026-11-27): the generation-cut counter counts every cut cycle,
	 * whatever the tick outcome. The no-emission cut ending is already counted under its
	 * THINKING_TRUNCATED cause; the counter is the trace for the mixed cycles post-emission-seal —
	 * a command from an earlier complete reply classified the tick, the cut still shows.
	 */
	@Test
	fun `generationCutCycles counts cut cycles whatever the tick outcome records`() {
		val r = llmRecorder()
		// A no-emission cut ending: TIMEOUT_NOOP / THINKING_TRUNCATED.
		r.onTick(
			TickRecord(
				TickOutcome.TIMEOUT_NOOP,
				simTime = 1.0,
				timeoutNoOpCause = TimeoutNoOpCause.THINKING_TRUNCATED,
				generationCut = true
			)
		)
		// A mixed cycle: an earlier complete reply acted, the tick is classified by the emissions,
		// and the cut reply is named by the flag alone.
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 2.0, generationCut = true))
		// An uncut tick counts nothing.
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 3.0))

		assertThat(r.snapshot().generationCutCycles).isEqualTo(2L)
		assertThat(r.snapshot().timeoutNoOpByCause[TimeoutNoOpCause.THINKING_TRUNCATED.name]).isEqualTo(1L)
	}

	@Test
	fun `a run without cut cycles records the counter as zero, not absent`() {
		val r = llmRecorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))
		r.onTick(offerTick(2.0, offered = 1, skipped = emptySet()))
		val ruleBased = recorder().apply { onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 5.0)) }

		assertThat(r.snapshot().generationCutCycles).isEqualTo(0L)
		assertThat(ruleBased.snapshot().generationCutCycles, "a rule-based arm makes no LLM calls")
			.isEqualTo(0L)
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
	fun `latency fields are null when no ticks carried a latency — absent is not zero`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 1.0))

		val snap = r.snapshot()
		// Review finding #3 (Issue #834): nothing was measured, so the fields are null (absent),
		// never 0L (which would misread as "the model answered in 0 ms").
		assertThat(snap.latencyP50Ms).isNull()
		assertThat(snap.latencyP95Ms).isNull()
		assertThat(snap.latencyMaxMs).isNull()
	}

	@Test
	fun `latency fields are null when the recorder has seen no ticks at all`() {
		val snap = recorder().snapshot()
		assertThat(snap.latencyP50Ms).isNull()
		assertThat(snap.latencyP95Ms).isNull()
		assertThat(snap.latencyMaxMs).isNull()
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
		assertThat(outcome.unresolvedAtRunEnd).isNull()
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
				failedReservations = 9L,
				unresolvedAtRunEnd = 2L
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
		assertThat(outcome.unresolvedAtRunEnd).isEqualTo(2L)
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

	// ── Skipped NEXT SECTION offers (Issue #1302) ────────────────────────────

	private fun llmRecorder(): DefaultDispatcherRunRecorder =
		DefaultDispatcherRunRecorder(
			runId = "test-run-llm",
			arm = DispatcherArm.LLM_TOOL_CALLING,
			params = defaultParams
		)

	/** A tick that skipped the fallback and left [skipped] trains' offers unrequested. */
	private fun offerTick(
		simTime: Double,
		offered: Int,
		skipped: Set<String>,
		standing: Int = 0,
		outcome: TickOutcome = TickOutcome.LLM_NO_OP
	): TickRecord =
		TickRecord(
			outcome,
			simTime = simTime,
			nextSectionOffers =
				NextSectionOfferCount(offered = offered, skippedTrainIds = skipped, skippedStandingAtSignal = standing)
		)

	@Test
	fun `skipped and offered NEXT SECTION counts are summed over the ticks that carry one`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 2, skipped = setOf("T1"), standing = 1))
		r.onTick(offerTick(2.0, offered = 3, skipped = emptySet(), outcome = TickOutcome.LLM_ACTIONS))
		// A fallback cycle carries no count and contributes nothing.
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 3.0))

		val snap = r.snapshot()
		assertThat(snap.skippedNextSectionOffers).isEqualTo(1L)
		assertThat(snap.offeredNextSectionHops).isEqualTo(5L)
		assertThat(snap.skippedNextSectionOffersStandingAtSignal).isEqualTo(1L)
	}

	@Test
	fun `standing-at-signal skips are summed only from the standing count`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 2, skipped = setOf("T1", "T2"), standing = 1))
		r.onTick(offerTick(2.0, offered = 1, skipped = setOf("T1"), standing = 0))

		val snap = r.snapshot()
		assertThat(snap.skippedNextSectionOffers).isEqualTo(3L)
		assertThat(snap.skippedNextSectionOffersStandingAtSignal).isEqualTo(1L)
	}

	@Test
	fun `two trains skipped once each make a streak of one cycle`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 2, skipped = setOf("T1")))
		r.onTick(offerTick(2.0, offered = 2, skipped = setOf("T2")))

		val snap = r.snapshot()
		assertThat(snap.skippedNextSectionOffers).isEqualTo(2L)
		assertThat(snap.longestSkippedOfferStreakCycles).isEqualTo(1L)
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isEqualTo(0.0)
	}

	@Test
	fun `one train skipped three cycles in a row makes a streak of three`() {
		val r = llmRecorder()
		r.onTick(offerTick(10.0, offered = 1, skipped = setOf("T1")))
		r.onTick(offerTick(12.5, offered = 1, skipped = setOf("T1")))
		r.onTick(offerTick(15.0, offered = 1, skipped = setOf("T1")))

		val snap = r.snapshot()
		assertThat(snap.longestSkippedOfferStreakCycles).isEqualTo(3L)
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isEqualTo(5.0)
	}

	@Test
	fun `a fallback tick resets the streak`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 1, skipped = setOf("T1")))
		r.onTick(offerTick(2.0, offered = 1, skipped = setOf("T1")))
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 3.0))
		r.onTick(offerTick(4.0, offered = 1, skipped = setOf("T1")))

		val snap = r.snapshot()
		assertThat(snap.longestSkippedOfferStreakCycles).isEqualTo(2L)
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isEqualTo(1.0)
	}

	@Test
	fun `a tick where the train is not skipped resets its streak`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 1, skipped = setOf("T1")))
		r.onTick(offerTick(2.0, offered = 1, skipped = emptySet(), outcome = TickOutcome.LLM_ACTIONS))
		r.onTick(offerTick(3.0, offered = 1, skipped = setOf("T1")))

		assertThat(r.snapshot().longestSkippedOfferStreakCycles).isEqualTo(1L)
	}

	@Test
	fun `skipped and offered NEXT SECTION counts stay absent on a rule-based run`() {
		val r = recorder()
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 1.0))

		val snap = r.snapshot()
		assertThat(snap.skippedNextSectionOffers).isNull()
		assertThat(snap.offeredNextSectionHops).isNull()
		assertThat(snap.skippedNextSectionOffersStandingAtSignal).isNull()
		assertThat(snap.longestSkippedOfferStreakCycles).isNull()
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isNull()
	}

	/** An LLM run whose every cycle fell back was measured: nothing was skipped (0/0), not absent. */
	@Test
	fun `an LLM run whose cycles all fell back measures zero skipped offers`() {
		val r = llmRecorder()
		r.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 1.0))

		val snap = r.snapshot()
		assertThat(snap.skippedNextSectionOffers).isEqualTo(0L)
		assertThat(snap.offeredNextSectionHops).isEqualTo(0L)
		assertThat(snap.skippedNextSectionOffersStandingAtSignal).isEqualTo(0L)
		assertThat(snap.longestSkippedOfferStreakCycles).isEqualTo(0L)
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isEqualTo(0.0)
	}

	// ── Offer-group atomicity and failed counts (PR #1408 review round) ──────

	/**
	 * A tick whose NEXT SECTION count attempt threw ([TickRecord.nextSectionOffersFailed]): the
	 * cycle ended without a count, so it carries no [TickRecord.nextSectionOffers].
	 */
	private fun failedCountTick(simTime: Double): TickRecord =
		TickRecord(TickOutcome.LLM_NO_OP, simTime = simTime, nextSectionOffersFailed = true)

	@Test
	fun `a failed count drops the whole offer group absent on an LLM run`() {
		val r = llmRecorder()
		r.onTick(failedCountTick(simTime = 1.0))

		val snap = r.snapshot()
		assertOfferGroupAbsent(snap)

		// The failure must not drag a legitimate measurement down: an all-fallback LLM run (no
		// count attempted, nothing failed) still publishes its measured 0/0.
		val clean = llmRecorder()
		clean.onTick(TickRecord(TickOutcome.RULE_FALLBACK, simTime = 1.0))

		assertThat(clean.snapshot().skippedNextSectionOffers).isEqualTo(0L)
		assertThat(clean.snapshot().offeredNextSectionHops).isEqualTo(0L)
	}

	@Test
	fun `a later successful count does not rehabilitate a failed one`() {
		val r = llmRecorder()
		r.onTick(offerTick(1.0, offered = 2, skipped = setOf("T1")))
		r.onTick(offerTick(2.0, offered = 1, skipped = emptySet(), outcome = TickOutcome.LLM_ACTIONS))
		r.onTick(failedCountTick(simTime = 3.0))
		r.onTick(offerTick(4.0, offered = 3, skipped = setOf("T1", "T2")))

		val snap = r.finish(RunEndCause.NATURAL_COMPLETION)
		assertOfferGroupAbsent(snap)
	}

	private fun assertOfferGroupAbsent(snap: DispatcherRunSnapshot) {
		assertThat(snap.skippedNextSectionOffers).isNull()
		assertThat(snap.offeredNextSectionHops).isNull()
		assertThat(snap.skippedNextSectionOffersStandingAtSignal).isNull()
		assertThat(snap.longestSkippedOfferStreakCycles).isNull()
		assertThat(snap.longestSkippedOfferStreakSimSeconds).isNull()
	}

	/**
	 * The #1302 offer state moves as one group: one lock guards the three sums, the measured flag,
	 * the failure count and the streaks, so a snapshot concurrent with onTick sees either a tick's
	 * whole effect or none of it. This stress drives a deterministic script (mixed counts, the
	 * all-moving (0, 0) shape, no-count reset ticks) while a thread keeps building snapshots, and
	 * pins the impossible-torn-state invariant `skippedNextSectionOffers <= offeredNextSectionHops`
	 * on every one of them plus final frozen sums equal to the script's totals.
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	fun `concurrent snapshots never observe a torn NEXT SECTION offer group`() {
		val r = llmRecorder()
		// Every count tick skips as many hops as it offers, so the two sums agree at rest and a
		// snapshot reading one sum before onTick and the other after it would observe
		// skipped > offered — the only visible shape of the tear.
		val script =
			List(5_000) { i ->
				val simTime = (i + 1).toDouble()
				when (i % 10) {
					in 0..4 -> offerTick(simTime, offered = 1, skipped = setOf("T1"), standing = if (i % 2 == 0) 1 else 0)
					in 5..7 -> offerTick(simTime, offered = 2, skipped = setOf("T2", "T3"))
					8 -> offerTick(simTime, offered = 0, skipped = emptySet())
					else -> TickRecord(TickOutcome.RULE_FALLBACK, simTime = simTime)
				}
			}
		val expectedSkipped = script.sumOf { it.nextSectionOffers?.skipped?.toLong() ?: 0L }
		val expectedOffered = script.sumOf { it.nextSectionOffers?.offered?.toLong() ?: 0L }
		val expectedStanding = script.sumOf { it.nextSectionOffers?.skippedStandingAtSignal?.toLong() ?: 0L }

		val stop = AtomicBoolean(false)
		val observations = AtomicLong(0L)
		val torn = AtomicReference<DispatcherRunSnapshot?>()
		val observer =
			Thread {
				while (!stop.get()) {
					val snap = r.snapshot()
					observations.incrementAndGet()
					val skipped = snap.skippedNextSectionOffers
					val offered = snap.offeredNextSectionHops
					if (skipped != null && offered != null && skipped > offered) {
						torn.compareAndSet(null, snap)
						stop.set(true)
					}
				}
			}
		observer.start()
		script.forEach { r.onTick(it) }
		val frozen = r.finish(RunEndCause.MANUAL_STOP)
		stop.set(true)
		observer.join()

		// The long streak is "T1" skipped in cycles 0..4 of every 10-tick block (5 cycles; the
		// (0, 0) tick and the no-count tick after it end every streak).
		assertThat(torn.get()).isNull()
		assertThat(observations.get()).isGreaterThan(0L)
		assertThat(frozen.skippedNextSectionOffers).isEqualTo(expectedSkipped)
		assertThat(frozen.offeredNextSectionHops).isEqualTo(expectedOffered)
		assertThat(frozen.skippedNextSectionOffersStandingAtSignal).isEqualTo(expectedStanding)
		assertThat(frozen.longestSkippedOfferStreakCycles).isEqualTo(5L)
		assertThat(frozen.longestSkippedOfferStreakSimSeconds).isEqualTo(4.0)
	}

	// ── logFinalSummary log text (#1113 review round) ────────────────────────

	/**
	 * Verifies the integration point: [DefaultDispatcherRunRecorder.logFinalSummary] actually
	 * emits the #988 comparability note, not just that the figures underneath it are correct —
	 * the note is what tells a reader of the log that the printed `llmSuccessRate` and
	 * `actionableTickRate` cannot be compared with a pre-#988 run's (Issue #988).
	 *
	 * Follows the same Logback `ListAppender` pattern as `MeasuringPlanAdapterTest`'s
	 * `LogFinalSummaryLogText`: an appender attached to the root logger plus temporarily raising
	 * both the root and the `cz.vutbr.fit.interlockSim.dispatcher` package logger to INFO,
	 * because `logback-test.xml` pins the dispatcher package to WARN which would otherwise
	 * suppress the INFO-level note regardless of the root level.
	 */
	@Nested
	@DisplayName("logFinalSummary logs the comparability notes")
	inner class LogFinalSummaryLogText {
		private lateinit var appender: ListAppender<ILoggingEvent>
		private lateinit var rootLogger: LogbackLogger
		private lateinit var dispatcherLogger: LogbackLogger
		private var originalRootLevel: Level = Level.WARN
		private var originalDispatcherLevel: Level = Level.WARN

		@BeforeEach
		fun attachAppender() {
			rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
			dispatcherLogger =
				LoggerFactory.getLogger("cz.vutbr.fit.interlockSim.dispatcher") as LogbackLogger
			originalRootLevel = rootLogger.level
			originalDispatcherLevel = dispatcherLogger.level
			rootLogger.level = Level.INFO
			dispatcherLogger.level = Level.INFO
			appender = ListAppender()
			rootLogger.addAppender(appender)
			appender.start()
		}

		@AfterEach
		fun detachAppender() {
			rootLogger.detachAppender(appender)
			rootLogger.level = originalRootLevel
			dispatcherLogger.level = originalDispatcherLevel
		}

		@Test
		fun `logFinalSummary emits the #988 comparability note`() {
			recorder().logFinalSummary()

			val noteLines =
				appender.list
					.map { it.formattedMessage }
					.filter { it.contains("not comparable to pre-#988") }
			assertThat(noteLines).isNotEmpty()
			assertThat(noteLines.first()).contains("[DispatcherRunRecorder] note:")
			assertThat(noteLines.first()).contains("llmSuccessRate and actionableTickRate")
			assertThat(noteLines.first()).contains("LLM_SILENT_ALL_MOVING")
		}

		/**
		 * Issue #1280: the summary line keeps mid-run conflicts and the contentions still open at
		 * run end in two separate figures, so a cutoff run with trains still queued reads
		 * `conflicts=0 unresolvedAtRunEnd=2` instead of `conflicts=2`.
		 */
		@Test
		fun `final summary reports unresolved contentions at run end apart from conflicts`() {
			val r = recorder()
			r.recordRailwayOutcome(RailwayOutcome(conflicts = 0L, unresolvedAtRunEnd = 2L))
			r.finish(RunEndCause.NATURAL_COMPLETION)

			r.logFinalSummary()

			val summary =
				appender.list
					.map { it.formattedMessage }
					.single { it.contains("final summary") }
			assertThat(summary).contains("conflicts=0 ")
			assertThat(summary).contains("unresolvedAtRunEnd=2 ")
		}

		/** Issue #1302: the summary line names the skipped NEXT SECTION offers next to the offered total. */
		@Test
		fun `final summary reports the skipped NEXT SECTION offers`() {
			val r = recorder()
			r.onTick(offerTick(1.0, offered = 4, skipped = setOf("T1", "T2", "T3"), standing = 2))
			r.onTick(offerTick(3.0, offered = 1, skipped = setOf("T1")))

			r.logFinalSummary()

			val summary =
				appender.list
					.map { it.formattedMessage }
					.single { it.contains("final summary") }
			assertThat(summary).contains("skippedNextSectionOffers=4 ")
			assertThat(summary).contains("offeredNextSectionHops=5 ")
			assertThat(summary).contains("skippedNextSectionOffersStandingAtSignal=2 ")
			assertThat(summary).contains("longestSkippedOfferStreakCycles=2 ")
			assertThat(summary).contains("longestSkippedOfferStreakSimSeconds=2.0")
		}

		@Test
		fun `final summary marks the skipped NEXT SECTION offers as n-a when nothing measured them`() {
			recorder().logFinalSummary()

			val summary =
				appender.list
					.map { it.formattedMessage }
					.single { it.contains("final summary") }
			assertThat(summary).contains("skippedNextSectionOffers=n/a ")
		}

		@Test
		fun `final summary marks unresolved contentions as n-a when nothing measured them`() {
			recorder().logFinalSummary()

			val summary =
				appender.list
					.map { it.formattedMessage }
					.single { it.contains("final summary") }
			assertThat(summary).contains("unresolvedAtRunEnd=n/a ")
		}
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
