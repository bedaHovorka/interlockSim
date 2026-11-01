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

import kotlin.math.roundToLong

/**
 * The performance row of one finished run (Issue #1306, Goal 6 SP8): the wall time against the
 * simulated time, and the two routing figures Goal 1B counted by hand. Collected with
 * [MetricGroup.PERFORMANCE]; with the group off the run record has no row (`null`), never a row of
 * zeroes.
 *
 * Every harness gets the same row: the wall clock is read by the simulation context around the
 * kDisco kernel run ([cz.vutbr.fit.interlockSim.sim.RunEnded.wallSeconds]), the counts come from
 * events. The peak of concurrent trains that the Goal 1B SP11 row shows next to wall time is
 * [RunRecord.maxConcurrentTrains]; [performanceLine] prints the two side by side.
 *
 * @property wallSeconds Wall-clock seconds the kernel ran. A measurement of the machine, not of the
 *   railway: it varies from run to run and with load.
 * @property simulationSeconds Simulated seconds the run reached (the kernel clock at run end; a run
 *   starts at `0`).
 * @property realTimeRatio [simulationSeconds] divided by [wallSeconds]: how many times faster than
 *   real time the run went (the #591 acceptance figure). `null` when [wallSeconds] is not above `0`.
 *   Only an unpaced run (headless example, sweep child, native CLI) measures the simulator's speed: in
 *   a GUI run the wall includes the speed-control pacing and any pauses, so its ratio mostly shows the
 *   chosen speed factor.
 * @property routeSearchBudgetExceeded Switch-constrained route searches stopped at their node budget
 *   (Issue #1151), one per search, the count of the budget WARN lines of the run.
 * @property reservePathCalls Public route requests of `PathReservationService` during the run, each
 *   counted once: `reservePath`, `reservePathToAny` and `reservePathToAnyNextSemaphore` (the trains'
 *   own entry and next-signal requests). The internal per-target attempts are not counted.
 * @since Issue #1306 (Goal 6 SP8)
 */
data class RunPerformance(
	val wallSeconds: Double,
	val simulationSeconds: Double,
	val realTimeRatio: Double?,
	val routeSearchBudgetExceeded: Int,
	val reservePathCalls: Int
) {
	/** @since Issue #1306 (Goal 6 SP8) */
	companion object {
		/**
		 * The row of a run that reached [simulationSeconds] in [wallSeconds], with the ratio derived.
		 *
		 * @since Issue #1306 (Goal 6 SP8)
		 */
		fun of(
			wallSeconds: Double,
			simulationSeconds: Double,
			routeSearchBudgetExceeded: Int,
			reservePathCalls: Int
		): RunPerformance =
			RunPerformance(
				wallSeconds = wallSeconds,
				simulationSeconds = simulationSeconds,
				realTimeRatio = if (wallSeconds > 0.0) simulationSeconds / wallSeconds else null,
				routeSearchBudgetExceeded = routeSearchBudgetExceeded,
				reservePathCalls = reservePathCalls
			)
	}
}

/**
 * The performance row of this record as one console line, starting `Performance:` -- printed after
 * the run record line by the native CLI (at run end and in its SIGINT partial summary) and by the
 * JVM `example` mode. Like the record line it does not start with `---`.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
fun RunRecord.performanceLine(): String {
	val row = performance ?: return "Performance: not measured (metric group PERFORMANCE is off)"
	val ratio = row.realTimeRatio?.let { "${fixed(it, 1)}x" } ?: "none"
	return "Performance: wall ${fixed(row.wallSeconds, 2)} s, simulated ${fixed(row.simulationSeconds, 1)} s, " +
		"real-time ratio $ratio, peak admitted $maxConcurrentTrains, " +
		"route-search budget exceeded ${row.routeSearchBudgetExceeded}, reservePath calls ${row.reservePathCalls}"
}

/** [value] with exactly [decimals] decimals (commonMain has no `String.format`). */
private fun fixed(
	value: Double,
	decimals: Int
): String {
	var scale = 1L
	repeat(decimals) { scale *= DECIMAL_BASE }
	val scaled = (value * scale).roundToLong()
	val sign = if (scaled < 0) "-" else ""
	val magnitude = kotlin.math.abs(scaled)
	val whole = magnitude / scale
	if (decimals == 0) return "$sign$whole"
	val fraction = (magnitude % scale).toString().padStart(decimals, '0')
	return "$sign$whole.$fraction"
}

private const val DECIMAL_BASE = 10L
