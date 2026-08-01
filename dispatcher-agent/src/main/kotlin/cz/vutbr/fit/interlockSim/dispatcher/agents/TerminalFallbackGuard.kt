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

import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome.Failed
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome.Running

/**
 * Monitors dispatcher ticks and engages a terminal fallback when the LLM has been absent
 * for [threshold] consecutive ticks (SP2c.8, Issue #831), or immediately on a detectable
 * safety-net reappearance (SP2c.9, Issue #832).
 *
 * ## Three engagement triggers
 *
 * | # | Trigger | Reason | Source |
 * |---|---|---|---|
 * | 1 | [ActionAuthor.TIMEOUT_NOOP] for [threshold] consecutive ticks | [FailureReason.LLM_ABANDONED] | counter in [observe] |
 * | 2 | Unhandled exception from [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy.emit] (via [engageImmediately]) | [FailureReason.LLM_ABANDONED] | [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop.runEmission] |
 * | 3 | Any [ActionAuthor.SAFETY_NET] action observed in a tick | [FailureReason.SAFETY_NET_ENGAGED] | [observe] |
 *
 * ## Counter semantics (trigger 1)
 *
 * | Tick outcome | Counter |
 * |---|---|
 * | ≥ 1 action emitted (any author except [ActionAuthor.TIMEOUT_NOOP]) | **reset to 0** |
 * | All emitted actions rejected — LLM produced valid output, being wrong is not abandonment | **reset to 0** |
 * | Deadline miss → [ActionAuthor.TIMEOUT_NOOP] substituted | +1 |
 * | Unhandled exception from `emission.emit` (via [engageImmediately]) | **immediate engage** |
 *
 * ## Why a no_op authored LLM resets the counter
 *
 * A correctly idle station — one where the LLM correctly decides nothing is needed — emits
 * `no_op` with author [ActionAuthor.LLM]. If that did not reset the counter, a healthy
 * idle station would trip the guard after five ticks and every healthy run would be marked
 * FAILED. The counter measures **LLM absence** (timeout/exception), not LLM idleness.
 *
 * ## Why [ActionAuthor.RULE_BASED] does not trigger failure
 *
 * A run where all actions carry author [ActionAuthor.RULE_BASED] is a deliberate rule-based
 * run (e.g. the P10 gate determinism test). [ActionAuthor.RULE_BASED] actions reset the
 * counter (they are not TIMEOUT_NOOP), so such a run stays [Running] for the entire
 * simulation — by construction, not by accident.
 *
 * ## Why [ActionAuthor.RULE_FALLBACK] does not trigger engagement
 *
 * [ActionAuthor.RULE_FALLBACK] is a **post-engagement** author: it is set by constructing a
 * [RuleBasedEmissionStrategy] with `overrideAuthor = RULE_FALLBACK` **after** the guard has
 * already engaged (typically via [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]'s
 * `fallbackEmission` swap). It marks actions emitted while the run is already FAILED so the
 * simulation can still reach a terminating, inspectable state. A [RULE_FALLBACK] action
 * observed while the guard is *not* yet engaged is a configuration anomaly, not a failure
 * trigger — engaging on it would make the post-engagement fallback indistinguishable from
 * the trigger that started it. [RULE_FALLBACK] therefore resets the counter (it is not
 * TIMEOUT_NOOP) and never engages the guard on its own.
 *
 * The one exception is the **both-appear** case: if a single tick record contains both
 * [ActionAuthor.SAFETY_NET] and [ActionAuthor.RULE_FALLBACK], the SAFETY_NET trigger fires
 * (engagement is mandatory) and [RULE_FALLBACK] takes priority for the reason — the
 * engagement is reported as [FailureReason.LLM_ABANDONED] rather than
 * [FailureReason.SAFETY_NET_ENGAGED]. This preserves the priority ordering from SP2c.9.
 *
 * ## Why [ActionAuthor.SAFETY_NET] engages immediately
 *
 * [ActionAuthor.SAFETY_NET] should be extinct after SP2c.8 (the admission safety net was
 * deleted in favour of the counter-based terminal fallback). Its reappearance in a tick
 * record means a forced-admission path resurfaced — a regression that must be detectable.
 * The guard therefore engages immediately (bypassing the counter) with reason
 * [FailureReason.SAFETY_NET_ENGAGED], so the run-end attribution summary flags it.
 *
 * ## Usage
 *
 * ```kotlin
 * val guard = TerminalFallbackGuard(threshold = 5) { reason ->
 *     // swap emission strategy to RuleBasedEmissionStrategy with RULE_FALLBACK author
 * }
 * // inside DispatchTickLoop.runTick():
 * guard.observe(tickRecord)
 * // on exception from emission.emit:
 * guard.engageImmediately()
 * // after the tick:
 * val outcome: RunOutcome = guard.currentOutcome
 * ```
 *
 * The guard is single-use: once [engaged] is `true` it never reverts.
 *
 * @param threshold Number of consecutive [ActionAuthor.TIMEOUT_NOOP] ticks before engagement.
 *   Defaults to 5 (~15 s at ~3 s/tick). Must be ≥ 1.
 * @param onAbandoned Callback invoked exactly once when the guard engages, with the
 *   [FailureReason] ([FailureReason.LLM_ABANDONED] for triggers 1 and 2,
 *   [FailureReason.SAFETY_NET_ENGAGED] for trigger 3 — or [FailureReason.LLM_ABANDONED] when
 *   [ActionAuthor.RULE_FALLBACK] takes priority in the both-appear case). Runs synchronously
 *   on the calling thread (the driver coroutine). Use it to swap the
 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy]
 *   in [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop].
 *
 * @since Issue #831 (SP2c.8 — Goal 10 terminal fallback guard redesign);
 *   SAFETY_NET trigger added in Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
 */
