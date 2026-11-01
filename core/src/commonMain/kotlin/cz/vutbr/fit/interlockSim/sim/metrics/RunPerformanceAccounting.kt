/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.onRunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.onRunEnded

/**
 * The [RunPerformance] counters of [DefaultMetricsCollectionService] (Issue #1306, Goal 6 SP8): the
 * [MetricGroup.PERFORMANCE] group, subscribed only when that group is on.
 *
 * Counts from events only ([RunAccountingEvent.ReservePathCalled],
 * [RunAccountingEvent.RouteSearchBudgetExceeded]) and takes the wall and simulated seconds from
 * [RunEnded]; no clock is read here. Single-threaded, like the service.
 */
internal class RunPerformanceAccounting {
	private var reservePathCalls = 0
	private var budgetExceeded = 0
	private var runEnded: RunEnded? = null

	/** Subscribe to the events of [env]; call once, before `run()`. */
	fun subscribe(env: SimulationEnvironment) {
		env.onRunAccountingEvent(::onAccounting)
		env.onRunEnded(::onRunEnded)
	}

	fun onAccounting(event: RunAccountingEvent) {
		when (event) {
			is RunAccountingEvent.ReservePathCalled -> reservePathCalls++
			is RunAccountingEvent.RouteSearchBudgetExceeded -> budgetExceeded++
			else -> Unit
		}
	}

	fun onRunEnded(event: RunEnded) {
		runEnded = event
	}

	/** The row of the ended run, or `null` before the run ended. */
	fun performance(): RunPerformance? {
		val ended = runEnded ?: return null
		return RunPerformance.of(
			wallSeconds = ended.wallSeconds,
			simulationSeconds = ended.time,
			routeSearchBudgetExceeded = budgetExceeded,
			reservePathCalls = reservePathCalls
		)
	}
}
