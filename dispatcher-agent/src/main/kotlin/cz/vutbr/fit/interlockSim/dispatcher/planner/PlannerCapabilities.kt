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
 * ## Speed constraint
 *
 * Async/LLM-backed planners need the simulation clock to not race ahead while the
 * planner is computing a decision.  [maxSpeedMultiplier] *declares* the cap; **clamping**
 * the `SimulationRunner` speed to that value is still open work — GUI runs are paced by a
 * `DelegatingSimulationController` backed by the live `SimulationRunner`, so an async planner
 * may legitimately be bound there, but the cap itself is not yet enforced numerically.
 * Console/headless runs pass a no-pacing `NoOpSimulationController`, which
 * [assertPlannerPacingCompatible] rejects up-front — converting a silent violation into a
 * fast startup failure. Rule-based (synchronous, instant) planners set this to
 * [UNRESTRICTED] and are exempt (they return before the next tick and need no pacing).
 *
 * @property name Human-readable name identifying the planner algorithm.
 * @property isAsynchronous `true` if the planner may suspend during [DispatcherPlanner.plan]
 *   (e.g. waiting for an LLM response); `false` for synchronous rule-based planners.
 * @property maxSpeedMultiplier Maximum simulation speed multiplier this planner safely
 *   supports.  Must be positive.  Use [UNRESTRICTED] for synchronous planners;
 *   [AGENT_MAX_SPEED_MULTIPLIER] for async/LLM planners.
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
		 * state.  **Clamping** the `SimulationRunner` to this value is still open work;
		 * [assertPlannerPacingCompatible] accepts an async planner on a GUI run (paced by
		 * `DelegatingSimulationController`) but does not yet enforce the numeric cap, and still
		 * rejects an async planner bound to a no-pacing `NoOpSimulationController`.
		 *
		 * The restriction applies to **all** async planners regardless of the underlying LLM
		 * model or hardware — even a fast local model needs a safety margin.
		 */
		const val AGENT_MAX_SPEED_MULTIPLIER: Double = 2.0
	}
}

/**
 * Enforces the speed-cap precondition before a simulation starts.
 *
 * Async/LLM planners must run at <= [PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER]× real-time,
 * which requires a pacing [SimulationController].  [NoOpSimulationController] provides no pacing,
 * so an async planner bound to it cannot honour the cap and is rejected up-front.  Synchronous
 * (rule-based) planners are exempt — they return before the next tick and need no pacing.
 *
 * **Enforcement status:** this guard performs the *binary* reject — an async planner bound
 * to a no-pacing `NoOpSimulationController` (console/headless runs) fails fast. GUI runs pass
 * a `DelegatingSimulationController` paced by `SimulationRunner`, so an async planner is
 * accepted there. *Clamping* the live `SimulationRunner` speed to
 * [PlannerCapabilities.maxSpeedMultiplier] (the actual 2× cap) is still open work; until then
 * an async planner on a GUI run is accepted but not speed-capped.
 *
 * @throws IllegalStateException when [planner] is asynchronous and [controller] is a
 *   [NoOpSimulationController].
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
