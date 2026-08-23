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
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
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
	private val store =
		mockk<RunSnapshotStore> {
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

	/**
	 * Issue #930: a starved run is recorded as [RunEndCause.STARVED], which
	 * `DefaultDispatcherRunRecorder` turns into `completedNaturally = false`. Nothing in the gate
	 * predicate had to change for that to be rejected — this test pins that the composition works,
	 * so a later "simplification" of the recorder cannot silently let a dead railway pass again.
	 */
	@Test
	fun `runPassed rejects a STARVED run`() {
		val starved =
			snapshot(completedNaturally = false, fallback = false, c7Clean = true)
				.copy(
					endCause = RunEndCause.STARVED,
					railwayOutcome = RailwayOutcome(journeysCompleted = 0L, trainsExited = 0L)
				)

		assertThat(aggregator.runPassed(starved)).isFalse()
	}

	// ── Actionable-rate gate composition (Issue #927) ──────────────────────────

	@Test
	fun `runPassed fails below MIN_ACTIONABLE_RATE even when every other condition passes`() {
		val belowThreshold =
			snapshot(
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				arm = DispatcherArm.LLM_TOOL_CALLING,
				actionableTickRate = RunReportAggregator.MIN_ACTIONABLE_RATE - 0.01
			)
		assertThat(aggregator.runPassed(belowThreshold)).isFalse()
	}

	@Test
	fun `runPassed passes at or above MIN_ACTIONABLE_RATE when every other condition passes`() {
		val atThreshold =
			snapshot(
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				arm = DispatcherArm.LLM_TOOL_CALLING,
				actionableTickRate = RunReportAggregator.MIN_ACTIONABLE_RATE
			)
		assertThat(aggregator.runPassed(atThreshold)).isTrue()
	}

	/**
	 * Proves the "AND" composition (Issue #927): a run with a clean railway outcome (good
	 * completedNaturally/fallback/c7Clean) but a low actionable rate must still fail, and a run
	 * with a high actionable rate but a dirty railway outcome must also still fail — neither
	 * factor alone can carry the gate.
	 */
	@Test
	fun `runPassed requires both a passing railway outcome AND an adequate actionable rate`() {
		val llm = DispatcherArm.LLM_TOOL_CALLING
		val goodRailwayLowRate =
			snapshot(arm = llm, completedNaturally = true, fallback = false, c7Clean = true, actionableTickRate = 0.1)
		val badRailwayHighRate =
			snapshot(arm = llm, completedNaturally = false, fallback = false, c7Clean = true, actionableTickRate = 1.0)
		val bothGood =
			snapshot(arm = llm, completedNaturally = true, fallback = false, c7Clean = true, actionableTickRate = 1.0)

		assertThat(aggregator.runPassed(goodRailwayLowRate)).isFalse()
		assertThat(aggregator.runPassed(badRailwayHighRate)).isFalse()
		assertThat(aggregator.runPassed(bothGood)).isTrue()
	}

	/**
	 * The measured #895 defect. A [DispatcherArm.RULE_BASED] run never consults an LLM, so it
	 * records `totalTicks = 0` and therefore `actionableTickRate = 0.0`. Applying the #927
	 * actionable-rate clause to it failed a control arm that had just delivered eleven train
	 * exits on every one of its ten runs.
	 *
	 * The #895 campaign printed exactly that: `| RULE_BASED | 10 | 0 | FAIL | ... 0.000 ... |`,
	 * where #847 and #834 had both published the same control arm as 10/10 PASS. The clause is a
	 * statement about LLM decision quality and has no meaning for an arm that makes no LLM
	 * decisions.
	 */
	@Test
	fun `runPassed does not apply the actionable-rate clause to the rule-based arm`() {
		val control =
			snapshot(
				arm = DispatcherArm.RULE_BASED,
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				llmSuccessRate = 0.0,
				actionableTickRate = 0.0,
				railwayOutcome = RailwayOutcome(journeysCompleted = 11L, trainsEntered = 15L, trainsExited = 11L)
			)

		assertThat(aggregator.runPassed(control)).isTrue()
	}

	/**
	 * The same zero rate on an LLM arm is a real failure, not an exemption — the guard is scoped
	 * to the arm that cannot produce the measurement, not to the value zero.
	 */
	@Test
	fun `runPassed still applies the actionable-rate clause to an LLM arm`() {
		val llm =
			snapshot(
				arm = DispatcherArm.LLM_TOOL_CALLING,
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				actionableTickRate = 0.0
			)

		assertThat(aggregator.runPassed(llm)).isFalse()
	}

	/**
	 * The outcome term (#895). A run that completes naturally with a clean decision record can
	 * still have moved almost nothing, and nothing in the pre-#895 predicate noticed.
	 * [RunReportAggregator.MIN_TRAINS_EXITED] is derived from the deterministic control arm, not
	 * from the LLM arm it judges.
	 */
	@Test
	fun `runPassed fails a naturally completed run that moved too few trains`() {
		val weak =
			snapshot(
				arm = DispatcherArm.LLM_TOOL_CALLING,
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				actionableTickRate = 1.0,
				railwayOutcome =
					RailwayOutcome(
						journeysCompleted = 3L,
						trainsEntered = 15L,
						trainsExited = RunReportAggregator.MIN_TRAINS_EXITED - 1
					)
			)
		val adequate =
			snapshot(
				arm = DispatcherArm.LLM_TOOL_CALLING,
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				actionableTickRate = 1.0,
				railwayOutcome =
					RailwayOutcome(
						journeysCompleted = 6L,
						trainsEntered = 15L,
						trainsExited = RunReportAggregator.MIN_TRAINS_EXITED
					)
			)

		assertThat(aggregator.runPassed(weak)).isFalse()
		assertThat(aggregator.runPassed(adequate)).isTrue()
	}

	/**
	 * An absent exit count is "not measured", never "zero" — the same rule [RailwayOutcome] and
	 * [RailwayProgress] already follow. A run nobody measured must not be failed by a term that
	 * had nothing to read.
	 */
	@Test
	fun `runPassed ignores the outcome term when the exit count was not measured`() {
		val unmeasured =
			snapshot(
				arm = DispatcherArm.LLM_TOOL_CALLING,
				completedNaturally = true,
				fallback = false,
				c7Clean = true,
				actionableTickRate = 1.0,
				railwayOutcome = RailwayOutcome.UNMEASURED
			)

		assertThat(aggregator.runPassed(unmeasured)).isTrue()
	}

	@Test
	fun `aggregate computes median actionableTickRate correctly`() {
		val snapshots =
			listOf(
				snapshot(runId = "a1", actionableTickRate = 0.4),
				snapshot(runId = "a2", actionableTickRate = 0.7),
				snapshot(runId = "a3", actionableTickRate = 0.9)
			)
		val report = aggregator.aggregate(snapshots)
		// Sorted: [0.4, 0.7, 0.9] -> median is 0.7
		assertThat(report.medianActionableTickRate).isEqualTo(0.7)
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
		val snapshots =
			(1..7).map { i -> snapshot(runId = "p$i", c7Clean = true) } +
				(1..3).map { i -> snapshot(runId = "f$i", completedNaturally = false, c7Clean = true) }
		val report = aggregator.aggregate(snapshots)
		assertThat(report.gatePassed).isFalse()
		assertThat(report.passingRuns).isEqualTo(7)
	}

	@Test
	fun `single non-c7Clean run fails entire arm even at 10 of 10 completions`() {
		// 9 clean passing + 1 non-c7Clean (but completedNaturally) → gate must fail
		val snapshots =
			(1..9).map { i -> snapshot(runId = "c$i", c7Clean = true) } +
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
		val snapshots =
			listOf(
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
		val snapshots =
			listOf(
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

		assertThat(md).transform("contains Arm Comparison") { md.contains("## Arm Comparison") }.isTrue()
		assertThat(md).transform("contains Per-Run Detail") { md.contains("## Per-Run Detail") }.isTrue()
		assertThat(md).transform("contains Failure Modes") { md.contains("## Failure Modes") }.isTrue()
		assertThat(md).transform("contains Apply Failures") { md.contains("## Apply Failures") }.isTrue()
		assertThat(md).transform("contains Author Attribution") { md.contains("## Author Attribution") }.isTrue()
		assertThat(md).transform("contains Latency") { md.contains("## Latency") }.isTrue()
		assertThat(md).transform("contains Parameter Sweep") { md.contains("## Parameter Sweep") }.isTrue()
	}

	@Test
	fun `renderMarkdown output is English only — no Czech strings`() {
		val snapshots = (1..10).map { i -> snapshot(runId = "en$i") }
		val report = aggregator.aggregate(snapshots)
		val md = aggregator.renderMarkdown(listOf(report))

		// Check that common Czech words do not appear in the output
		val czechPatterns =
			listOf(
				"Jízdní",
				"jízdní",
				"cesty",
				"cesta",
				"volnost",
				"závěr",
				"postaveno",
				"nástupník",
				"vlak",
				"stanice"
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
		val snapshots =
			(1..10).map { i ->
				snapshot(runId = "lat$i", latencyP95Ms = (i * 100L))
			}
		val report = aggregator.aggregate(snapshots)
		// Sorted latencies: 100..1000; p95 index = (10-1)*95/100 = 8 → value at index 8 = 900
		assertThat(report.p95LatencyMs).isEqualTo(900L)
	}

	@Test
	fun `p95LatencyMs is null when every run has null latency, and skips null runs when some are present`() {
		// Review finding #3 (Issue #834): an unmeasured run has null latencyP95Ms (absent, not
		// zero). The cross-run p95 must skip nulls rather than count them as 0, and be null only
		// when *every* run is unmeasured — never a bare 0 that would misread as "answered in 0 ms".
		val allNull =
			(1..10).map { i ->
				snapshot(runId = "rule$i", latencyP50Ms = null, latencyP95Ms = null, latencyMaxMs = null)
			}
		assertThat(aggregator.aggregate(allNull).p95LatencyMs).isNull()

		// 8 measured runs (100..800) + 2 unmeasured (null, skipped) -> p95 over the 8:
		// idx = (8-1)*95/100 = 6 -> sorted[6] = 700 (the cross-run linear-index percentile95).
		val mixed =
			(1..8).map { i -> snapshot(runId = "lat$i", latencyP95Ms = (i * 100L)) } +
				listOf(
					snapshot(runId = "rule1", latencyP95Ms = null),
					snapshot(runId = "rule2", latencyP95Ms = null)
				)
		assertThat(aggregator.aggregate(mixed).p95LatencyMs).isEqualTo(700L)
	}

	@Test
	fun `aggregate computes medianCorrectAt1 only from snapshots with oracle data`() {
		val snapshots =
			listOf(
				snapshot(runId = "o1", correctAt1 = 0.5),
				snapshot(runId = "o2", correctAt1 = 0.9)
			)
		val report = aggregator.aggregate(snapshots)
		assertThat(report.medianCorrectAt1).isEqualTo(0.7)
	}

	@Test
	fun `renderMarkdown shows correctAt1 value in Arm Comparison and Parameter Sweep when oracle data present`() {
		// Task 5 (#834) legitimately introduced "n/a" elsewhere in the report (invalid-action rate
		// and railway-outcome columns, for figures nothing measured), so this can no longer assert
		// a blanket absence of "n/a" — it must check the correctAt1 column specifically.
		val report = aggregator.aggregate(listOf(snapshot(runId = "oracle1", correctAt1 = 0.75)))
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md).transform("Arm Comparison shows correctAt1") { md.contains("| 0.750 |") }.isTrue()
		val hygieneCells = tableRowCells(hygieneSection(md), DispatcherArm.RULE_BASED.name)
		assertThat(hygieneCells[17]).isEqualTo("0.750")
	}

	@Test
	fun `renderMarkdown shows non-zero rejection, apply-failure, and author counts and a dirty C7 arm`() {
		val dirtySnapshot =
			snapshot(
				runId = "dirty1",
				c7Clean = false,
				rejections = mapOf(RejectionCode.UNKNOWN_TRAIN to 2L),
				applyFailures = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED to 3L),
				authorCounts = mapOf(ActionAuthor.LLM to 4L)
			)
		val report = aggregator.aggregate(listOf(dirtySnapshot))
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md)
			.transform("shows rejection count") { md.contains("| ${RejectionCode.UNKNOWN_TRAIN} | 2 |") }
			.isTrue()
		assertThat(md)
			.transform("shows apply-failure count") { md.contains("| ${ApplyFailureCode.ALL_PATHS_BLOCKED} | 3 |") }
			.isTrue()
		assertThat(md).transform("shows author count") { md.contains("| ${ActionAuthor.LLM} | 4 |") }.isTrue()
		assertThat(md).transform("shows C7-not-clean as no") { md.contains("| no |") }.isTrue()
	}

	@Test
	fun `renderMarkdown falls back to placeholders for an arm report with no snapshots`() {
		// aggregate(emptyList()) is what DispatcherReliabilityReportKt.main() feeds in for an arm
		// with zero runs recorded yet — Latency and Parameter Sweep must render defaults, not throw.
		val emptyArmReport = aggregator.aggregate(emptyList())
		val md = aggregator.renderMarkdown(listOf(emptyArmReport))

		assertThat(md).transform("Parameter Sweep falls back to rule-based") { md.contains("rule-based") }.isTrue()
		assertThat(md).transform("Parameter Sweep falls back to unset seed") { md.contains("unset") }.isTrue()
	}

	@Test
	fun `renderMarkdown shows configured model and seed in Parameter Sweep`() {
		val report =
			aggregator.aggregate(
				listOf(snapshot(runId = "params1", model = "qwen2.5:7b-instruct", seed = 42L))
			)
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md).transform("shows model") { md.contains("qwen2.5:7b-instruct") }.isTrue()
		assertThat(md).transform("shows seed") { md.contains("| 42 |") }.isTrue()
	}

	@Test
	fun `Parameter Sweep renders one row per parameter cell, not one per arm`() {
		// Issue #847 (SP2c.24): a sweep over two temperatures used to collapse into a single
		// LLM_TOOL_CALLING row labelled with whichever cell was read first — a "Parameter Sweep"
		// section that could not show two parameter values.
		val cold =
			listOf(
				snapshot(runId = "cold1", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.28),
				snapshot(runId = "cold2", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.28)
			)
		val hot =
			listOf(snapshot(runId = "hot1", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.5))
		val report = aggregator.aggregate(cold + hot)
		val md = aggregator.renderMarkdown(listOf(report))

		// Task 5 (#834): the Parameter Sweep section is now two tables (Decision Hygiene, Railway
		// Outcomes) sharing the same parameter-identity columns; check each table independently so
		// this assertion still means "one row per cell" rather than "one row per cell per table".
		val hygiene = hygieneSection(md)
		assertThat(hygiene).transform("shows the 0.28 cell") { it.contains("| 0.28 |") }.isTrue()
		assertThat(hygiene).transform("shows the 0.5 cell") { it.contains("| 0.5 |") }.isTrue()
		assertThat(hygiene)
			.transform("counts runs per cell, not per arm") {
				val dataRows = it.lines().filter { line -> line.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }
				dataRows.size == 2
			}.isTrue()

		val outcomes = outcomesSection(md)
		assertThat(outcomes)
			.transform("Railway Outcomes also has one row per cell") {
				val dataRows = it.lines().filter { line -> line.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }
				dataRows.size == 2
			}.isTrue()
	}

	@Test
	fun `Parameter Sweep row reports that cell's own run count`() {
		val report =
			aggregator.aggregate(
				listOf(
					snapshot(runId = "a1", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.28),
					snapshot(runId = "a2", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.28),
					snapshot(runId = "b1", arm = DispatcherArm.LLM_TOOL_CALLING, temperature = 0.9)
				)
			)
		val md = aggregator.renderMarkdown(listOf(report))
		val hygiene = hygieneSection(md)
		val rows =
			hygiene
				.lines()
				.filter { it.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }

		// Pooling the three runs into one row would report "3" against a single configuration.
		assertThat(rows.first { it.contains("| 0.28 |") })
			.transform("0.28 cell has 2 runs") { it.contains("| 2 | 2 |") }
			.isTrue()
		assertThat(rows.first { it.contains("| 0.9 |") })
			.transform("0.9 cell has 1 run") { it.contains("| 1 | 1 |") }
			.isTrue()
	}

	// ── Task 5 (#834): AC-required per-cell columns ─────────────────────────────

	@Test
	fun `Parameter Sweep reports every AC-required metric from known snapshot values`() {
		val snap =
			snapshot(
				runId = "ac1",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				llmSuccessRate = 0.75,
				noOpRate = 0.2,
				repairSuccessRate = 0.1,
				latencyP50Ms = 150L,
				latencyP95Ms = 250L,
				c7Clean = true,
				emittedByActionType = mapOf("MOVE_TRAIN" to 10L),
				rejections = mapOf(RejectionCode.UNKNOWN_TRAIN to 3L),
				ruleFallbackTicks = 2L,
				inferenceTimeoutSeconds = 90L,
				promptVariant = "v2",
				railwayOutcome =
					RailwayOutcome(
						journeysCompleted = 5L,
						trainsEntered = 6L,
						trainsExited = 4L,
						maxConcurrentTrains = 3L,
						blockTransitions = 20L,
						conflicts = 1L,
						failedReservations = 2L
					)
			)
		val report = aggregator.aggregate(listOf(snap))
		val md = aggregator.renderMarkdown(listOf(report))

		val hygieneCells = tableRowCells(hygieneSection(md), DispatcherArm.LLM_TOOL_CALLING.name)
		assertThat(hygieneCells[7]).isEqualTo("90")
		assertThat(hygieneCells[8]).isEqualTo("v2")
		assertThat(hygieneCells[12]).isEqualTo("0.750")
		// Invalid-action rate = 3 rejected / 10 emitted actions = 0.300 (action-scoped, not tick-scoped).
		assertThat(hygieneCells[13]).isEqualTo("0.300")
		assertThat(hygieneCells[14]).isEqualTo("0.200")
		assertThat(hygieneCells[15]).isEqualTo("0.100")
		assertThat(hygieneCells[18]).isEqualTo("150")
		assertThat(hygieneCells[19]).isEqualTo("250")
		assertThat(hygieneCells[20]).isEqualTo("yes")
		assertThat(hygieneCells[21]).isEqualTo("2")

		val outcomeCells = tableRowCells(outcomesSection(md), DispatcherArm.LLM_TOOL_CALLING.name)
		assertThat(outcomeCells[10]).isEqualTo("5")
		assertThat(outcomeCells[11]).isEqualTo("6")
		assertThat(outcomeCells[12]).isEqualTo("4")
		assertThat(outcomeCells[13]).isEqualTo("3")
		assertThat(outcomeCells[14]).isEqualTo("20")
		assertThat(outcomeCells[15]).isEqualTo("1")
		assertThat(outcomeCells[16]).isEqualTo("2")
	}

	@Test
	fun `invalid-action rate is computed from emitted and rejected actions, not from ticks`() {
		// invalidOutputRate (tick-scoped) stays at its default 0.0 here; emitted=20 actions with 5
		// rejected gives an action-scoped rate of 0.25 — a value invalidOutputRate could never
		// produce from this fixture, so this pins that the two are not silently interchangeable.
		val snap =
			snapshot(
				runId = "action-rate",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				invalidOutputRate = 0.0,
				emittedByActionType = mapOf("MOVE_TRAIN" to 15L, "SET_SIGNAL" to 5L),
				rejections = mapOf(RejectionCode.UNKNOWN_TRAIN to 5L)
			)
		val report = aggregator.aggregate(listOf(snap))
		val md = aggregator.renderMarkdown(listOf(report))

		val cells = tableRowCells(hygieneSection(md), DispatcherArm.LLM_TOOL_CALLING.name)
		assertThat(cells[13]).isEqualTo("0.250")
	}

	@Test
	fun `invalid-action rate is n slash a, not zero, when no run in the cell emitted any action`() {
		val snap = snapshot(runId = "no-actions", arm = DispatcherArm.LLM_TOOL_CALLING)
		val report = aggregator.aggregate(listOf(snap))
		val md = aggregator.renderMarkdown(listOf(report))

		val cells = tableRowCells(hygieneSection(md), DispatcherArm.LLM_TOOL_CALLING.name)
		assertThat(cells[13]).isEqualTo("n/a")
	}

	@Test
	fun `cells differing only in inferenceTimeoutSeconds render as separate rows`() {
		val short = snapshot(runId = "short", arm = DispatcherArm.LLM_TOOL_CALLING, inferenceTimeoutSeconds = 30L)
		val long = snapshot(runId = "long", arm = DispatcherArm.LLM_TOOL_CALLING, inferenceTimeoutSeconds = 90L)
		val report = aggregator.aggregate(listOf(short, long))
		val md = aggregator.renderMarkdown(listOf(report))

		val rows = hygieneSection(md).lines().filter { it.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }
		assertThat(rows.size).isEqualTo(2)
		assertThat(rows.any { it.contains("| 30 |") }).isTrue()
		assertThat(rows.any { it.contains("| 90 |") }).isTrue()
	}

	@Test
	fun `cells differing only in promptVariant render as separate rows`() {
		val v1 = snapshot(runId = "pv1", arm = DispatcherArm.LLM_TOOL_CALLING, promptVariant = "prompt-v1")
		val v2 = snapshot(runId = "pv2", arm = DispatcherArm.LLM_TOOL_CALLING, promptVariant = "prompt-v2")
		val report = aggregator.aggregate(listOf(v1, v2))
		val md = aggregator.renderMarkdown(listOf(report))

		val rows = hygieneSection(md).lines().filter { it.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }
		assertThat(rows.size).isEqualTo(2)
		assertThat(rows.any { it.contains("| prompt-v1 |") }).isTrue()
		assertThat(rows.any { it.contains("| prompt-v2 |") }).isTrue()
	}

	@Test
	fun `railway outcome figures render as n slash a, not zero, when never measured`() {
		val snap = snapshot(runId = "unmeasured", arm = DispatcherArm.LLM_TOOL_CALLING)
		val report = aggregator.aggregate(listOf(snap))
		val md = aggregator.renderMarkdown(listOf(report))

		val cells = tableRowCells(outcomesSection(md), DispatcherArm.LLM_TOOL_CALLING.name)
		// Indices 10..16: journeysCompleted, trainsEntered, trainsExited, maxConcurrentTrains,
		// blockTransitions, conflicts, failedReservations — RailwayOutcome.UNMEASURED, all null.
		(10..16).forEach { idx -> assertThat(cells[idx]).isEqualTo("n/a") }
	}

	// ── Logged FATAL simulation exceptions (measurement-integrity fix for #834's C2 condition, renamed #913) ──

	@Test
	fun `a run with no fatal-exception scan renders n slash a in Per-Run Detail, not a bare zero`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "unscanned", loggedFatalSimExceptionCount = null)))
		val md = aggregator.renderMarkdown(listOf(report))

		val row = md.lines().first { it.startsWith("| ${DispatcherArm.RULE_BASED} | unscanned ") }
		assertThat(row).transform("ends with n/a") { it.trimEnd().endsWith("| n/a |") }.isTrue()
	}

	@Test
	fun `a run whose log was scanned clean renders zero in Per-Run Detail, not n slash a`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "clean", loggedFatalSimExceptionCount = 0L)))
		val md = aggregator.renderMarkdown(listOf(report))

		val row = md.lines().first { it.startsWith("| ${DispatcherArm.RULE_BASED} | clean ") }
		assertThat(row).transform("ends with 0") { it.trimEnd().endsWith("| 0 |") }.isTrue()
	}

	@Test
	fun `no run with a logged FATAL renders the reassuring message in the section, not a table`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "ok1", loggedFatalSimExceptionCount = 0L)))
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md)
			.transform("contains heading") { it.contains("## Logged FATAL Simulation Exceptions") }
			.isTrue()
		assertThat(md)
			.transform("contains the no-FATAL message") { it.contains("No run recorded a FATAL `SimulationException`") }
			.isTrue()
	}

	@Test
	fun `a run with a logged FATAL is listed in the section with its count and first message`() {
		val flagged =
			snapshot(
				runId = "doomed",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				loggedFatalSimExceptionCount = 2L,
				loggedFatalSimExceptionFirstMessage = "SimulationException[FATAL]: pathToSemaphore null at time 12.5"
			)
		val clean = snapshot(runId = "ok2", loggedFatalSimExceptionCount = 0L)
		val report = aggregator.aggregate(listOf(flagged, clean))
		val md = aggregator.renderMarkdown(listOf(report))

		val section =
			md.substringAfter("## Logged FATAL Simulation Exceptions").substringBefore("## Per-Run Detail")
		assertThat(section).transform("lists the flagged run") { it.contains("| LLM_TOOL_CALLING | doomed | 2 |") }.isTrue()
		assertThat(section)
			.transform("includes the first message") { it.contains("pathToSemaphore null at time 12.5") }
			.isTrue()
		assertThat(section).transform("omits the clean run") { !it.contains("ok2") }.isTrue()
	}

	@Test
	fun `repair-success rate is marked as having no live producer, not presented as a bare measurement`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "repair1")))
		val md = aggregator.renderMarkdown(listOf(report))
		assertThat(md).transform("mentions no live producer") { it.contains("no live producer") }.isTrue()
	}

	@Test
	fun `Parameter Sweep legend distinguishes invalid-action rate from invalidOutputRate and cites #906`() {
		val report = aggregator.aggregate(listOf(snapshot(runId = "legend1")))
		val md = aggregator.renderMarkdown(listOf(report))

		assertThat(md).transform("mentions invalidOutputRate for contrast") { it.contains("invalidOutputRate") }.isTrue()
		assertThat(md).transform("mentions trainsExited ranking rationale") { it.contains("trainsExited") }.isTrue()
		assertThat(md).transform("cites issue #906") { it.contains("#906") }.isTrue()
	}

	@Test
	fun `Parameter Sweep ranks cells by trainsExited, not journeysCompleted`() {
		// "loser" has the higher journeysCompleted but the lower (real) trainsExited — #906's ruling
		// is precisely that ranking on journeysCompleted would put it first, which is the miscount
		// the ruling forbids: journeysCompleted can credit a journey to a train that never moved.
		val loser =
			snapshot(
				runId = "loser",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				temperature = 0.1,
				railwayOutcome = RailwayOutcome(journeysCompleted = 50L, trainsExited = 1L)
			)
		val winner =
			snapshot(
				runId = "winner",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				temperature = 0.9,
				railwayOutcome = RailwayOutcome(journeysCompleted = 2L, trainsExited = 10L)
			)
		val report = aggregator.aggregate(listOf(loser, winner))
		val md = aggregator.renderMarkdown(listOf(report))
		val rows = outcomesSection(md).lines().filter { it.startsWith("| ${DispatcherArm.LLM_TOOL_CALLING}") }

		assertThat(rows.size).isEqualTo(2)
		val winnerIdx = rows.indexOfFirst { it.contains("| 0.9 |") }
		val loserIdx = rows.indexOfFirst { it.contains("| 0.1 |") }
		assertThat(winnerIdx < loserIdx).isTrue()
	}

	@Test
	fun `Parameter Sweep with zero runs renders both tables without throwing`() {
		val emptyArmReport = aggregator.aggregate(emptyList())
		val md = aggregator.renderMarkdown(listOf(emptyArmReport))

		assertThat(md).transform("contains Decision Hygiene") { md.contains("### Decision Hygiene") }.isTrue()
		assertThat(md).transform("contains Railway Outcomes") { md.contains("### Railway Outcomes") }.isTrue()
		assertThat(md).transform("railway figures fall back to n/a") { outcomesSection(md).contains("n/a") }.isTrue()
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun snapshot(
		runId: String = "test-run",
		arm: DispatcherArm = DispatcherArm.RULE_BASED,
		completedNaturally: Boolean = true,
		fallback: Boolean = false,
		c7Clean: Boolean = true,
		llmSuccessRate: Double = 1.0,
		actionableTickRate: Double = llmSuccessRate,
		noOpRate: Double = 0.0,
		invalidOutputRate: Double = 0.0,
		repairSuccessRate: Double = 0.0,
		latencyP50Ms: Long? = 100L,
		latencyP95Ms: Long? = 200L,
		latencyMaxMs: Long? = 300L,
		rejections: Map<RejectionCode, Long> = emptyMap(),
		applyFailures: Map<ApplyFailureCode, Long> = emptyMap(),
		authorCounts: Map<ActionAuthor, Long> = emptyMap(),
		emittedByActionType: Map<String, Long> = emptyMap(),
		correctAt1: Double? = null,
		model: String = "",
		seed: Long? = null,
		temperature: Double = 0.0,
		inferenceTimeoutSeconds: Long = KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS,
		promptVariant: String = RunParameters.DEFAULT_PROMPT_VARIANT,
		railwayOutcome: RailwayOutcome = RailwayOutcome.UNMEASURED,
		ruleFallbackTicks: Long = 0L,
		loggedFatalSimExceptionCount: Long? = null,
		loggedFatalSimExceptionFirstMessage: String? = null
	): DispatcherRunSnapshot {
		val outcomes = TickOutcome.entries.associate { it.name to 0L }.toMutableMap()
		outcomes[TickOutcome.LLM_ACTIONS.name] = 1L
		outcomes[TickOutcome.RULE_FALLBACK.name] = ruleFallbackTicks

		return DispatcherRunSnapshot(
			runId = runId,
			arm = arm,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = temperature,
					maxActionsPerTick = 3,
					model = model,
					seed = seed,
					inferenceTimeoutSeconds = inferenceTimeoutSeconds,
					promptVariant = promptVariant
				),
			totalTicks = 1L + ruleFallbackTicks,
			ticksByOutcome = outcomes,
			timeoutNoOpByCause = TimeoutNoOpCause.entries.associate { it.name to 0L },
			llmSuccessRate = llmSuccessRate,
			actionableTickRate = actionableTickRate,
			noOpRate = noOpRate,
			invalidOutputRate = invalidOutputRate,
			repairSuccessRate = repairSuccessRate,
			emittedByActionType = emittedByActionType,
			rejectionsByCode = rejections.mapKeys { it.key.name },
			applyFailuresByCode = applyFailures.mapKeys { it.key.name },
			validAt1 = 1.0,
			correctAt1 = correctAt1,
			oracleAgreementAt1 = null,
			latencyP50Ms = latencyP50Ms,
			latencyP95Ms = latencyP95Ms,
			latencyMaxMs = latencyMaxMs,
			actionsByAuthor = authorCounts.mapKeys { it.key.name },
			unattributedApplies = 0L,
			terminalFallbackEngaged = fallback,
			terminalFallbackTickIndex = if (fallback) 5L else null,
			c7Clean = c7Clean,
			completedNaturally = completedNaturally,
			endCause = if (completedNaturally) RunEndCause.NATURAL_COMPLETION else RunEndCause.TERMINATED_EARLY,
			railwayOutcome = railwayOutcome,
			loggedFatalSimExceptionCount = loggedFatalSimExceptionCount,
			loggedFatalSimExceptionFirstMessage = loggedFatalSimExceptionFirstMessage
		)
	}

	/**
	 * Extracts the data row for [armPrefix] from a single Parameter Sweep table [section] (the
	 * text between one `###` heading and the next), split into trimmed cell values with the
	 * leading/trailing empty cells (from the outer `|`) dropped.
	 */
	private fun tableRowCells(
		section: String,
		armPrefix: String
	): List<String> {
		val line = section.lines().first { it.startsWith("| $armPrefix ") }
		return line
			.split("|")
			.drop(1)
			.dropLast(1)
			.map { it.trim() }
	}

	private fun hygieneSection(md: String): String =
		md.substringAfter("### Decision Hygiene").substringBefore("### Railway Outcomes")

	private fun outcomesSection(md: String): String = md.substringAfter("### Railway Outcomes")
}
