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
 * - **[ollamaSuccessCount]** — dispatch cycles where the LLM produced a usable result
 *   (non-empty decision list, or at least one actuator-tool invocation).
 * - **[fallbackCount]** — dispatch cycles where the rule-based fallback was invoked in total.
 * - **[fallbacksByReason]** — [fallbackCount] broken down by [FallbackReason] category.
 * - **[totalCycles]** — `ollamaSuccessCount + fallbackCount`.
 * - **[ollamaSuccessRate]** — fraction of cycles where the LLM succeeded:
 *   `ollamaSuccessCount / totalCycles`.  `0.0` when [totalCycles] is zero (no cycles yet).
 *
 * @param ollamaSuccessCount Number of cycles where Ollama produced a usable result.
 * @param fallbackCount Number of cycles where the rule-based fallback was invoked.
 * @param fallbacksByReason Per-[FallbackReason] breakdown of [fallbackCount].
 * @param totalCycles `ollamaSuccessCount + fallbackCount`.
 * @param ollamaSuccessRate Fraction of cycles where Ollama succeeded (0.0–1.0).
 *
 * @see MeasuringPlanAdapter
 * @see FallbackReason
 * @since Issue #817 (Goal 10 dispatcher metrics)
 */
data class PlannerMetricsSnapshot(
	val ollamaSuccessCount: Long,
	val fallbackCount: Long,
	val fallbacksByReason: Map<FallbackReason, Long>,
	val totalCycles: Long,
	val ollamaSuccessRate: Double,
)
