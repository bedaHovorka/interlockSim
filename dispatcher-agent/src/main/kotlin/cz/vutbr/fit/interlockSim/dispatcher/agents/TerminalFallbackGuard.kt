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
 * for [threshold] consecutive ticks (SP2c.8, Issue #831).
 *
 * ## Counter semantics
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
 * @param onAbandoned Callback invoked exactly once when the guard engages, with
 *   [FailureReason.LLM_ABANDONED]. Runs synchronously on the calling thread (the driver
 *   coroutine). Use it to swap the [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy]
 *   in [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop].
 *
 * @since Issue #831 (SP2c.8 — Goal 10 terminal fallback guard redesign)
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

	/**
	 * Whether the guard has engaged (the LLM has been absent for [threshold] consecutive
	 * ticks, or [engageImmediately] was called).
	 *
	 * Once `true`, never reverts to `false`.
	 */
	val engaged: Boolean get() = _engaged

	/**
	 * The current [RunOutcome] as observed so far.
	 *
	 * Returns [Failed] with reason [FailureReason.LLM_ABANDONED] once [engaged] is `true`;
	 * [Running] otherwise. Never reverts after [engaged] becomes `true`.
	 */
	val currentOutcome: RunOutcome get() = if (_engaged) Failed(FailureReason.LLM_ABANDONED) else Running

	/**
	 * Processes a completed [TickRecord].
	 *
	 * - If any action has author [ActionAuthor.TIMEOUT_NOOP], the consecutive-timeout
	 *   counter increments; if it reaches [threshold], the guard engages.
	 * - Otherwise (any non-TIMEOUT_NOOP author — including [ActionAuthor.LLM] no_op,
	 *   [ActionAuthor.RULE_BASED], or all-rejected-but-emitted) the counter resets to 0.
	 *
	 * No-op once [engaged] is `true`.
	 *
	 * @param record The completed tick record to inspect.
	 */
	fun observe(record: TickRecord) {
		if (_engaged) return
		val isTimeout = record.actions.any { it.author == ActionAuthor.TIMEOUT_NOOP }
		if (isTimeout) {
			consecutiveTimeouts++
			if (consecutiveTimeouts >= threshold) {
				engage()
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
	 * immediately considered absent regardless of prior tick history.
	 *
	 * No-op once [engaged] is already `true`.
	 */
	fun engageImmediately() {
		if (_engaged) return
		engage()
	}

	private fun engage() {
		_engaged = true
		onAbandoned(FailureReason.LLM_ABANDONED)
	}
}
