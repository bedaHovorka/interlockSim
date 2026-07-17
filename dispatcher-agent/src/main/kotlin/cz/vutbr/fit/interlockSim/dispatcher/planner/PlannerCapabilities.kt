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
 * Capabilities and constraints of a [DispatcherPlanner] implementation.
 *
 * Informs the wiring layer about which planning algorithm is active and what runtime
 * constraints apply — in particular the maximum simulation speed multiplier.
 *
 * ## Speed constraint (Issue #187 owner decision, recorded in Issue #574)
 *
 * Async/LLM-backed planners need the simulation clock to not race ahead while the
 * planner is computing a decision.  The wiring layer **MUST** read
 * [maxSpeedMultiplier] before starting the simulation and cap the
 * `SimulationRunner` speed at that value when this planner is active.
 * Rule-based (synchronous, instant) planners set this to [UNRESTRICTED].
 *
 * @property name Human-readable name identifying the planner algorithm.
 * @property isAsynchronous `true` if the planner may suspend during [DispatcherPlanner.plan]
 *   (e.g. waiting for an LLM response); `false` for synchronous rule-based planners.
 * @property maxSpeedMultiplier Maximum simulation speed multiplier this planner safely
 *   supports.  Must be positive.  Use [UNRESTRICTED] for synchronous planners;
 *   [AGENT_MAX_SPEED_MULTIPLIER] for async/LLM planners.
 *
 * @since Issue #574 (SP3.6 — Goal 10)
 */
data class PlannerCapabilities(
	val name: String,
	val isAsynchronous: Boolean,
	val maxSpeedMultiplier: Double
) {
	init {
		require(maxSpeedMultiplier > 0.0) {
			"maxSpeedMultiplier must be positive, got $maxSpeedMultiplier"
		}
	}

	companion object {
		/**
		 * No speed restriction — synchronous (rule-based) planners.
		 *
		 * Set [maxSpeedMultiplier] to this value when the planner is synchronous and its
		 * [DispatcherPlanner.plan] call returns before the next simulation tick.
		 */
		const val UNRESTRICTED: Double = Double.MAX_VALUE

		/**
		 * Maximum simulation speed multiplier for agent-driven (async, LLM-backed) planners.
		 *
		 * Agent runtime speed is restricted to 2× real-time so the planner has sufficient
		 * wall-clock time to produce decisions before the simulation advances past the relevant
		 * state.  The wiring layer enforces this cap on the `SimulationRunner` when an async
		 * planner is active.
		 *
		 * The restriction applies to **all** async planners regardless of the underlying LLM
		 * model or hardware — even a fast local model needs a safety margin (owner decision,
		 * Issue #187 comment, recorded in Issue #574).
		 *
		 * @since Issue #574 (SP3.6 — Goal 10)
		 */
		const val AGENT_MAX_SPEED_MULTIPLIER: Double = 2.0
	}
}
