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
 * Currently only one failure reason exists: the LLM was running but the
 * [TerminalFallbackGuard] detected that a terminal fallback (author [ActionAuthor.RULE_FALLBACK])
 * fired mid-run, indicating the LLM effectively abandoned its turn.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
enum class FailureReason {
	/**
	 * The [TerminalFallbackGuard] observed at least one tick where a [ActionAuthor.RULE_FALLBACK]
	 * action was produced — meaning the LLM emission strategy failed mid-run and a rule-based
	 * fallback took over. The loop is marked FAILED so no further LLM work proceeds.
	 */
	LLM_ABANDONED
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
 * [ActionAuthor.RULE_BASED] actions never flip the outcome to [Failed]: only
 * [ActionAuthor.RULE_FALLBACK] (terminal fallback mid-LLM-run) does. A rule-based
 * determinism run (P10 gate, SP2c.5) where every action has author [ActionAuthor.RULE_BASED]
 * therefore stays [Running] for the entire simulation — by construction, not by accident.
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
