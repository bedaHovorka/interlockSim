/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

/**
 * Machine-readable reason for a [RunOutcome.Failed] outcome (SP2c.5, Issue #828).
 *
 * Two failure reasons exist:
 * - [LLM_ABANDONED] — the LLM was absent for [TerminalFallbackGuard.threshold] consecutive
 *   ticks, or an unhandled emission exception was caught, or a [ActionAuthor.RULE_FALLBACK]
 *   action co-appeared with a [ActionAuthor.SAFETY_NET] action in the same tick record
 *   (RULE_FALLBACK priority in the both-appear case).
 * - [SAFETY_NET_ENGAGED] — a [ActionAuthor.SAFETY_NET] action was observed in a tick record,
 *   indicating a forced-admission safety-net mechanism that should be extinct after SP2c.8
 *   resurfaced.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
enum class FailureReason {
	/**
	 * The LLM emission strategy was judged to have abandoned the run. Engaged by
	 * [TerminalFallbackGuard] on any of three triggers:
	 *
	 * 1. [ActionAuthor.TIMEOUT_NOOP] actions occur for [TerminalFallbackGuard.threshold]
	 *    consecutive ticks (counter saturation — the LLM stopped emitting actionable output).
	 * 2. An unhandled emission exception propagates out of
	 *    [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop.runEmission], caught only when a
	 *    fallback emission is configured (SP2c.8); [TerminalFallbackGuard.engageImmediately]
	 *    then flips the outcome.
	 * 3. A [ActionAuthor.RULE_FALLBACK] action co-appears with a [ActionAuthor.SAFETY_NET]
	 *    action in the same tick record — RULE_FALLBACK takes priority over
	 *    [SAFETY_NET_ENGAGED] in the both-appear case.
	 *
	 * Note that [ActionAuthor.RULE_FALLBACK] alone does **not** engage the guard: it is a
	 * post-engagement author (set on the fallback emission strategy after the guard has already
	 * engaged) and resets the TIMEOUT_NOOP counter rather than tripping it. The loop is marked
	 * FAILED so no further LLM work proceeds.
	 */
	LLM_ABANDONED,

	/**
	 * The [TerminalFallbackGuard] observed at least one tick where a [ActionAuthor.SAFETY_NET]
	 * action was produced — meaning a forced-admission safety-net mechanism fired. This should
	 * be extinct after SP2c.8; this reason code makes its reappearance detectable in run-end
	 * attribution summaries.
	 *
	 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
	 */
	SAFETY_NET_ENGAGED
}

/**
 * Lifecycle outcome of a [DispatchTickLoop] run (SP2c.5, Issue #828).
 *
 * [DispatchTickLoop.runOutcome] starts as [Running] and transitions at most once (to
 * [Completed] or [Failed]) over the course of the simulation:
 *
 * - [Running] — the loop is healthy and operating normally.
 * - [Completed] — the simulation finished successfully (all trains exited, no failures).
 * - [Failed] — an unrecoverable failure occurred (see [FailureReason]).
 *
 * The outcome is updated by [TerminalFallbackGuard] inside [DispatchTickLoop.runTick];
 * callers of the loop (e.g. the `agentDriverAction` in
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]) may inspect it after each tick to decide
 * whether to continue.
 *
 * ## C7 gate protection
 *
 * [ActionAuthor.RULE_BASED] actions never flip the outcome to [Failed]: a rule-based
 * determinism run (P10 gate, SP2c.5) where every action has author [ActionAuthor.RULE_BASED]
 * therefore stays [Running] for the entire simulation — by construction, not by accident.
 * [ActionAuthor.RULE_FALLBACK] is a post-engagement author (set on the fallback emission
 * strategy after the guard has engaged) and does not by itself flip the outcome; the outcome
 * is flipped by the [TerminalFallbackGuard]'s three triggers (consecutive TIMEOUT_NOOP,
 * unhandled emission exception, or a [ActionAuthor.SAFETY_NET] action).
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
sealed interface RunOutcome {
	/** The loop is operating normally; no terminal failure has been observed. */
	data object Running : RunOutcome

	/** The simulation completed successfully. */
	data object Completed : RunOutcome

	/**
	 * An unrecoverable failure was detected by [TerminalFallbackGuard].
	 *
	 * @property reason Machine-readable classification of why the run failed.
	 */
	data class Failed(
		val reason: FailureReason
	) : RunOutcome
}