class TerminalFallbackGuard(
	private val threshold: Int = 5,
	private val onAbandoned: (FailureReason) -> Unit = {}
) {
	init {
		require(threshold >= 1) { "threshold must be >= 1, got $threshold" }
	}

	private var consecutiveTimeouts: Int = 0

	@Volatile
	private var _engaged: Boolean = false

	@Volatile
	private var _reason: FailureReason? = null

	/**
	 * Whether the guard has engaged (any of the three triggers has fired).
	 *
	 * Once `true`, never reverts to `false`.
	 */
	val engaged: Boolean get() = _engaged

	/**
	 * The current [RunOutcome] as observed so far.
	 *
	 * Returns [Failed] with the engagement reason once [engaged] is `true`;
	 * [Running] otherwise. Never reverts after [engaged] becomes `true`.
	 */
	val currentOutcome: RunOutcome
		get() = if (_engaged) Failed(_reason ?: FailureReason.LLM_ABANDONED) else Running

	/**
	 * Processes a completed [TickRecord].
	 *
	 * Triggers, in priority order:
	 *
	 * 1. **SAFETY_NET** (immediate engage) — if any action has author
	 *    [ActionAuthor.SAFETY_NET], engage immediately with reason
	 *    [FailureReason.SAFETY_NET_ENGAGED]. If the same record also contains
	 *    [ActionAuthor.RULE_FALLBACK], the reason is [FailureReason.LLM_ABANDONED] instead
	 *    (RULE_FALLBACK priority in the both-appear case).
	 * 2. **Counter** — otherwise, if any action has author [ActionAuthor.TIMEOUT_NOOP],
	 *    increment the consecutive-timeout counter; engage with
	 *    [FailureReason.LLM_ABANDONED] when it reaches [threshold].
	 * 3. **Reset** — otherwise (any non-TIMEOUT_NOOP author, including [ActionAuthor.LLM]
	 *    `no_op`, [ActionAuthor.RULE_BASED], or [ActionAuthor.RULE_FALLBACK]) reset the
	 *    counter to 0.
	 *
	 * [ActionAuthor.RULE_FALLBACK] alone never engages the guard — see the class KDoc.
	 *
	 * No-op once [engaged] is `true`.
	 *
	 * @param record The completed tick record to inspect.
	 */
	fun observe(record: TickRecord) {
		if (_engaged) return
		val hasSafetyNet = record.actions.any { it.author == ActionAuthor.SAFETY_NET }
		if (hasSafetyNet) {
			// SAFETY_NET is an immediate-engage trigger (SP2c.9). RULE_FALLBACK priority:
			// when both appear in the same record, the engagement reason is LLM_ABANDONED.
			val hasFallback = record.actions.any { it.author == ActionAuthor.RULE_FALLBACK }
			engage(if (hasFallback) FailureReason.LLM_ABANDONED else FailureReason.SAFETY_NET_ENGAGED)
			return
		}
		// RULE_FALLBACK alone does NOT engage under the counter model — it is a
		// post-engagement author that only appears after the guard has already engaged.
		// It is not TIMEOUT_NOOP, so it resets the counter like any other non-timeout author.
		val isTimeout = record.actions.any { it.author == ActionAuthor.TIMEOUT_NOOP }
		if (isTimeout) {
			consecutiveTimeouts++
			if (consecutiveTimeouts >= threshold) {
				engage(FailureReason.LLM_ABANDONED)
			}
		} else {
			consecutiveTimeouts = 0
		}
	}

	/**
	 * Engages the guard immediately, bypassing the consecutive-timeout counter.
	 *
	 * Use this when [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] catches an
	 * unhandled non-cancellation exception from
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy.emit] — the LLM is
	 * immediately considered absent regardless of prior tick history. The engagement reason
	 * is [FailureReason.LLM_ABANDONED] (trigger 2).
	 *
	 * No-op once [engaged] is already `true`.
	 */
	fun engageImmediately() {
		if (_engaged) return
		engage(FailureReason.LLM_ABANDONED)
	}

	private fun engage(reason: FailureReason) {
		// Set the reason before publishing _engaged so currentOutcome observers never see
		// _engaged == true with a null reason (visibility piggybacks on the volatile write).
		_reason = reason
		_engaged = true
		onAbandoned(reason)
	}
}