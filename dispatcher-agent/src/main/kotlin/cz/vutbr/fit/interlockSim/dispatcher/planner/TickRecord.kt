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
 * Immutable record of a single dispatcher tick's [TickOutcome], reported to a
 * [PlannerTickListener].
 *
 * @param outcome What happened this tick.
 * @param simTime Simulation time (seconds) at which this tick completed.
 * @param timeoutNoOpCause The [TimeoutNoOpCause] explaining why the harness applied a safe
 *   do-nothing. Must be non-`null` if and only if [outcome] is [TickOutcome.TIMEOUT_NOOP] —
 *   enforced by an `init` invariant.
 * @param latencyMs Wall-clock duration in milliseconds of the LLM inference cycle that produced
 *   this tick, measured by [KoogAgentPlanAdapter] around the `withTimeout { decideAsync(...) }`
 *   call — see its KDoc for exactly what the measurement covers and why. `null` when this tick
 *   was not the direct result of a timed inference attempt (e.g. a tick reported by a future
 *   non-LLM planner, or any construction site that predates this field), so that "no data"
 *   stays honest rather than being reported as a `0` sample.
 *   [DefaultDispatcherRunRecorder] accumulates the non-`null` values across a run and derives
 *   [DispatcherRunSnapshot.latencyP50Ms], [DispatcherRunSnapshot.latencyP95Ms] and
 *   [DispatcherRunSnapshot.latencyMaxMs] from them.
 * @param silentNonIdleCycleIndex The correlation tick index
 *   ([cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap.currentCycle]) of this cycle when
 *   it was a silent LLM cycle on a non-idle station — the LLM emitted no decision, so the decisions
 *   posted for this cycle are the rule-based fallback's, computed on the kept cycle observation
 *   (Issue #1365). `null` for every other cycle, and when the command queue has no correlation map.
 *   [DefaultDispatcherRunRecorder] uses it to count
 *   [DispatcherRunSnapshot.staleOriginRefusalsAfterSilentCycle].
 * @param nextSectionOffers The offered and skipped NEXT SECTION hops of a cycle that skipped the
 *   rule-based fallback (Issue #1302, see [NextSectionOfferCount]). `null` on every other cycle
 *   ending, and when the count failed — see [nextSectionOffersFailed]. Travels only here — never
 *   into the cycle history, which feeds later prompts. [DefaultDispatcherRunRecorder] sums it into
 *   [DispatcherRunSnapshot.skippedNextSectionOffers] and [DispatcherRunSnapshot.offeredNextSectionHops].
 * @param nextSectionOffersFailed Whether this cycle's NEXT SECTION count was attempted and threw
 *   ([KoogAgentPlanAdapter], which logs a WARN there). `true` only in that one case: an attempt
 *   that succeeded carries [nextSectionOffers] instead, and a cycle that never attempted a count
 *   (every fallback-counting ending) stays `false` with a `null` [nextSectionOffers]
 *   — *not measured*, not failed. [DefaultDispatcherRunRecorder] counts the failed attempts into
 *   a run-level counter, and **one failure drops the run's whole offer group**
 *   ([DispatcherRunSnapshot.skippedNextSectionOffers] through
 *   [DispatcherRunSnapshot.longestSkippedOfferStreakSimSeconds]) to absent: a count the recorder
 *   cannot vouch for must not masquerade as a measured zero (PR #1408 review round). Later
 *   successful counts do not rehabilitate the run — the rule is run-level, not per-cycle.
 * @param generationCut Whether at least one `/api/chat` reply of this cycle was cut by the model
 *   profile's `num_predict` (Ollama `done_reason == "length"`, Issue #1342) — independent of
 *   [outcome]: after the emission seal a cycle whose earlier complete reply acted records
 *   [TickOutcome.LLM_ACTIONS] (or [TickOutcome.LLM_NO_OP]) and the cut still shows here, while the
 *   no-emission cut ending is the [TickOutcome.TIMEOUT_NOOP] tick with
 *   [TimeoutNoOpCause.THINKING_TRUNCATED]. True only for cut cycles —
 *   [DefaultDispatcherRunRecorder] counts them into [DispatcherRunSnapshot.generationCutCycles].
 *
 * @see PlannerTickListener
 * @see TickOutcome
 * @see DefaultDispatcherRunRecorder
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy); `latencyMs` added in Issue #834
 *   (SP2c.11 — real per-run inference latency); `nextSectionOffers` added in Issue #1302;
 *   `nextSectionOffersFailed` in the PR #1408 review round; `generationCut` in the Issue #1342
 *   review round
 */
data class TickRecord(
	val outcome: TickOutcome,
	val simTime: Double,
	val timeoutNoOpCause: TimeoutNoOpCause? = null,
	val latencyMs: Long? = null,
	val silentNonIdleCycleIndex: Long? = null,
	val nextSectionOffers: NextSectionOfferCount? = null,
	val nextSectionOffersFailed: Boolean = false,
	val generationCut: Boolean = false
) {
	init {
		val expectsCause = outcome == TickOutcome.TIMEOUT_NOOP
		val hasCause = timeoutNoOpCause != null
		require(expectsCause == hasCause) {
			"timeoutNoOpCause must be set if and only if outcome is TIMEOUT_NOOP, " +
				"but outcome=$outcome timeoutNoOpCause=$timeoutNoOpCause"
		}
	}
}
