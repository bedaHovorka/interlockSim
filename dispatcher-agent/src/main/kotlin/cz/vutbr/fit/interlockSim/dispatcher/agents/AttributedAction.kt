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

import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction

/**
 * Attribution tag for a [DispatchAction] emitted by [DispatchTickLoop].
 *
 * Distinguishes the originating decision-maker so that the metrics and reporting layer can
 * correctly classify each tick. [TerminalFallbackGuard] counts [TIMEOUT_NOOP] ticks rather
 * than watching for [RULE_FALLBACK] — [RunOutcome.Failed] is triggered by the LLM being
 * *absent* (timeouts/exceptions), not by the fallback emitting. [RunOutcome] is never marked
 * FAILED for a determinism run that is deliberately rule-based all the way through (the P10
 * gate). [DispatcherPreferenceStore] persists a per-run author-count summary to answer "who
 * decided this?" for any dispatched action.
 *
 * | Value | Meaning |
 * |---|---|
 * | [LLM] | Action produced by the LLM emission strategy during a normal inference cycle. |
 * | [TIMEOUT_NOOP] | Budget deadline expired; the loop substituted [DispatchAction.NoOp] automatically. |
 * | [RULE_BASED] | A [RuleBasedEmissionStrategy] run — rule-based all the way, intentionally so. |
 * | [RULE_FALLBACK] | Post-engagement rule-based action: [TerminalFallbackGuard] has already marked the run FAILED; the rule engine runs to bring the simulation to a terminating state. Does **not** engage the guard on its own. |
 * | [SAFETY_NET] | Forced admission — should be extinct; retained so its reappearance is detectable. Engages [TerminalFallbackGuard] immediately with [FailureReason.SAFETY_NET_ENGAGED]. |
 * | [OPERATOR] | Human operator override (reserved for future interactive use). |
 *
 * **Why [RULE_BASED] ≠ [RULE_FALLBACK]:** conflating them would mark every rule-based
 * determinism run (the P10 gate) as FAILED — exactly the bug the [TerminalFallbackGuard] /
 * [RunOutcome.Failed] combination must avoid.
 */
enum class ActionAuthor {
	/** Action produced by the LLM emission strategy during a normal inference cycle. */
	LLM,

	/**
	 * Budget deadline expired; the loop substituted [DispatchAction.NoOp] automatically.
	 * Always paired with [DispatchAction.NoOp] — provably originates zero dispatching actions.
	 */
	TIMEOUT_NOOP,

	/**
	 * Action produced by [RuleBasedEmissionStrategy] — rule-based all the way, intentionally so.
	 * **Not** a fallback; a run where all actions have this author stays [RunOutcome.Running].
	 */
	RULE_BASED,

	/**
	 * Post-engagement action produced by the fallback emission strategy after
	 * [TerminalFallbackGuard] has engaged and marked the run [RunOutcome.Failed].
	 *
	 * The run is already failed; the rule engine runs only so the simulation reaches a
	 * terminating state and artefacts are inspectable. This author is set by constructing a
	 * [RuleBasedEmissionStrategy] with `overrideAuthor = RULE_FALLBACK`.
	 * A `RULE_FALLBACK` action observed while the guard is *not* yet engaged does not by
	 * itself engage the guard — it is a post-engagement author, not a trigger. It only
	 * influences the engagement reason when [SAFETY_NET] also appears in the same tick
	 * record (RULE_FALLBACK priority — see [TerminalFallbackGuard]).
	 */
	RULE_FALLBACK,

	/**
	 * Forced admission by a safety-net mechanism. Should be extinct;
	 * retained as a detectable author so its reappearance sets [RunOutcome.Failed]
	 * with [FailureReason.SAFETY_NET_ENGAGED] via [TerminalFallbackGuard].
	 */
	SAFETY_NET,

	/** Human operator approval via the SEMI_AUTO gateway. Legal, but disqualifies the run from the autonomous bar. */
	OPERATOR
}

/**
 * A [DispatchAction] emitted this tick, tagged with the [CommandId] issued when it was posted
 * to the actuator queue, so it can be tracked in [WorkingMemory.pendingRequests] until the
 * matching [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome] arrives or the
 * entry expires by TTL.
 *
 * ## Purpose
 *
 * The dispatcher control loop emits `DispatchAction` values toward the actuator. Between emission
 * and confirmation there is an asynchronous gap: the action is translated into a
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision], posted to
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue], drained on the sim thread, and only
 * then does an `AppliedOutcome` arrive in the next (or a later) tick's
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation]. [AttributedAction] is
 * the **before** half; [commandId] is the link that lets [WorkingMemory.update] match the
 * **after** half — [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.id] always
 * carries the same [CommandId] that
 * [cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap.register] issued at post time.
 *
 * ## Why [tick] is a separate field
 *
 * [CommandId] is an opaque monotonic counter with no embedded tick — it is assigned by
 * [cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap] and never surfaced back to the
 * poster except via the eventual [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome].
 * [WorkingMemory]'s TTL bookkeeping needs to know the tick a pending entry was *added* on
 * (independent of whether/when an outcome ever arrives), so [tick] is carried alongside
 * [commandId] explicitly rather than derived from it.
 *
 * ## [DispatchAction.NoOp]
 *
 * A `no_op` action produces no actuator side-effect and therefore never generates an
 * `AppliedOutcome`. [WorkingMemory.update] skips `no_op` entries when adding to
 * [WorkingMemory.pendingRequests] to avoid spurious pending-request leaks.
 *
 * @property commandId Correlation key for this action instance, assigned at actuator-queue
 *   post time.
 * @property tick Dispatcher tick this action was recorded on (used for TTL expiry).
 * @property action The action that was emitted to the actuator.
 */
data class AttributedAction(
	val commandId: CommandId,
	val tick: Long,
	val action: DispatchAction,
	/**
	 * Attribution tag identifying the decision-maker that produced this action.
	 *
	 * Defaults to [ActionAuthor.LLM] for backwards-compatibility with existing code that
	 * creates [AttributedAction] instances without explicitly specifying an author. New code
	 * (particularly [DispatchTickLoop] and [RuleBasedEmissionStrategy]) should always pass an
	 * explicit value.
	 */
	val author: ActionAuthor = ActionAuthor.LLM,
	/**
	 * Human-readable reason explaining why this action was chosen by [author].
	 *
	 * Defaults to an empty string for backwards-compatibility. Production paths should supply
	 * a meaningful reason so that [DispatcherPreferenceStore] and B2's "why this route?" query
	 * can answer "who decided this and why?".
	 */
	val reason: String = ""
)

/** Extracts the trainId from a [DispatchAction], or `null` for [DispatchAction.NoOp]. */
internal fun trainIdOf(action: DispatchAction): String? =
	when (action) {
		is DispatchAction.ApproveTrain -> action.trainId
		is DispatchAction.RequestRoute -> action.trainId
		is DispatchAction.CancelRoute -> action.trainId
		DispatchAction.NoOp -> null
	}
