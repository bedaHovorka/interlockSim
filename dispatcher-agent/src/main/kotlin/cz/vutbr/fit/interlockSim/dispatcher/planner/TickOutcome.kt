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

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor

/**
 * Exhaustive taxonomy of what happened on a single dispatcher tick, replacing the legacy
 * two-way split ([PlannerCycleListener.onLlmSuccess] / [FallbackReason]) that could not tell
 * "the LLM correctly decided nothing was needed" apart from "the LLM failed to produce
 * anything" — both were counted as `FallbackReason.EMPTY_NO_TOOLS`.
 *
 * On `vyhybna.xml`, a high [LLM_NO_OP] rate is expected and healthy: most ticks genuinely
 * require no intervention, and constraint C6 makes an explicit `no_op` a first-class,
 * frequently-correct action so the model stops inventing unnecessary work. Distinguishing that
 * from a dead model ([TIMEOUT_NOOP]) is the entire point of this taxonomy.
 *
 * [LLM_NO_OP] is produced on the live path by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.plan]
 * in two cases (Issue #834): an idle station (no active or queued trains) with no LLM emissions,
 * and a cycle whose only tool emission(s) were an explicit `no_op`. Before Issue #834 both cases
 * were mis-scored as [RULE_FALLBACK] via the undifferentiated `FallbackReason.EMPTY_NO_TOOLS`
 * path, which is why `noOpRate` read `0` in every run measured before that fix.
 *
 * | Outcome | Meaning | [tickClass] | Counts as LLM success |
 * |---|---|---|---|
 * | [LLM_ACTIONS] | ≥ 1 valid action emitted and accepted by the action validator | [TickClass.SUCCESS] | yes |
 * | [LLM_NO_OP] | LLM **explicitly** emitted `no_op` | [TickClass.SUCCESS] | yes |
 * | [LLM_REPAIRED] | First output invalid; the single repair attempt produced valid output | [TickClass.SUCCESS] | yes (also `repairSuccessCount`) |
 * | [TIMEOUT_NOOP] | Safe do-nothing applied by the harness; carries a [TimeoutNoOpCause] | [TickClass.DEGRADED] | no |
 * | [LLM_EXCEPTION] | Non-cancellation throwable from the LLM path | [TickClass.DEGRADED] | no |
 * | [LLM_ABANDONED] | Terminal fallback engaged; LLM arm retired for the rest of the run | [TickClass.RUN_FAILURE] | no |
 * | [RULE_FALLBACK] | A deterministic planner originated the dispatching actions this tick | [TickClass.RUN_FAILURE] | no |
 *
 * ## Migration from [FallbackReason]
 *
 * `FallbackReason` predates this split and cannot express it on its own — see
 * `FallbackReason.toTickOutcome` for the documented, lossy projection used to replay legacy
 * data (or legacy call sites not yet migrated) onto this taxonomy.
 *
 * @see TickClass
 * @see TimeoutNoOpCause
 * @see TickRecord
 * @see PlannerTickListener
 * @see FallbackReason
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
enum class TickOutcome {
	/** ≥ 1 valid action emitted and accepted by the action validator. */
	LLM_ACTIONS,

	/** LLM **explicitly** emitted `no_op` — a correct, healthy outcome, not a fallback. */
	LLM_NO_OP,

	/** First output invalid; the single repair attempt produced valid output. */
	LLM_REPAIRED,

	/**
	 * Safe do-nothing applied by the harness because the LLM did not produce a usable result
	 * this tick. Always carried together with a [TimeoutNoOpCause] on [TickRecord].
	 */
	TIMEOUT_NOOP,

	/**
	 * Non-cancellation throwable from the LLM path (network error, invalid tool call, etc.).
	 *
	 * **Not currently emitted by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]**
	 * — that adapter maps its generic-exception catch block to [RULE_FALLBACK] (the fallback
	 * dispatcher's decisions are actually posted, so they must be attributed as a fallback,
	 * not a no-op). This outcome is reserved for future producers and is reachable today only
	 * via the legacy `FallbackReason.toTickOutcome` projection. Its [toActionAuthor] mapping to
	 * [ActionAuthor.TIMEOUT_NOOP] is correct for attribution because, like [TIMEOUT_NOOP], it is
	 * a no-dispatching-action degraded outcome — but no live producer pairs it with dispatching
	 * actions, so the mapping is never exercised against a real tick (Issue #843 review).
	 */
	LLM_EXCEPTION,

	/** Terminal fallback engaged; the LLM arm is retired for the rest of the run. */
	LLM_ABANDONED,

	/** A deterministic planner originated the dispatching actions this tick. */
	RULE_FALLBACK
}

/**
 * Coarse [TickClass] severity bucket for this [TickOutcome]. See the class-level table on
 * [TickOutcome] for the full mapping.
 */
val TickOutcome.tickClass: TickClass
	get() =
		when (this) {
			TickOutcome.LLM_ACTIONS, TickOutcome.LLM_NO_OP, TickOutcome.LLM_REPAIRED -> TickClass.SUCCESS
			TickOutcome.TIMEOUT_NOOP, TickOutcome.LLM_EXCEPTION -> TickClass.DEGRADED
			TickOutcome.LLM_ABANDONED, TickOutcome.RULE_FALLBACK -> TickClass.RUN_FAILURE
		}

/**
 * Whether this [TickOutcome] counts as an LLM success for reliability metrics (e.g. the
 * `ollamaSuccessCount` counter in `MeasuringPlanAdapter`). See the class-level table on
 * [TickOutcome] for the full mapping.
 */
val TickOutcome.countsAsLlmSuccess: Boolean
	get() = tickClass == TickClass.SUCCESS

/**
 * [ActionAuthor] that should be attributed to every decision posted this tick, given this
 * [TickOutcome].
 *
 * Used by [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] to correctly attribute a
 * live LLM-planner cycle's `commandQueue.postAll` call instead of the pre-SP2c.20-follow-up
 * default of always tagging it [ActionAuthor.LLM] (SP2c.20 follow-up, Issue #843).
 *
 * - LLM-success outcomes ([TickOutcome.LLM_ACTIONS], [TickOutcome.LLM_NO_OP],
 *   [TickOutcome.LLM_REPAIRED]) → [ActionAuthor.LLM].
 * - No-dispatching-action outcomes ([TickOutcome.TIMEOUT_NOOP], [TickOutcome.LLM_EXCEPTION]) →
 *   [ActionAuthor.TIMEOUT_NOOP].
 * - Deterministic-fallback outcomes ([TickOutcome.LLM_ABANDONED], [TickOutcome.RULE_FALLBACK]) →
 *   [ActionAuthor.RULE_FALLBACK] — the fallback dispatcher's decisions were actually posted.
 */
val TickOutcome.toActionAuthor: ActionAuthor
	get() =
		when (this) {
			TickOutcome.LLM_ACTIONS, TickOutcome.LLM_NO_OP, TickOutcome.LLM_REPAIRED -> ActionAuthor.LLM
			TickOutcome.TIMEOUT_NOOP, TickOutcome.LLM_EXCEPTION -> ActionAuthor.TIMEOUT_NOOP
			TickOutcome.LLM_ABANDONED, TickOutcome.RULE_FALLBACK -> ActionAuthor.RULE_FALLBACK
		}
