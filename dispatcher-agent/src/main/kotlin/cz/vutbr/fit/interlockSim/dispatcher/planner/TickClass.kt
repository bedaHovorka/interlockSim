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
 * Coarse severity bucket for a [TickOutcome].
 *
 * Where [TickOutcome] answers "what exactly happened this tick", [TickClass] answers
 * "how worried should an operator watching the run be" — the three buckets a dashboard or
 * alerting rule actually needs:
 *
 * - [SUCCESS]: the tick produced a correct outcome (an action, an explicit no-op, or a
 *   successful single-shot repair). Nothing to look at.
 * - [DEGRADED]: the LLM arm did not produce a usable result this tick, but the run is still
 *   healthy overall — a safe do-nothing was applied and the LLM arm remains active for the
 *   next tick.
 * - [RUN_FAILURE]: the LLM arm is no longer contributing to this tick's decision — either it
 *   has been permanently retired for the run ([TickOutcome.LLM_ABANDONED]), or a deterministic
 *   planner produced the dispatching actions instead ([TickOutcome.RULE_FALLBACK]).
 *
 * @see TickOutcome
 * @see TickOutcome.tickClass
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
enum class TickClass {
	SUCCESS,
	DEGRADED,
	RUN_FAILURE
}
