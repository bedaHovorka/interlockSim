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

import java.util.Locale

/**
 * The Performance table of the Parameter Sweep section of `report.md` (Issue #1306, Goal 6 SP8): the
 * run record's performance row aggregated per parameter cell **and example**, followed by the Goal 1B
 * SP11 wall budget, reported against and never asserted.
 *
 * Its own table, by ruling (gemma4 as traffic-simulation-expert, B2): the #895 Decision Hygiene and
 * Railway Outcomes tables are keyed by parameter cell alone, so a campaign that runs two networks
 * with the same parameters pools them into one row, and a wall time averaged across networks would
 * be meaningless. Here every network of a cell has its own row; the rows follow the cells' ranking
 * and start with the same parameter-identity columns, so a reader can join them to the #895 tables.
 *
 * Wall time is a measurement of the machine, not of the railway: it varies with load and between
 * runs, so the table reports medians and nothing in the gate reads it.
 */
internal object PerformanceReportSection {
	/** Goal 1B SP11 budget for one run (#1158), in wall seconds. Reported against, never asserted. */
	private const val SP11_RUN_BUDGET_SECONDS = 15

	/** Goal 1B SP11 budget for a whole campaign (#1158), in wall seconds. Reported against, never asserted. */
	private const val SP11_TOTAL_BUDGET_SECONDS = 180

	/** Column count of the Performance table: 11 parameter-identity columns and 8 of its own. */
	private const val PERFORMANCE_TABLE_COLUMNS = 19

	private const val NOT_MEASURED = "n/a"

	/**
	 * Appends the Performance table for [cells] (ranked Parameter Sweep cells), each row starting
	 * with [paramsRowPrefix], then the SP11 budget line over every run of [cells].
	 */
	fun append(
		sb: StringBuilder,
		cells: List<ArmReport>,
		paramsRowPrefix: (ArmReport) -> String
	) {
		sb.appendLine("### Performance")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | " +
				"Breaker Threshold | Breaker Cooldown s | " +
				"Example | Runs | Wall s (median) | Sim s (median) | Ratio (median) | Max concurrent | " +
				"Budget exceeded | reservePath calls |"
		)
		sb.appendLine("|" + "---|".repeat(PERFORMANCE_TABLE_COLUMNS))
		for (cell in cells) {
			val byExample =
				if (cell.snapshots.isEmpty()) {
					listOf(null to emptyList())
				} else {
					cell.snapshots
						.groupBy { it.example }
						.entries
						.sortedWith(compareBy(nullsLast()) { it.key })
						.map { it.key to it.value }
				}
			for ((example, runs) in byExample) {
				sb.appendLine(paramsRowPrefix(cell) + row(example, runs))
			}
		}
		sb.appendLine()
		sb.appendLine(budgetLine(cells.flatMap { it.snapshots }))
		sb.appendLine()
	}

	/** The legend bullet for the Performance table. */
	fun legend(): String =
		"- **Performance (issue #1306)** — the run record's performance row, one row per parameter cell " +
			"and example, so wall time is never averaged across networks. **Wall s** is the wall time the " +
			"simulation kernel ran (not JVM start-up or network loading), **Sim s** the simulated time " +
			"reached, **Ratio** Sim s ÷ Wall s per run (times faster than real time); all three are medians " +
			"over the runs. **Max concurrent** is the peak of admitted trains (max), **Budget exceeded** the " +
			"route searches stopped at their node budget and **reservePath calls** the public route requests " +
			"(both summed). Wall time measures the machine, not the railway: it varies with load, and " +
			"nothing in the gate reads it. `n/a` = not measured (a file before schema version 12, an " +
			"aborted run, or the PERFORMANCE metric group off). The SP11 wall budget below the table is " +
			"reported against, never asserted."

	private fun row(
		example: String?,
		runs: List<DispatcherRunSnapshot>
	): String {
		val outcomes = runs.map { it.railwayOutcome }
		return "| ${example ?: NOT_MEASURED} " +
			"| ${runs.size} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.wallSeconds }), 2)} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.simulationSeconds }), 1)} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.realTimeRatio }), 1)} " +
			"| ${outcomes.mapNotNull { it.maxConcurrentTrains }.maxOrNull() ?: NOT_MEASURED} " +
			"| ${sumOf(outcomes.map { it.routeSearchBudgetExceeded })} " +
			"| ${sumOf(outcomes.map { it.reservePathCalls })} |"
	}

	private fun budgetLine(runs: List<DispatcherRunSnapshot>): String {
		val prefix = "Wall-time budget (Goal 1B SP11, reported, never asserted): "
		val walls = runs.mapNotNull { it.railwayOutcome.wallSeconds }
		if (walls.isEmpty()) return prefix + "not measured"
		val over = walls.count { it > SP11_RUN_BUDGET_SECONDS }
		return prefix +
			"slowest run ${fixed(walls.max(), 2)} s against $SP11_RUN_BUDGET_SECONDS s per run " +
			"($over of ${walls.size} runs over); " +
			"all runs ${fixed(walls.sum(), 2)} s against $SP11_TOTAL_BUDGET_SECONDS s in total"
	}

	private fun medianOf(values: List<Double>): Double? {
		if (values.isEmpty()) return null
		val sorted = values.sorted()
		val mid = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
	}

	private fun sumOf(values: List<Long?>): String {
		val present = values.filterNotNull()
		return if (present.isEmpty()) NOT_MEASURED else present.sum().toString()
	}

	private fun fixed(
		value: Double?,
		decimals: Int
	): String = value?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: NOT_MEASURED
}
