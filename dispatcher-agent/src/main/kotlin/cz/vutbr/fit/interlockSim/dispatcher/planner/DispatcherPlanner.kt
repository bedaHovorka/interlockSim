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

import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation

/**
 * Common contract for all dispatcher-planner implementations.
 *
 * A planner converts a [DispatchObservation] (the current state of the railway network as
 * seen by the dispatcher) into a list of [DispatchDecision]s to execute.
 *
 * ## Pluggability contract (SP3.6 — Issue #574)
 *
 * Different planning algorithms are interchangeable behind this interface:
 * - **Rule-based** ([RuleBasedPlanAdapter]) — deterministic, explainable, no latency;
 *   wraps the existing [cz.vutbr.fit.interlockSim.sim.Dispatcher] API.
 * - **Search / optimisation** — future; will also implement this interface.
 * - **LLM-driven** — future; async implementation with non-trivial latency.
 *
 * ## Asynchrony
 *
 * [plan] is a `suspend` function so that LLM-backed planners can await remote inference
 * without blocking the driver thread.  Synchronous rule-based planners simply return
 * immediately without suspending.
 *
 * ## Speed constraint (Issue #187 / Issue #574)
 *
 * [capabilities.maxSpeedMultiplier][PlannerCapabilities.maxSpeedMultiplier] declares the
 * maximum simulation speed this planner can safely follow.  The wiring layer **MUST** read
 * this value and cap the `SimulationRunner` speed accordingly before starting the simulation.
 *
 * ## Thread safety
 *
 * Implementations **must** be stateless or internally thread-safe: [plan] may be called
 * concurrently from multiple coroutines (e.g. during parallel tests).
 *
 * @since Issue #574 (SP3.6 — Goal 10)
 */
interface DispatcherPlanner {
	/**
	 * Static metadata describing this planner's capabilities and constraints.
	 *
	 * Read once by the wiring layer before simulation starts; must not change after the
	 * planner is constructed.
	 */
	val capabilities: PlannerCapabilities

	/**
	 * Produces dispatch decisions for the given [observation].
	 *
	 * Implementations must not retain [observation] beyond the call or mutate simulation
	 * state (pure function / referentially transparent intent — same as the
	 * [cz.vutbr.fit.interlockSim.sim.Dispatcher] contract).
	 *
	 * An empty list is valid.  Rule-based implementations typically return at least
	 * [DispatchDecision.NoAction] so that the driver can distinguish "planner ran, found
	 * nothing to do" from "planner was not called".
	 *
	 * @param observation Read-only snapshot of the railway network state.
	 * @return List of decisions to post to the actuator queue; never `null`.
	 */
	suspend fun plan(observation: DispatchObservation): List<DispatchDecision>
}
