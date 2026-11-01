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
		reservePathCalls: Long?
	) = runSnapshot(
		runId = runId,
		example = example,
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
			"| Example | Runs | Wall s (median) | Sim s (median) | Ratio (median) | Max concurrent | " +
				"Budget exceeded | reservePath calls |"
		)
		val shunting = performanceCells(md, ExampleNames.SHUNTING_LOOP)
		assertThat(shunting.size, "column count").isEqualTo(PERFORMANCE_COLUMNS)
		assertThat(shunting.drop(EXAMPLE_COLUMN + 1), "shuntingLoop figures")
			.isEqualTo(listOf("2", "2.00", "600.0", "400.0", "2", "0", "62"))
		assertThat(performanceCells(md, ExampleNames.CERVENY_UJEZD).drop(EXAMPLE_COLUMN + 1), "cervenyUjezd figures")
			.isEqualTo(listOf("1", "4.00", "600.0", "150.0", "2", "0", "177"))
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
	fun `a run written before schema version 12 shows its performance as not measured`() {
		val md = render(listOf(run("v11", example = null, wallSeconds = null, reservePathCalls = null)))

		assertThat(performanceCells(md, "n/a").drop(EXAMPLE_COLUMN + 1))
			.isEqualTo(listOf("1", "n/a", "n/a", "n/a", "2", "n/a", "n/a"))
		assertThat(md).contains("Wall-time budget (Goal 1B SP11, reported, never asserted): not measured")
	}

	@Test
	fun `the SP11 wall budget is reported against the slowest run and the total`() {
		val md =
			render(
				listOf(
					run("s1", ExampleNames.SHUNTING_LOOP, wallSeconds = 1.5, reservePathCalls = 30L),
					run("s2", ExampleNames.SHUNTING_LOOP, wallSeconds = 16.0, reservePathCalls = 30L)
				)
			)

		assertThat(md).contains(
			"Wall-time budget (Goal 1B SP11, reported, never asserted): slowest run 16.00 s against 15 s per run " +
				"(1 of 2 runs over); all runs 17.50 s against 180 s in total"
		)
	}

	private companion object {
		/** Index of the Example cell: after the 11 parameter-identity cells. */
		const val EXAMPLE_COLUMN = 11
		const val PERFORMANCE_COLUMNS = 19
	}
}
