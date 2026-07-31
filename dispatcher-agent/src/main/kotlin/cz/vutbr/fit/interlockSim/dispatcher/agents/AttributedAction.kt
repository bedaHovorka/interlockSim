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
 * Attribution tag for a [DispatchAction] emitted by [DispatchTickLoop] (SP2c.5, Issue #828).
 *
 * Distinguishes the originating decision-maker so that [TerminalFallbackGuard] can correctly
 * classify a tick as rule-based (acceptable) vs. terminal fallback (run FAILED) and
 * [RunOutcome] is never marked FAILED for a determinism run that is deliberately rule-based
 * all the way through (the P10 gate).
 *
 * | Value | Meaning |
 * |---|---|
 * | [LLM] | Action produced by the LLM emission strategy during a normal inference cycle. |
 * | [TIMEOUT_NOOP] | Budget deadline expired; the loop substituted [DispatchAction.NoOp] automatically. |
 * | [RULE_BASED] | A [RuleBasedEmissionStrategy] run — rule-based all the way, intentionally so. |
 * | [RULE_FALLBACK] | Terminal fallback fired **mid-LLM-run** — the LLM failed and rules took over. Sets [RunOutcome.Failed]. |
 * | [OPERATOR] | Human operator override (reserved for future interactive use). |
 *
 * **Why [RULE_BASED] ≠ [RULE_FALLBACK]:** conflating them would mark every rule-based
 * determinism run (the P10 gate, SP2c.5 acceptance criterion) as FAILED — exactly the bug
 * the [TerminalFallbackGuard] / [RunOutcome.Failed] combination must avoid.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
enum class ActionAuthor {
	/** Action produced by the LLM emission strategy during a normal inference cycle. */
	LLM,

	/** Budget deadline expired; the loop substituted [DispatchAction.NoOp] automatically. */
	TIMEOUT_NOOP,

	/**
	 * Action produced by [RuleBasedEmissionStrategy] — rule-based all the way, intentionally so.
	 * **Not** a fallback; a run where all actions have this author stays [RunOutcome.Running].
	 */
	RULE_BASED,

	/**
	 * Terminal fallback engaged **mid-LLM-run**: the LLM failed and a rule-based strategy
	 * took over. Sets [RunOutcome.Failed] via [TerminalFallbackGuard].
	 */
	RULE_FALLBACK,

	/** Human operator override. Reserved for future interactive use. */
	OPERATOR
}

/**
 * A [DispatchAction] emitted this tick, tagged with the [CommandId] issued when it was posted
 * to the actuator queue, so it can be tracked in [WorkingMemory.pendingRequests] until the
 * matching [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome] arrives or the
 * entry expires by TTL (SP2c.7, Issue #830).
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
 * [cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap.register] issued at post time
 * (SP2c.17, Issue #840).
 *
 * ## Why [tick] is a separate field
 *
 * Unlike the superseded `CorrelationId` value class this type replaces, [CommandId] is an opaque
 * monotonic counter with no embedded tick — it is assigned by
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
 *
 * @since Issue #830 (SP2c.7 — Goal 10 ring buffer)
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
	 *
	 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
	 */
	val author: ActionAuthor = ActionAuthor.LLM
)

/** Extracts the trainId from a [DispatchAction], or `null` for [DispatchAction.NoOp]. */
internal fun trainIdOf(action: DispatchAction): String? =
	when (action) {
		is DispatchAction.ApproveTrain -> action.trainId
		is DispatchAction.RequestRoute -> action.trainId
		is DispatchAction.CancelRoute -> action.trainId
		DispatchAction.NoOp -> null
	}
