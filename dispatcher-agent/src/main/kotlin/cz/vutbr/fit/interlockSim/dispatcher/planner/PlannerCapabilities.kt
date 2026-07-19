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

import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationController

/**
 * Capabilities and constraints of a [DispatcherPlanner] implementation.
 *
 * Informs the wiring layer about which planning algorithm is active and what runtime
 * constraints apply — in particular the maximum simulation speed multiplier.
 *
 * ## Speed constraint (Issue #187 owner decision, recorded in Issue #574)
 *
 * Async/LLM-backed planners need the simulation clock to not race ahead while the
 * planner is computing a decision.  [maxSpeedMultiplier] *declares* the cap; capping the
 * `SimulationRunner` speed to that value is wired in SP1.4 (#549).  Until #549 lands, the
 * wiring layer rejects an async planner bound to a no-pacing `NoOpSimulationController` via
 * [assertPlannerPacingCompatible] — converting a future silent Issue #187 violation into a
 * fast startup failure.  Rule-based (synchronous, instant) planners set this to [UNRESTRICTED]
 * and are exempt (they return before the next tick and need no pacing).
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
		 * state.  Capping the `SimulationRunner` to this value is wired in SP1.4 (#549); until
		 * then [assertPlannerPacingCompatible] only prevents an async planner from being bound to
		 * a no-pacing `NoOpSimulationController`.
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

/**
 * Enforces the Issue #187 speed-cap precondition before a simulation starts.
 *
 * Async/LLM planners must run at <= [PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER]× real-time,
 * which requires a pacing [SimulationController].  [NoOpSimulationController] provides no pacing,
 * so an async planner bound to it cannot honour the cap and is rejected up-front.  Synchronous
 * (rule-based) planners are exempt — they return before the next tick and need no pacing.
 *
 * **Enforcement status (SP3.6):** this guard only prevents the unsafe planner/controller
 * combination.  Capping the actual `SimulationRunner` speed to
 * [PlannerCapabilities.maxSpeedMultiplier] is deferred to SP1.4 (#549); until then no async
 * planner may be activated against a no-pacing controller.
 *
 * @throws IllegalStateException when [planner] is asynchronous and [controller] is a
 *   [NoOpSimulationController].
 * @since Issue #574 (SP3.6 — Goal 10)
 */
fun assertPlannerPacingCompatible(
	planner: DispatcherPlanner,
	controller: SimulationController
) {
	if (planner.capabilities.isAsynchronous && controller is NoOpSimulationController) {
		throw IllegalStateException(
			"Async dispatcher planner '${planner.capabilities.name}' requires a pacing " +
				"SimulationController to enforce the " +
				"${PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER}x speed cap (Issue #187), " +
				"but $controller is a NoOpSimulationController. " +
				"Wire a pacing controller (SimulationRunner via DelegatingSimulationController, SP4.2 #564)."
		)
	}
}
