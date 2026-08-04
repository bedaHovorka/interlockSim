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
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Unit tests for [RunReportAggregator] and [ArmReport].
 *
 * All tests use synthetic [DispatcherRunSnapshot] fixtures — no live Ollama required.
 *
 * @since Issue #846 (SP2c.23 — cross-run aggregator + Markdown report + Gradle task)
 */
class RunReportAggregatorTest {

	private val store = mockk<RunSnapshotStore> {
		every { readAll(any<Path>()) } returns emptyList()
		every { write(any()) } throws UnsupportedOperationException("test-only store")
	}
	private val aggregator = RunReportAggregator(store)

	// ── Gate logic ────────────────────────────────────────────────────────────

	@Test
	fun `runPassed requires completedNaturally, no terminalFallback, and c7Clean`() {
		assertThat(aggregator.runPassed(snapshot(completedNaturally = true, fallback = false, c7Clean = true))).isTrue()
		assertThat(aggregator.runPassed(snapshot(completedNaturally = false, fallback = false, c7Clean = true))).isFalse()
		assertThat(aggregator.runPassed(snapshot(completedNaturally = true, fallback = true, c7Clean = true))).isFalse()
		assertThat(aggregator.runPassed(snapshot(completedNaturally = true, fallback = false, c7Clean = false))).isFalse()
	}

	@Test
	fun `gatePassed requires runCount ge 10 and passingRuns ge 8 and all c7Clean`() {
		// 10 passing runs, all c7Clean → gate passes
		val tenPassing = (1..10).map { i -> snapshot(runId = "r$i", c7Clean = true) }
		val report = aggregator.aggregate(tenPassing)
		assertThat(report.gatePassed).isTrue()
		assertThat(report.passingRuns).isEqualTo(10)
	}

	@Test
	fun `gatePassed fails when fewer than 10 runs even if all pass`() {
		val nineRuns = (1..9).map { i -> snapshot(runId = "r$i", c7Clean = true) }
		val report = aggregator.aggregate(nineRuns)
		assertThat(report.gatePassed).isFalse()
	}

	@Test
	fun `gatePassed fails when fewer than 8 passing runs even at runCount 10`() {
		// 7 passing + 3 failing (completedNaturally = false) → 7 < 8
		val snapshots = (1..7).map { i -> snapshot(runId = "p$i", c7Clean = true) } +
			(1..3).map { i -> snapshot(runId = "f$i", completedNaturally = false, c7Clean = true) }
		val report = aggregator.aggregate(snapshots)
		assertThat(report.gatePassed).isFalse()
		assertThat(report.passingRuns).isEqualTo(7)
	}

	@Test
	fun `single non-c7Clean run fails entire arm even at 10 of 10 completions`() {
		// 9 clean passing + 1 non-c7Clean (but completedNaturally) → gate must fail
		val snapshots = (1..9).map { i -> snapshot(runId = "c$i", c7Clean = true) } +
			listOf(snapshot(runId = "dirty", c7Clean = false))
		val report = aggregator.aggregate(snapshots)

		assertThat(report.runCount).isEqualTo(10)
		// passingRuns counts only runs where c7Clean=true as well
		assertThat(report.passingRuns).isEqualTo(9)
		// allC7Clean is false → gate must fail regardless of counts
		assertThat(report.allC7Clean).isFalse()
		assertThat(report.gatePassed).isFalse()
	}

	@Test
	fun `allC7Clean is false when any snapshot has c7Clean false`() {
		val snapshots = listOf(
			snapshot(runId = "a", c7Clean = true),
			snapshot(runId = "b", c7Clean = false)
		)
		val report = aggregator.aggregate(snapshots)
		assertThat(report.allC7Clean).isFalse()
	}

	// ── Aggregation ───────────────────────────────────────────────────────────

	@Test
	fun `aggregate on empty list produces zero-count report with gatePassed false`() {
		val report = aggregator.aggregate(emptyList())
		assertThat(report.runCount).isEqualTo(0)
		assertThat(report.gatePassed).isFalse()
	}

