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
@Deprecated(
	message =
		"Replaced by TickOutcome (Issue #842), which splits EMPTY_NO_TOOLS into the " +
			"opposite outcomes 'LLM correctly did nothing' (LLM_NO_OP) and 'LLM produced " +
			"nothing at all' (TIMEOUT_NOOP). Retained for one release; use " +
			"FallbackReason.toTickOutcome() to project onto the new taxonomy. " +
			"See PlannerTickListener."
)
enum class FallbackReason {
	/**
	 * The LLM completed a dispatch cycle but neither returned decisions nor invoked any actuator
	 * tool — it truly did nothing this cycle.  The fallback is needed to route queued trains that
	 * the LLM left unaddressed.
	 *
	 * Since Issue #834, [KoogAgentPlanAdapter] only reaches this reason when the station is
	 * *not* idle (an active or queued train exists) — an empty cycle on a genuinely idle station
	 * is reported as [TickOutcome.LLM_NO_OP] instead, without consulting the fallback.
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
	EXCEPTION
}
