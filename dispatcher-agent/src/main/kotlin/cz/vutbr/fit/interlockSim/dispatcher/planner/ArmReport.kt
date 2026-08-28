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

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor

/**
 * Aggregated report for a single [DispatcherArm] across multiple runs.
 *
 * Produced by [RunReportAggregator.aggregate] from a list of [DispatcherRunSnapshot] values
 * that share the same [arm].
 *
 * ## Gate logic (A4)
 *
 * ```
 * runPassed  = completedNaturally && !terminalFallbackEngaged && c7Clean &&
 *              actionableTickRate >= RunReportAggregator.MIN_ACTIONABLE_RATE
 * gatePassed = runCount >= 10 && passingRuns >= 8 && snapshots.all { it.c7Clean }
 * ```
 *
 * A **single** non-`c7Clean` run fails the whole arm even at 10/10 completions.  C7 is a
 * deterministic-component correctness gate, not a majority vote. The `actionableTickRate`
 * threshold is Issue #927's addition — the A4 gate must be actionable-rate **and** railway
 * outcome, since a rate check alone is gameable in both directions (see
 * [RunReportAggregator.MIN_ACTIONABLE_RATE]'s KDoc for the provisional threshold value).
 *
 * @property arm Which dispatcher implementation this report covers.
 * @property params Representative [RunParameters] cell (all aggregated runs must share the same params).
 * @property runCount Total number of runs aggregated.
 * @property passingRuns Number of runs that satisfied the per-run pass predicate.
 * @property gatePassed Whether the A4 arm-level gate is satisfied.
 * @property medianLlmSuccessRate Median `llmSuccessRate` across all runs.
 * @property iqrLlmSuccessRate Interquartile range of `llmSuccessRate` across all runs.
 * @property medianActionableTickRate Median `actionableTickRate` across all runs (Issue #927).
 * @property medianNoOpRate Median `noOpRate` across all runs.
 * @property iqrNoOpRate IQR of `noOpRate` across all runs.
 * @property medianValidAt1 Median `validAt1` across all runs.
 * @property iqrValidAt1 IQR of `validAt1` across all runs.
 * @property medianCorrectAt1 Median `correctAt1` across all runs; `null` when no oracle data.
 * @property iqrCorrectAt1 IQR of `correctAt1`; `null` when no oracle data.
 * @property p95LatencyMs 95th-percentile of per-run `latencyP95Ms` values; `null` when no run in
 *   the arm measured latency (rule-based arm, or every run failed before inference started) —
 *   runs with `null` `latencyP95Ms` are skipped, not counted as `0` (absent is not zero).
 * @property allC7Clean Whether every aggregated snapshot had `c7Clean = true`.
 * @property rejectionCounts Total rejection counts per [RejectionCode] across all runs.
 * @property applyFailureCounts Total apply-failure counts per [ApplyFailureCode] across all runs.
 * @property authorCounts Total action counts per [ActionAuthor] across all runs.
 * @property snapshots The raw snapshots used to build this report (for Per-Run Detail).
 *
 * @since Issue #846 (SP2c.23 — cross-run aggregator + Markdown report + Gradle task)
 */
data class ArmReport(
	val arm: DispatcherArm,
	val params: RunParameters,
	val runCount: Int,
	val passingRuns: Int,
	val gatePassed: Boolean,
	val medianLlmSuccessRate: Double,
	val iqrLlmSuccessRate: Double,
	val medianActionableTickRate: Double,
	val medianNoOpRate: Double,
	val iqrNoOpRate: Double,
	val medianValidAt1: Double,
	val iqrValidAt1: Double,
	val medianCorrectAt1: Double?,
	val iqrCorrectAt1: Double?,
	val p95LatencyMs: Long?,
	val allC7Clean: Boolean,
	val rejectionCounts: Map<RejectionCode, Long>,
	val applyFailureCounts: Map<ApplyFailureCode, Long>,
	val authorCounts: Map<ActionAuthor, Long>,
	val snapshots: List<DispatcherRunSnapshot>
)
