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
 * Listener that a dispatcher planner calls after each tick completes, reporting the full
 * [TickOutcome] taxonomy via a single [TickRecord].
 *
 * Replaces the two-callback [PlannerCycleListener] (`onLlmSuccess` / `onFallback`), which could
 * not distinguish an explicit, healthy LLM no-op from a harness-applied safe do-nothing — see
 * [TickOutcome] for the full rationale.
 *
 * Implementations must be thread-safe; [onTick] may be called from multiple coroutines
 * simultaneously.
 *
 * **Wiring note:** [KoogAgentPlanAdapter.addTickListener] registers this to fire at every
 * `plan()` completion (SP2c.20 follow-up, Issue #843), independent of the deprecated
 * [PlannerCycleListener] slot `MeasuringPlanAdapter` claims — the two listeners coexist without
 * interfering. Multiple [PlannerTickListener]s registered via [KoogAgentPlanAdapter.addTickListener]
 * also coexist without interfering (Issue #713 Task 9), fanned out through [CompositeTickListener].
 *
 * @see TickRecord
 * @see TickOutcome
 * @see PlannerCycleListener
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
fun interface PlannerTickListener {
	/**
	 * Called when a dispatcher tick completes, regardless of whether it succeeded, degraded, or
	 * failed.
	 *
	 * @param record What happened this tick and when.
	 */
	fun onTick(record: TickRecord)
}
