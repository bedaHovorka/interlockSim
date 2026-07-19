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
import cz.vutbr.fit.interlockSim.sim.Dispatcher

/**
 * [DispatcherPlanner] adapter that delegates to the synchronous [Dispatcher] API.
 *
 * This is the **rule-based planner** used in production (SP3.6, Issue #574).  It bridges
 * the legacy synchronous [Dispatcher.decide] call into the pluggable [DispatcherPlanner]
 * interface so that the [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] can work
 * uniformly with any planner implementation.
 *
 * ## Speed constraint
 *
 * [capabilities.maxSpeedMultiplier][PlannerCapabilities.maxSpeedMultiplier] is set to
 * [PlannerCapabilities.UNRESTRICTED] because [Dispatcher.decide] is synchronous and returns
 * in negligible wall-clock time — the simulation clock can run at full speed with no risk of
 * the planner falling behind.
 *
 * ## Usage
 *
 * ```kotlin
 * val planner: DispatcherPlanner = RuleBasedPlanAdapter(RuleBasedDispatcher())
 * val driver = AgentLoopDriver(
 *     perceptionPort = ...,
 *     planner = planner,
 *     commandQueue = ...,
 *     controller = ...
 * )
 * ```
 *
 * @param dispatcher The synchronous rule-based dispatcher to delegate to.
 *
 * @since Issue #574 (SP3.6 — Goal 10)
 */
class RuleBasedPlanAdapter(
	private val dispatcher: Dispatcher
) : DispatcherPlanner {
	override val capabilities: PlannerCapabilities =
		PlannerCapabilities(
			name = "RuleBased",
			isAsynchronous = false,
			maxSpeedMultiplier = PlannerCapabilities.UNRESTRICTED
		)

	/**
	 * Delegates synchronously to [Dispatcher.decide]; returns immediately without suspending.
	 *
	 * Callers may freely use this inside a coroutine — the underlying call is non-suspending
	 * and finishes in constant time.
	 */
	override suspend fun plan(observation: DispatchObservation): List<DispatchDecision> = dispatcher.decide(observation)
}
