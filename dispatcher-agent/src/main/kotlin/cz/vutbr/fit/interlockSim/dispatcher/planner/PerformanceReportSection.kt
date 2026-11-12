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
 * runs, so the table reports medians and nothing in the gate reads it. Two walls are shown: the
 * kernel wall of the run record (`RailwayOutcome.wallSeconds`) and the whole-run wall the `aiSweep`
 * driver timed around the forked JVM (`DispatcherRunSnapshot.processWallSeconds`), which the SP11
 * budget is about.
 */
internal object PerformanceReportSection {
	/** Goal 1B SP11 budget for one run (#1158), in wall seconds. Reported against, never asserted. */
	private const val SP11_RUN_BUDGET_SECONDS = 15

	/** Goal 1B SP11 budget for a whole campaign (#1158), in wall seconds. Reported against, never asserted. */
	private const val SP11_TOTAL_BUDGET_SECONDS = 180

	/** Column count of the Performance table: 11 parameter-identity columns and 10 of its own. */
	private const val PERFORMANCE_TABLE_COLUMNS = 21

	private const val NOT_MEASURED = "n/a"

	/** The Example cell of a run that named no example (a GUI run, a file before schema version 12). */
	private const val UNNAMED = "(unnamed)"

	private val ABORTED_CAUSES = setOf(RunEndCause.TIMEOUT_ABORT, RunEndCause.CRASH)

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
				"Example | Runs | Measured runs | Kernel wall s (median) | Whole-run wall s (median) | " +
				"Sim s (median) | Ratio (median) | Max concurrent | Budget exceeded | reservePath calls |"
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
		budgetLines(cells.flatMap { it.snapshots }).forEach(sb::appendLine)
		sb.appendLine()
	}

	/** The legend bullet for the Performance table. */
	fun legend(): String =
		"- **Performance (issue #1306)** — the run record's performance row, one row per parameter cell " +
			"and example, so wall time is never averaged across networks; `(unnamed)` is a run that named " +
			"no example. **Runs** counts every run of the row, aborted ones included; **Measured runs** " +
			"those with a kernel wall, the runs the medians are taken over. **Kernel wall s** is the wall " +
			"time the simulation kernel ran, **Whole-run wall s** the forked run as aiSweep timed it (JVM " +
			"start, network loading, run and persistence; aborted runs included), **Sim s** the simulated " +
			"time reached and **Ratio** Sim s ÷ kernel wall per run, times faster than real time. In a GUI " +
			"run the wall includes the speed pacing and pauses, so its ratio mostly shows the speed factor. " +
			"**Max concurrent** is the peak of admitted trains (max), **Budget exceeded** the route searches " +
			"stopped at their node budget, **reservePath calls** the public route requests (`reservePath`, " +
			"`reservePathToAny`, `reservePathToAnyNextSemaphore`, each call once; both summed), so it is " +
			"not comparable with the Goal 1B B5 `reservePath`-only count (for example shuntingLoop 32 → 47 " +
			"per run). Wall time " +
			"measures the machine, not the railway: it varies with load, and nothing in the gate reads it. " +
			"`n/a` = not measured (a file before schema version 12, an aborted run, the PERFORMANCE metric " +
			"group off, or no aiSweep timing). The SP11 wall budget below the table is reported against, " +
			"never asserted."

	private fun row(
		example: String?,
		runs: List<DispatcherRunSnapshot>
	): String {
		val outcomes = runs.map { it.railwayOutcome }
		return "| ${example ?: UNNAMED} " +
			"| ${runs.size} " +
			"| ${outcomes.count { it.wallSeconds != null }} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.wallSeconds }), 2)} " +
			"| ${fixed(medianOf(runs.mapNotNull { it.processWallSeconds }), 2)} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.simulationSeconds }), 1)} " +
			"| ${fixed(medianOf(outcomes.mapNotNull { it.realTimeRatio }), 1)} " +
			"| ${outcomes.mapNotNull { it.maxConcurrentTrains }.maxOrNull() ?: NOT_MEASURED} " +
			"| ${sumOf(outcomes.map { it.routeSearchBudgetExceeded })} " +
			"| ${sumOf(outcomes.map { it.reservePathCalls })} |"
	}

	/**
	 * The SP11 budget lines: the whole-run wall (what the budget is about, aborted runs included), the
	 * kernel wall of the run records, and the aborted runs, which have no kernel wall.
	 */
	private fun budgetLines(runs: List<DispatcherRunSnapshot>): List<String> {
		val aborted = runs.count { it.endCause in ABORTED_CAUSES }
		return listOf(
			"Wall-time budget (Goal 1B SP11, reported, never asserted): " +
				"$SP11_RUN_BUDGET_SECONDS s per run, $SP11_TOTAL_BUDGET_SECONDS s in total.",
			"",
			"- Whole run (forked JVM, measured by aiSweep): " + wallSummary(runs.mapNotNull { it.processWallSeconds }, "runs"),
			"- Kernel only (run record): " + wallSummary(runs.mapNotNull { it.railwayOutcome.wallSeconds }, "measured runs"),
			"- Aborted runs (TIMEOUT_ABORT or CRASH): $aborted, without a kernel wall; " +
				"their whole-run wall is in the Whole run line"
		)
	}

	private fun wallSummary(
		walls: List<Double>,
		noun: String
	): String {
		if (walls.isEmpty()) return "not measured"
		val over = walls.count { it > SP11_RUN_BUDGET_SECONDS }
		return "slowest ${fixed(walls.max(), 2)} s; $over of ${walls.size} $noun over $SP11_RUN_BUDGET_SECONDS s; " +
			"total ${fixed(walls.sum(), 2)} s of $SP11_TOTAL_BUDGET_SECONDS s"
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
