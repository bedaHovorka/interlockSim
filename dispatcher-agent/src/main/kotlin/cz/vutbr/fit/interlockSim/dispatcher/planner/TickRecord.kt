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
 * @param latencyMs Wall-clock duration in milliseconds of the LLM inference cycle that produced
 *   this tick, measured by [KoogAgentPlanAdapter] around the `withTimeout { decideAsync(...) }`
 *   call — see its KDoc for exactly what the measurement covers and why. `null` when this tick
 *   was not the direct result of a timed inference attempt (e.g. a tick reported by a future
 *   non-LLM planner, or any construction site that predates this field), so that "no data"
 *   stays honest rather than being reported as a `0` sample.
 *   [DefaultDispatcherRunRecorder] accumulates the non-`null` values across a run and derives
 *   [DispatcherRunSnapshot.latencyP50Ms], [DispatcherRunSnapshot.latencyP95Ms] and
 *   [DispatcherRunSnapshot.latencyMaxMs] from them.
 *
 * @see PlannerTickListener
 * @see TickOutcome
 * @see DefaultDispatcherRunRecorder
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy); `latencyMs` added in Issue #834
 *   (SP2c.11 — real per-run inference latency)
 */
data class TickRecord(
	val outcome: TickOutcome,
	val simTime: Double,
	val timeoutNoOpCause: TimeoutNoOpCause? = null,
	val latencyMs: Long? = null
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
