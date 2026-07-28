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
 * Categorises why the rule-based fallback was invoked by [KoogAgentPlanAdapter].
 *
 * Each value corresponds to one of the three fallback paths inside [KoogAgentPlanAdapter.plan]:
 *
 * | Reason | Trigger |
 * |---|---|
 * | [EMPTY_NO_TOOLS] | LLM cycle completed but returned no decisions and invoked no actuator tool |
 * | [TIMEOUT] | [kotlinx.coroutines.withTimeout] expired before the LLM responded |
 * | [EXCEPTION] | LLM call threw a non-cancellation exception |
 *
 * Used by [PlannerCycleListener] and aggregated in [PlannerMetricsSnapshot].
 *
 * @see KoogAgentPlanAdapter
 * @see MeasuringPlanAdapter
 * @since Issue #817 (Goal 10 dispatcher metrics)
 */
enum class FallbackReason {
	/**
	 * The LLM completed a dispatch cycle but neither returned decisions nor invoked any actuator
	 * tool — it truly did nothing this cycle.  The fallback is needed to route queued trains that
	 * the LLM left unaddressed.
	 */
	EMPTY_NO_TOOLS,

	/**
	 * The LLM inference exceeded the configured per-cycle timeout
	 * ([KoogAgentPlanAdapter.inferenceTimeout]).  The fallback provides routing decisions for the
	 * simulation step that would otherwise stall.
	 */
	TIMEOUT,

	/**
	 * The LLM call threw an unexpected exception (network error, invalid tool call, HTTP error,
	 * etc.).  The fallback ensures simulation continuity despite the transient LLM failure.
	 */
	EXCEPTION,
}
