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

/**
 * Immutable point-in-time snapshot of [DispatcherPlanner] decision-cycle metrics.
 *
 * Follows the Goal 6 [cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot] pattern: an
 * immutable value type captured on demand from the live atomic counters, independent of
 * subsequent counter updates.
 *
 * ## Metrics
 *
 * - **[outcomeCounts]** — one tick count per [TickOutcome]; the single stored breakdown every
 *   other figure here is derived from. Populated for every [TickOutcome] entry, so an outcome
 *   that never occurred reads `0` rather than `null`.
 * - **[ollamaSuccessCount]** — ticks the LLM is credited with, i.e. the sum of [outcomeCounts]
 *   over the outcomes marked "success" in the partition below.
 * - **[fallbackCount]** — every other tick: `totalCycles - ollamaSuccessCount`.
 * - **[totalCycles]** — `outcomeCounts.values.sum()`.
 * - **[ollamaSuccessRate]** — `ollamaSuccessCount / totalCycles`; `0.0` when [totalCycles] is
 *   zero (no cycles yet).
 *
 * ## Success/fallback partition (Issue #713 Task 10)
 *
 * Exactly one of "success" or "fallback" applies to every [TickOutcome]. The partition is
 * [TickOutcome.countsAsLlmSuccess] — repeated here because it is the semantic every recorded
 * `ollamaSuccessRate` figure depends on:
 *
 * | [TickOutcome] | Counts as | Why |
 * |---|---|---|
 * | [TickOutcome.LLM_ACTIONS] | success | the LLM emitted valid actions |
 * | [TickOutcome.LLM_NO_OP] | success | the LLM explicitly and correctly did nothing |
 * | [TickOutcome.LLM_REPAIRED] | success | the single repair attempt produced valid output |
 * | [TickOutcome.LLM_SILENT_NONACTIONABLE] | **fallback** | preserves the rate of every run recorded before this migration — see below |
 * | [TickOutcome.TIMEOUT_NOOP] | fallback | the harness applied a safe do-nothing |
 * | [TickOutcome.LLM_EXCEPTION] | fallback | the LLM path threw |
 * | [TickOutcome.LLM_ABANDONED] | fallback | the LLM arm was retired for the rest of the run |
 * | [TickOutcome.RULE_FALLBACK] | fallback | a deterministic planner originated the actions |
 *
 * **Why [TickOutcome.LLM_SILENT_NONACTIONABLE] is a fallback and not a success.** Before Issue
 * #713 Task 10 this snapshot was fed by the deprecated [PlannerCycleListener], whose
 * `onFallback` fired for all three [FallbackReason] values *before* the outcome split — so
 * every silent cycle on a non-idle station was counted as a fallback, including the ones
 * `KoogAgentPlanAdapter.runFallback` goes on to classify as
 * [TickOutcome.LLM_SILENT_NONACTIONABLE]. Scoring it as a success here would silently raise
 * [ollamaSuccessRate] against every run already recorded in
 * `docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md`, so it stays a fallback. Note this is deliberately
 * *not* the same question as whether the tick counts toward the actionable-rate denominator
 * ([TickOutcome.countsTowardActionableRate], where it is excluded from both numerator and
 * denominator) — that is a different metric on [DispatcherRunSnapshot].
 *
 * ## Consistency
 *
 * Only [ollamaSuccessCount] and [outcomeCounts] are stored; the remaining three figures are
 * computed, so no writer can publish a snapshot whose total disagrees with its breakdown. The
 * one remaining degree of freedom — [ollamaSuccessCount] versus the partition — is closed by an
 * `init` invariant, mirroring [DispatcherRunSnapshot]'s
 * `ticksByOutcome.values.sum() == totalTicks` check.
 *
 * @param ollamaSuccessCount Ticks credited to the LLM; must equal the [outcomeCounts] sum over
 *   the outcomes for which [TickOutcome.countsAsLlmSuccess] is `true`.
 * @param outcomeCounts Per-[TickOutcome] tick counts.
 *
 * @see MeasuringPlanAdapter
 * @see TickOutcome
 * @since Issue #817 (Goal 10 dispatcher metrics); re-keyed onto [TickOutcome] in Issue #713
 *   Task 10
 */
data class PlannerMetricsSnapshot(
	val ollamaSuccessCount: Long,
	val outcomeCounts: Map<TickOutcome, Long>
) {
	init {
		val derived = outcomeCounts.entries.sumOf { (outcome, count) -> if (outcome.countsAsLlmSuccess) count else 0L }
		require(ollamaSuccessCount == derived) {
			"ollamaSuccessCount=$ollamaSuccessCount must equal the outcomeCounts sum over " +
				"LLM-success outcomes ($derived); see the success/fallback partition on " +
				"PlannerMetricsSnapshot"
		}
	}

	/** Total dispatch cycles observed: `outcomeCounts.values.sum()`. */
	val totalCycles: Long
		get() = outcomeCounts.values.sum()

	/** Cycles not credited to the LLM: `totalCycles - ollamaSuccessCount`. */
	val fallbackCount: Long
		get() = totalCycles - ollamaSuccessCount

	/**
	 * Fraction of cycles credited to the LLM (0.0–1.0). `0.0` when [totalCycles] is zero.
	 *
	 * Not comparable across the reclassifications listed on [MeasuringPlanAdapter.logFinalSummary].
	 */
	val ollamaSuccessRate: Double
		get() = if (totalCycles > 0L) ollamaSuccessCount.toDouble() / totalCycles.toDouble() else 0.0
}