	@Test
	fun `aggregate computes median llmSuccessRate correctly`() {
		val snapshots = listOf(
			snapshot(runId = "r1", llmSuccessRate = 0.6),
			snapshot(runId = "r2", llmSuccessRate = 0.8),
			snapshot(runId = "r3", llmSuccessRate = 1.0)
		)
		val report = aggregator.aggregate(snapshots)
		// Sorted: [0.6, 0.8, 1.0] → median is 0.8
		assertThat(report.medianLlmSuccessRate).isEqualTo(0.8)
	}

	@Test
	fun `aggregate sums rejection counts across all runs`() {
		val snap1 = snapshot(runId = "s1", rejections = mapOf(RejectionCode.UNKNOWN_TRAIN to 3L))
		val snap2 = snapshot(runId = "s2", rejections = mapOf(RejectionCode.UNKNOWN_TRAIN to 2L))
		val report = aggregator.aggregate(listOf(snap1, snap2))
		assertThat(report.rejectionCounts[RejectionCode.UNKNOWN_TRAIN]).isEqualTo(5L)
	}

	@Test
	fun `aggregate sums apply-failure counts across all runs`() {
		val snap1 = snapshot(runId = "af1", applyFailures = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED to 4L))
		val snap2 = snapshot(runId = "af2", applyFailures = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED to 1L))
		val report = aggregator.aggregate(listOf(snap1, snap2))
		assertThat(report.applyFailureCounts[ApplyFailureCode.ALL_PATHS_BLOCKED]).isEqualTo(5L)
	}

	@Test
	fun `aggregate sums author counts across all runs`() {
		val snap1 = snapshot(runId = "au1", authorCounts = mapOf(ActionAuthor.LLM to 10L))
		val snap2 = snapshot(runId = "au2", authorCounts = mapOf(ActionAuthor.LLM to 5L))
		val report = aggregator.aggregate(listOf(snap1, snap2))
		assertThat(report.authorCounts[ActionAuthor.LLM]).isEqualTo(15L)
	}

	// ── Markdown rendering ────────────────────────────────────────────────────

	@Test
	fun `renderMarkdown produces non-empty string`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "md1")))
		val md = aggregator.renderMarkdown(listOf(report))
		assertThat(md).isNotEmpty()
	}

	@Test
	fun `renderMarkdown contains all table headings`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "tables")))
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md).transform("contains T1") { md.contains("T1 Arm Comparison") }.isTrue()
		assertThat(md).transform("contains T2") { md.contains("T2 Per-Run Detail") }.isTrue()
		assertThat(md).transform("contains T3") { md.contains("T3 Failure Modes") }.isTrue()
		assertThat(md).transform("contains T4") { md.contains("T4 Apply Failures") }.isTrue()
		assertThat(md).transform("contains T5") { md.contains("T5 Author Attribution") }.isTrue()
		assertThat(md).transform("contains T6") { md.contains("T6 Latency") }.isTrue()
		assertThat(md).transform("contains T7") { md.contains("T7 Parameter Sweep") }.isTrue()
	}

	@Test
	fun `renderMarkdown output is English only — no Czech strings`() {
		val snapshots = (1..10).map { i -> snapshot(runId = "en$i") }
		val report = aggregator.aggregate(snapshots)
		val md = aggregator.renderMarkdown(listOf(report))

		// Check that common Czech words do not appear in the output
		val czechPatterns = listOf(
			"Jízdní", "jízdní", "cesty", "cesta", "volnost", "závěr",
			"postaveno", "nástupník", "vlak", "stanice"
		)
		for (pattern in czechPatterns) {
			assertThat(md).doesNotContain(pattern)
		}
	}

	@Test
	fun `renderMarkdown shows PASS for arm with 10 passing runs`() {
		val snapshots = (1..10).map { i -> snapshot(runId = "pass$i") }
		val report = aggregator.aggregate(snapshots)
		val md = aggregator.renderMarkdown(listOf(report))
		assertThat(md).transform("contains PASS") { md.contains("PASS") }.isTrue()
	}

	@Test
	fun `renderMarkdown shows FAIL for arm with fewer than 10 runs`() {
		val snapshots = (1..5).map { i -> snapshot(runId = "fail$i") }
		val report = aggregator.aggregate(snapshots)
		val md = aggregator.renderMarkdown(listOf(report))
		assertThat(md).transform("contains FAIL") { md.contains("FAIL") }.isTrue()
	}

	@Test
	fun `renderMarkdown handles multiple arms without mixing rows`() {
		val ruleReport = aggregator.aggregate(listOf(snapshot(runId = "rb1", arm = DispatcherArm.RULE_BASED)))
		val llmReport = aggregator.aggregate(listOf(snapshot(runId = "llm1", arm = DispatcherArm.LLM_TOOL_CALLING)))
		val md = aggregator.renderMarkdown(listOf(ruleReport, llmReport))

		assertThat(md).transform("contains RULE_BASED") { md.contains("RULE_BASED") }.isTrue()
		assertThat(md).transform("contains LLM_TOOL_CALLING") { md.contains("LLM_TOOL_CALLING") }.isTrue()
	}

	@Test
	fun `p95LatencyMs reflects 95th percentile of per-run latencyP95Ms`() {
		val snapshots = (1..10).map { i ->
			snapshot(runId = "lat$i", latencyP95Ms = (i * 100L))
		}
		val report = aggregator.aggregate(snapshots)
		// Sorted latencies: 100..1000; p95 index = (10-1)*95/100 = 8 → value at index 8 = 900
		assertThat(report.p95LatencyMs).isGreaterThanOrEqualTo(800L)
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun snapshot(
		runId: String = "test-run",
		arm: DispatcherArm = DispatcherArm.RULE_BASED,
		completedNaturally: Boolean = true,
		fallback: Boolean = false,
		c7Clean: Boolean = true,
		llmSuccessRate: Double = 1.0,
		latencyP95Ms: Long = 200L,
		rejections: Map<RejectionCode, Long> = emptyMap(),
		applyFailures: Map<ApplyFailureCode, Long> = emptyMap(),
		authorCounts: Map<ActionAuthor, Long> = emptyMap()
	): DispatcherRunSnapshot {
		val outcomes = TickOutcome.entries.associate { it.name to 0L }.toMutableMap()
		outcomes[TickOutcome.LLM_ACTIONS.name] = 1L

		return DispatcherRunSnapshot(
			runId = runId,
			arm = arm,
			params = RunParameters(
				tickPeriodMs = 500L,
				historyN = 10,
				temperature = 0.0,
				maxActionsPerTick = 3,
				model = "",
				seed = null
			),
			totalTicks = 1L,
			ticksByOutcome = outcomes,
			timeoutNoOpByCause = TimeoutNoOpCause.entries.associate { it.name to 0L },
			llmSuccessRate = llmSuccessRate,
			noOpRate = 0.0,
			invalidOutputRate = 0.0,
			repairSuccessRate = 0.0,
			emittedByActionType = emptyMap(),
			rejectionsByCode = rejections.mapKeys { it.key.name },
			applyFailuresByCode = applyFailures.mapKeys { it.key.name },
			validAt1 = 1.0,
			correctAt1 = null,
			oracleAgreementAt1 = null,
			latencyP50Ms = 100L,
			latencyP95Ms = latencyP95Ms,
			latencyMaxMs = 300L,
			actionsByAuthor = authorCounts.mapKeys { it.key.name },
			unattributedApplies = 0L,
			terminalFallbackEngaged = fallback,
			terminalFallbackTickIndex = if (fallback) 5L else null,
			c7Clean = c7Clean,
			completedNaturally = completedNaturally,
			endCause = if (completedNaturally) RunEndCause.NATURAL_COMPLETION else RunEndCause.TIMEOUT_ABORT
		)
	}
}
