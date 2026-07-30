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

/**
 * Immutable record of a single dispatcher tick's [TickOutcome], reported to a
 * [PlannerTickListener].
 *
 * @param outcome What happened this tick.
 * @param simTime Simulation time (seconds) at which this tick completed.
 * @param timeoutNoOpCause The [TimeoutNoOpCause] explaining why the harness applied a safe
 *   do-nothing. Must be non-`null` if and only if [outcome] is [TickOutcome.TIMEOUT_NOOP] —
 *   enforced by an `init` invariant.
 *
 * @see PlannerTickListener
 * @see TickOutcome
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
data class TickRecord(
	val outcome: TickOutcome,
	val simTime: Double,
	val timeoutNoOpCause: TimeoutNoOpCause? = null
) {
	init {
		val expectsCause = outcome == TickOutcome.TIMEOUT_NOOP
		val hasCause = timeoutNoOpCause != null
		require(expectsCause == hasCause) {
			"timeoutNoOpCause must be set if and only if outcome is TIMEOUT_NOOP, " +
				"but outcome=$outcome timeoutNoOpCause=$timeoutNoOpCause"
		}
	}
}
