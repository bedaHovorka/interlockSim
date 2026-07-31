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
 * Monitors [TickRecord]s for [ActionAuthor.RULE_FALLBACK] actions and transitions the
 * [RunOutcome] to [Failed] when detected (SP2c.5, Issue #828).
 *
 * ## Why [ActionAuthor.RULE_BASED] does not trigger failure
 *
 * A run where all actions carry author [ActionAuthor.RULE_BASED] is a deliberate rule-based
 * run (e.g. the P10 gate determinism test). This guard must **not** mark such a run as
 * FAILED — otherwise every rule-based acceptance run would be incorrectly classified as a
 * failure.
 *
 * Only [ActionAuthor.RULE_FALLBACK] signals a terminal failure: it means the LLM emission
 * strategy was running but failed mid-turn and a rule-based strategy had to take over.
 * **That** is the C7 gate scenario (SP2c.20).
 *
 * ## Usage
 *
 * ```kotlin
 * val guard = TerminalFallbackGuard()
 * // inside DispatchTickLoop.runTick():
 * guard.observe(tickRecord)
 * // after the tick:
 * val outcome: RunOutcome = guard.currentOutcome
 * ```
 *
 * The guard is single-use and immutable once a failure is detected. Once [RunOutcome.Failed]
 * is set it never reverts to [Running].
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
class TerminalFallbackGuard {
	private var outcomeState: RunOutcome = Running

	/**
	 * The current [RunOutcome] as observed so far.
	 *
	 * Starts as [Running]; transitions to [Failed] the first time a tick record contains
	 * a [ActionAuthor.RULE_FALLBACK] action. Never reverts after that.
	 */
	val currentOutcome: RunOutcome get() = outcomeState

	/**
	 * Processes a completed [TickRecord].
	 *
	 * If any [AttributedAction] in the record has [ActionAuthor.RULE_FALLBACK] and the
	 * outcome has not yet failed, transitions [currentOutcome] to
	 * [Failed] with reason [FailureReason.LLM_ABANDONED].
	 *
	 * @param record The completed tick record to inspect.
	 */
	fun observe(record: TickRecord) {
		if (outcomeState is Failed) return
		val hasFallback = record.actions.any { it.author == ActionAuthor.RULE_FALLBACK }
		if (hasFallback) {
			outcomeState = Failed(FailureReason.LLM_ABANDONED)
		}
	}
}
