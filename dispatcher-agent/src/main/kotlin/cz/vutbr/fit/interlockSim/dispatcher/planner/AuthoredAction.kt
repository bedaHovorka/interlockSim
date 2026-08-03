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
 * Attribution metadata attached to every dispatching action (SP2c.20, Issue #843, Principle P7).
 *
 * Carries the full attribution identity for one action event — who made the decision, why,
 * what kind of decision it was, and which dispatcher tick originated it. Used in
 * [ActionOutcome] so both observer and metrics code can attribute results without looking back
 * at the planner's per-tick state.
 *
 * ## C7 gate
 *
 * [ActionAuthor.RULE_FALLBACK] and [ActionAuthor.SAFETY_NET] are the two C7-violating authors.
 * See [cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore.c7Clean] for the
 * authoritative `c7Clean` computation.
 *
 * ## Unattributed applies (`tickIndex = -1`)
 *
 * When the correlation map cannot find a registered entry for an applied
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision] (entry was evicted, reused across ticks, or
 * never registered), [tickIndex] is set to `-1` and the action is counted as an
 * `unattributedApply` in the metrics. This is never swallowed silently.
 *
 * @property author Decision-maker that produced this action.
 * @property reason Human-readable explanation of why [author] chose this action.
 * @property decisionKind Classification of the decision type (e.g. `"ApproveTrain"`,
 *   `"RequestRoute"`, `"NoOp"`, `"RULE_FALLBACK"`, `"SAFETY_NET"`).
 * @property tickIndex Dispatcher tick (0-based) that emitted this action; `-1` when the
 *   correlation map entry was unavailable at apply time.
 *
 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
 */
data class AuthoredAction(
	val author: ActionAuthor,
	val reason: String,
	val decisionKind: String,
	val tickIndex: Long
)
