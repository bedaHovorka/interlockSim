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

import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation

/**
 * Pluggable "decide" seam for [DispatchTickLoop] (SP2c.5, Issue #828).
 *
 * An [EmissionStrategy] converts a rendered prompt string and the current
 * [DispatcherObservation] into a list of [AttributedAction]s to execute this tick.
 * Returning `null` is equivalent to "no decision" — the caller ([DispatchTickLoop]) will
 * substitute a single [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] with author
 * [ActionAuthor.TIMEOUT_NOOP]. Returning an **empty list** is "the strategy ran and decided there
 * is nothing to do this tick" — the loop substitutes a single [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
 * authored by [author] (the strategy's own author, not [ActionAuthor.TIMEOUT_NOOP]), so an idle
 * tick from the rule engine or an explicit-`no_op` LLM tick is recorded as a deliberate decision,
 * not a degraded timeout (SP2c.19 / #829).
 *
 * ## Implementations
 *
 * | Implementation | Description |
 * |---|---|
 * | [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy] | Ignores `prompt`; delegates to [cz.vutbr.fit.interlockSim.sim.Dispatcher]. Author is [ActionAuthor.RULE_BASED]. |
 * | LLM strategy (future SP2c steps) | Sends `prompt` to the model; maps JSON tool calls to [AttributedAction]s. Author is [ActionAuthor.LLM] or [ActionAuthor.RULE_FALLBACK]. |
 *
 * ## Return contract
 *
 * - `null` — deadline / timeout (no inference completed); the loop substitutes [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] authored [ActionAuthor.TIMEOUT_NOOP].
 * - Empty list — the strategy ran and decided there is nothing to do this tick; the loop substitutes [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] authored by [author].
 * - Non-empty list — actions to validate and execute (at most [DispatchTickLoop.maxActionsPerTick] will be applied).
 *
 * ## Threading
 *
 * [emit] is called from the agent driver coroutine (off the kDisco sim thread) and may
 * `suspend` while waiting for a remote LLM response. Rule-based implementations return
 * immediately without suspending.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
fun interface EmissionStrategy {
	/**
	 * The author the loop should attribute an **idle-substituted** [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
	 * to when this strategy returns an empty list (ran and decided there is nothing to do).
	 *
	 * Defaults to [ActionAuthor.RULE_BASED]; a future LLM strategy overrides it with
	 * [ActionAuthor.LLM] so its explicit `no_op`/idle ticks record as LLM-originated, not rule-based.
	 * This is **not** used for the `null` (deadline/timeout) substitution, which is always
	 * [ActionAuthor.TIMEOUT_NOOP].
	 */
	val author: ActionAuthor
		get() = ActionAuthor.RULE_BASED

	/**
	 * Produces [AttributedAction]s for this tick.
	 *
	 * @param prompt Rendered prompt string (from [ObservationRenderer.render]). Rule-based
	 *   strategies may ignore this.
	 * @param observation The current [DispatcherObservation] at the start of this tick.
	 *   Rule-based strategies use this directly; LLM strategies use it for context / fallback.
	 * @return A list of [AttributedAction]s to execute, an empty list if nothing to do, or
	 *   `null` on deadline/timeout. The caller substitutes [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
	 *   when `null` is returned (author [ActionAuthor.TIMEOUT_NOOP]) or when the list is empty
	 *   (author [author]).
	 */
	suspend fun emit(
		prompt: String,
		observation: DispatcherObservation
	): List<AttributedAction>?
}
