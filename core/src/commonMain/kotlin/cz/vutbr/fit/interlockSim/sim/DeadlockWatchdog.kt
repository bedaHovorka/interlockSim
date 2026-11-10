/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

/**
 * The persistence rules of the deadlock watchdog (Issue #1154): a wait-for cycle counts as a
 * deadlock when either rule fires, and only while the simulation clock advances.
 *
 * - the **same** cycle (same member set) seen on [persistTicks] successive dispatcher ticks;
 * - **any** cycle present on `persistTicks * 10` successive ticks, the churn bound: member sets
 *   that alternate per tick never satisfy the same-member rule, yet a permanently cycling system
 *   must still report instead of resetting forever.
 *
 * An observation at a clock value that is not later than the previous one is ignored, so a frozen
 * clock never builds up a count: that case belongs to the route-search node budget (Issue #1151),
 * not to this watchdog. An absent cycle restarts both counts.
 */
internal class DeadlockWatchdog(
	private val persistTicks: Int
) {
	init {
		require(persistTicks > 0) { "persistTicks must be positive, got: $persistTicks" }
	}

	private var members: Set<String>? = null
	private var lastTime: Double = Double.NEGATIVE_INFINITY
	private var anyCycleTicks: Int = 0
	private var ticks: Int = 0

	/**
	 * The tick count a report should carry: the churn count. The same-member count never exceeds it
	 * (both reset together on an absent cycle, and a member change restarts the same-member count,
	 * so it trails), which is why the churn count alone is the honest figure behind a report.
	 */
	fun reportedTicks(): Int = anyCycleTicks

	/**
	 * Feeds one dispatcher tick.
	 *
	 * @param time simulation time of the tick
	 * @param cycle the cycle found on this tick, or `null` when the wait-for graph is acyclic
	 * @return the cycle once either rule fires, otherwise `null`
	 */
	fun observe(
		time: Double,
		cycle: List<String>?
	): List<String>? {
		if (cycle == null) {
			members = null
			ticks = 0
			anyCycleTicks = 0
			lastTime = time
			return null
		}
		if (time <= lastTime) {
			return null
		}
		lastTime = time
		val current = cycle.toSet()
		ticks = if (current == members) ticks + 1 else 1
		members = current
		anyCycleTicks += 1
		val fired = ticks >= persistTicks || anyCycleTicks >= persistTicks * 10
		return if (fired) cycle else null
	}
}
