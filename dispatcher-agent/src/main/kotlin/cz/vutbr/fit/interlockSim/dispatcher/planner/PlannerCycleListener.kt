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
 * Listener that [KoogAgentPlanAdapter] calls after each dispatch cycle completes.
 *
 * Implementations receive one of two events per cycle:
 *
 * - [onLlmSuccess]: the LLM produced a usable result (non-empty decision list, or at least one
 *   actuator-tool invocation during the cycle).
 * - [onFallback]: the rule-based dispatcher was invoked as a fallback because the LLM
 *   timed out, threw, or completed without producing any decisions or tool calls.
 *
 * Implementations must be thread-safe; [KoogAgentPlanAdapter.plan] may be called from multiple
 * coroutines simultaneously.
 *
 * @see KoogAgentPlanAdapter
 * @see MeasuringPlanAdapter
 * @see FallbackReason
 * @since Issue #817 (Goal 10 dispatcher metrics)
 */
interface PlannerCycleListener {
	/**
	 * Called when the LLM dispatch cycle completed successfully.
	 *
	 * "Success" means the LLM either returned a non-empty decision list or invoked at least one
	 * actuator tool during the cycle (the normal Koog tool-calling outcome — see the class KDoc
	 * of [KoogAgentPlanAdapter] for the full "Fallback priority" semantics).
	 *
	 * @param simTime Simulation time (seconds) at which this cycle completed.
	 */
	fun onLlmSuccess(simTime: Double)

	/**
	 * Called when the rule-based fallback dispatcher was invoked for this cycle.
	 *
	 * @param reason Categorised cause of the fallback (timeout / empty cycle / exception).
	 * @param simTime Simulation time (seconds) at which the fallback was triggered.
	 */
	fun onFallback(
		reason: FallbackReason,
		simTime: Double
	)
}
