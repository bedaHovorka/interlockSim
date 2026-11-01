/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.ExampleNames
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The per-network Performance table of `report.md` (Issue #1306, Goal 6 SP8): wall against simulated
 * time, the routing counts, and the Goal 1B SP11 wall budget reported against, never asserted.
 *
 * Ruling (gemma4 as traffic-simulation-expert, B2): the performance figures get their own table, one
 * row per parameter cell and example, so a mixed campaign never averages wall time across networks;
 * the #895 tables keep their layout. The wall figures here are synthetic inputs, not measurements.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class RunPerformanceReportTest {
	private val aggregator = RunReportAggregator(mockk(relaxed = true))

	private fun run(
		runId: String,
		example: String?,
		wallSeconds: Double?,
		reservePathCalls: Long?,
		processWallSeconds: Double? = wallSeconds?.let { it + 1.0 }
	) = runSnapshot(
		runId = runId,
		example = example,
		processWallSeconds = processWallSeconds,
		railwayOutcome =
			RailwayOutcome(
				trainsExited = 15L,
				maxConcurrentTrains = 2L,
				wallSeconds = wallSeconds,
				simulationSeconds = wallSeconds?.let { 600.0 },
				realTimeRatio = wallSeconds?.let { 600.0 / it },
				routeSearchBudgetExceeded = wallSeconds?.let { 0L },
				reservePathCalls = reservePathCalls
			)
	)

	private fun render(runs: List<DispatcherRunSnapshot>): String =
		aggregator.renderMarkdown(listOf(aggregator.aggregate(runs)))

	/** The cells of the Performance row of [example]; the Example cell follows the 11 parameter cells. */
	private fun performanceCells(
		md: String,
		example: String
	): List<String> =
		md
			.substringAfter("### Performance")
			.lines()
			.map { line ->
				line
					.split("|")
					.map { it.trim() }
					.drop(1)
					.dropLast(1)
			}.first { it.size > EXAMPLE_COLUMN && it[EXAMPLE_COLUMN] == example }

	@Test
	fun `each network of a cell gets its own row with medians of the wall figures and sums of the counts`() {
		val md =
			render(
				listOf(
					run("s1", ExampleNames.SHUNTING_LOOP, wallSeconds = 1.0, reservePathCalls = 30L),
					run("s2", ExampleNames.SHUNTING_LOOP, wallSeconds = 3.0, reservePathCalls = 32L),
					run("c1", ExampleNames.CERVENY_UJEZD, wallSeconds = 4.0, reservePathCalls = 177L)
				)
			)

		val header = md.substringAfter("### Performance").lines().first { it.startsWith("| Arm ") }
		assertThat(header).contains(
			"| Example | Runs | Measured runs | Kernel wall s (median) | Whole-run wall s (median) | " +
				"Sim s (median) | Ratio (median) | Max concurrent | Budget exceeded | reservePath calls |"
		)
		val shunting = performanceCells(md, ExampleNames.SHUNTING_LOOP)
		assertThat(shunting.size, "column count").isEqualTo(PERFORMANCE_COLUMNS)
		assertThat(shunting.drop(EXAMPLE_COLUMN + 1), "shuntingLoop figures")
			.isEqualTo(listOf("2", "2", "2.00", "3.00", "600.0", "400.0", "2", "0", "62"))
		assertThat(performanceCells(md, ExampleNames.CERVENY_UJEZD).drop(EXAMPLE_COLUMN + 1), "cervenyUjezd figures")
			.isEqualTo(listOf("1", "1", "4.00", "5.00", "600.0", "150.0", "2", "0", "177"))
	}

	@Test
	fun `the Railway Outcomes table keeps its 22 columns`() {
		val md = render(listOf(run("s1", ExampleNames.SHUNTING_LOOP, wallSeconds = 1.0, reservePathCalls = 30L)))

		val row =
			md
				.substringAfter("### Railway Outcomes")
				.substringBefore("### Performance")
				.lines()
				.first { it.startsWith("| ${DispatcherArm.RULE_BASED} ") }
		assertThat(row.split("|").size - 2, "outcomes columns").isEqualTo(22)
	}

	@Test
	fun `a run written before schema version 12 shows an unnamed network and its performance as not measured`() {
		val md = render(listOf(run("v11", example = null, wallSeconds = null, reservePathCalls = null)))

		assertThat(performanceCells(md, UNNAMED).drop(EXAMPLE_COLUMN + 1))
			.isEqualTo(listOf("1", "0", "n/a", "n/a", "n/a", "n/a", "2", "n/a", "n/a"))
		assertThat(md).contains("- Whole run (forked JVM, measured by aiSweep): not measured")
		assertThat(md).contains("- Kernel only (run record): not measured")
		assertThat(md).contains("not comparable with the Goal 1B B5 `reservePath`-only count")
	}

	@Test
	fun `the SP11 wall budget is reported for the whole runs and the kernel, aborted runs included`() {
		val aborted =
			runSnapshot(runId = "t1", example = ExampleNames.SHUNTING_LOOP, processWallSeconds = 900.0)
				.copy(endCause = RunEndCause.TIMEOUT_ABORT)
		val md =
			render(
				listOf(
					run("s1", ExampleNames.SHUNTING_LOOP, wallSeconds = 1.5, reservePathCalls = 30L, processWallSeconds = 2.0),
					run("s2", ExampleNames.SHUNTING_LOOP, wallSeconds = 16.0, reservePathCalls = 30L, processWallSeconds = 17.0),
					aborted
				)
			)

		assertThat(md).contains("Wall-time budget (Goal 1B SP11, reported, never asserted): 15 s per run, 180 s in total.")
		assertThat(md).contains(
			"- Whole run (forked JVM, measured by aiSweep): slowest 900.00 s; 2 of 3 runs over 15 s; total 919.00 s of 180 s"
		)
		assertThat(md).contains(
			"- Kernel only (run record): slowest 16.00 s; 1 of 2 measured runs over 15 s; total 17.50 s of 180 s"
		)
		assertThat(md).contains(
			"- Aborted runs (TIMEOUT_ABORT or CRASH): 1, without a kernel wall; their whole-run wall is in the Whole run line"
		)
		assertThat(performanceCells(md, ExampleNames.SHUNTING_LOOP)[EXAMPLE_COLUMN + 2], "measured runs").isEqualTo("2")
	}

	private companion object {
		/** Index of the Example cell: after the 11 parameter-identity cells. */
		const val EXAMPLE_COLUMN = 11
		const val PERFORMANCE_COLUMNS = 21
		const val UNNAMED = "(unnamed)"
	}
}
