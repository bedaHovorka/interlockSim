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
 * Immutable point-in-time snapshot of the SP2c.21 A/B-discriminator metrics and latency
 * distribution (Issue #844).
 *
 * ## Metric definitions
 *
 * | Metric | Definition |
 * |---|---|
 * | [validAt1] | `1 − invalidOutputRate`; `true` for a tick when the first LLM output parses and passes [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator] |
 * | [correctAt1] | Tick is valid **and** every applied action succeeded (no `APPLIED_THEN_FAILED` other than `ALL_PATHS_BLOCKED`) **and** the run reached natural completion with all trains exited |
 * | [oracleAgreementAt1] | Fraction of ticks where the normalised LLM action set equals `RuleBasedDispatcher.decide()` on the same observation |
 *
 * ## Honesty requirement (KDoc is mandatory — this caveat must survive refactoring)
 *
 * **[correctAt1] is an outcome-anchored executability measure with a run-level completion
 * precondition** — it is **not** a decision-correctness measure. Multiple genuinely correct
 * dispatching decisions exist at almost every tick (route choice among free paths, admission
 * ordering), so exact-match against any single oracle systematically understates competence.
 * Use [correctAt1] and [validAt1] as the A/B discriminators; use [oracleAgreementAt1] as
 * diagnostic colour only, never as a gate.
 *
 * ## Latency
 *
 * Latency is measured wall-clock via `System.nanoTime` from just before the LLM call to result
 * available.  Both `firstCallLatencyMs` (prompt-cost analysis) and total-including-repair are
 * captured per tick.  [latencyP50Ms], [latencyP95Ms], and [latencyMaxMs] are computed over
 * **all** samples without reservoir sampling (tick counts are in the tens-to-hundreds, so all
 * samples fit in memory).
 *
 * @property validTickCount  Number of ticks where the LLM output was valid (parsed + validated).
 * @property totalTickCount  Total ticks observed (denominator for rate metrics).
 * @property invalidTickCount Number of ticks where the LLM output was invalid.
 * @property correctTickCount Number of valid ticks where all applied actions also succeeded
 *   (excluding `ALL_PATHS_BLOCKED`, which is ordinary contention) and (if the run has
 *   completed) the run reached natural completion.
 * @property runCompletedNaturally Whether the owning run reached natural completion (all
 *   trains exited with no terminal fallback failure).  `null` while the run is still in
 *   progress.  [correctAt1] is `0.0` until this is `true`.
 * @property oracleAgreeTicks Number of ticks where [OracleVerdict.AGREES] was observed.
 * @property oracleCallableTicks Number of ticks where the oracle was actually called
 *   (excludes [OracleVerdict.ORACLE_UNAVAILABLE] ticks — the denominator for [oracleAgreementAt1]).
 * @property divergeUnsafeCount Number of [OracleVerdict.DIVERGES_UNSAFE] events.
 * @property latencyP50Ms p50 of per-tick first-call LLM latency in milliseconds.
 *   `Double.NaN` when no samples are available.
 * @property latencyP95Ms p95 of per-tick first-call LLM latency in milliseconds.
 *   `Double.NaN` when no samples are available.
 * @property latencyMaxMs Maximum per-tick first-call LLM latency in milliseconds.
 *   `Double.NaN` when no samples are available.
 *
 * @see Sp2c21MetricsRecorder
 * @see OracleVerdict
 * @since Issue #844 (SP2c.21 — Goal 10 latency percentiles + valid@1 / correct@1 + shadow oracle)
 */
data class Sp2c21MetricsSnapshot(
	val validTickCount: Long,
	val totalTickCount: Long,
	val invalidTickCount: Long,
	val correctTickCount: Long,
	val runCompletedNaturally: Boolean?,
	val oracleAgreeTicks: Long,
	val oracleCallableTicks: Long,
	val divergeUnsafeCount: Long,
	val latencyP50Ms: Double,
	val latencyP95Ms: Double,
	val latencyMaxMs: Double
) {
	/**
	 * Fraction of ticks where the first LLM output was valid (0.0–1.0).
	 * `0.0` when [totalTickCount] is zero.
	 */
	val validAt1: Double
		get() = if (totalTickCount > 0L) validTickCount.toDouble() / totalTickCount.toDouble() else 0.0

	/**
	 * Fraction of ticks that were valid **and** had all applied actions succeed, measured
	 * only when the run reached natural completion (all trains exited).
	 *
	 * **This is an outcome-anchored executability measure with a run-level completion
	 * precondition.** It does **not** prove decision optimality, throughput-optimality, or
	 * "the best route". Multiple genuinely correct dispatching decisions exist at almost every
	 * tick; exact-match against any single oracle systematically understates competence.
	 *
	 * Returns `0.0` when [totalTickCount] is zero **or** when [runCompletedNaturally] is not
	 * `true` (run still in progress or ended in failure).
	 */
	val correctAt1: Double
		get() {
			if (totalTickCount == 0L || runCompletedNaturally != true) return 0.0
			return correctTickCount.toDouble() / totalTickCount.toDouble()
		}

	/**
	 * Fraction of ticks where the LLM action set matched the rule-based oracle's action set
	 * (order-insensitive, normalised kind comparison).  `0.0` when no oracle-callable ticks
	 * exist.
	 *
	 * **Diagnostic colour only** — never gate on this metric. The baseline oracle is only
	 * *a* valid policy; divergence does not imply incorrectness.
	 */
	val oracleAgreementAt1: Double
		get() =
			if (oracleCallableTicks > 0L) {
				oracleAgreeTicks.toDouble() / oracleCallableTicks.toDouble()
			} else {
				0.0
			}
}
