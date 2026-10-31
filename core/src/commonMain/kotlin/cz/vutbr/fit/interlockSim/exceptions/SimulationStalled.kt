/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

/**
 * The simulation clock still advances, but trains wait for each other in a cycle that no event can
 * resolve (Issue #1154). Raised by the deadlock watchdog of `MultiTrainLoop`, which then aborts the
 * run through `SimulationEnvironment.errorStop`.
 *
 * This is **not** the frozen-clock case: a run whose clock stops advancing never reaches the
 * watchdog's tick, and is bounded by the route-search node budget instead (Issue #1151).
 *
 * @property cycle Names of the trains in the wait-for cycle; each waits for the next, the last for
 *   the first.
 * @property persistedTicks Number of consecutive dispatcher ticks, with an advancing clock, behind
 *   the report: the ticks of the same cycle, or of any cycle at the churn-bound tick count.
 * @property simTime Simulation time at which the cycle was reported.
 */
class SimulationStalled(
	val cycle: List<String>,
	val persistedTicks: Int,
	val simTime: Double
) : SimulationException(
		Severity.FATAL,
		"Simulation stalled at t=$simTime: wait-for cycle ${cycle.joinToString(" -> ")} -> ${cycle.first()} " +
			"persisted for $persistedTicks dispatcher ticks.",
		null,
		cycle
	)
