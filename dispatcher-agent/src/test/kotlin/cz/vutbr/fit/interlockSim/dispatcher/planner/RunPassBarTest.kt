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
import assertk.assertions.doesNotContain
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.ExampleNames
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The exit term of [RunReportAggregator.runPassed] under the per-example bar (Issue #1315).
 *
 * Ruling (traffic-simulation-expert, railway-civil-engineer opinion, gemma4 ACCEPT): wrong exits
 * count against a pass by **subtraction**, not as a veto: only the trains that left by their
 * timetabled InOut count toward the bar of the run's example. An example without a measured bar
 * gets no exit check, and the report says "not measured" on its verdict.
 *
 * @since Issue #1315 (Goal 6)
 */
class RunPassBarTest {
	private val aggregator = RunReportAggregator(mockk(relaxed = true))

	private fun run(
		example: String?,
		exited: Long?,
		wrongExits: Long? = null,
		runId: String = "r"
	) = runSnapshot(
		runId = runId,
		example = example,
		railwayOutcome = RailwayOutcome(trainsEntered = 19L, trainsExited = exited, wrongExits = wrongExits)
	)

	// ── Subtraction ───────────────────────────────────────────────────────────

	@Test
	fun `wrong exits that push the own-InOut exits below the bar fail the run`() {
		// cervenyUjezd bar 10: 12 exits, 3 of them by another InOut -> 9 by the own InOut.
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = 12L, wrongExits = 3L))).isFalse()
	}

	@Test
	fun `the same exits without wrong exits pass the run`() {
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = 12L, wrongExits = 0L))).isTrue()
	}

	@Test
	fun `own-InOut exits exactly at the bar pass the run`() {
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = 13L, wrongExits = 3L))).isTrue()
	}

	@Test
	fun `an absent wrong-exit count subtracts nothing`() {
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = 10L, wrongExits = null))).isTrue()
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = 9L, wrongExits = null))).isFalse()
	}

	@Test
	fun `an absent exit count still passes the exit term`() {
		assertThat(aggregator.runPassed(run(ExampleNames.CERVENY_UJEZD, exited = null, wrongExits = 5L))).isTrue()
	}

	@Test
	fun `each example is read against its own bar`() {
		// 5 own exits: enough for Praha (bar 4), too few for vyhybna shuntingLoop (bar 6).
		assertThat(aggregator.runPassed(run(ExampleNames.PRAHA_HLAVNI_NADRAZI, exited = 5L, wrongExits = 0L))).isTrue()
		assertThat(aggregator.runPassed(run(ExampleNames.SHUNTING_LOOP, exited = 5L, wrongExits = 0L))).isFalse()
	}

	@Test
	fun `an example without a bar gets no exit check`() {
		assertThat(aggregator.runPassed(run(UNKNOWN_EXAMPLE, exited = 0L, wrongExits = 0L))).isTrue()
	}

	// ── #895 verdicts do not move ─────────────────────────────────────────────

	/**
	 * The #895 campaign (vyhybna, 600 s) ran `shuntingLoop` and `shuntingLoopAI`, wrote its files
	 * before schema version 11 (no wrong-exit count) and before schema version 13 (no example).
	 * Its run JSONs were deleted on 2026-08-24, so the verdicts are pinned on its documented
	 * figures: 11 exits on every control run, 0 to 11 on the LLM runs, against the old rule
	 * `trainsExited >= 6`. The literal schema-version-10 control-arm document is pinned in
	 * `DefaultRunSnapshotStoreTest`.
	 */
	@ParameterizedTest(name = "example={0} exits={1}")
	@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.planner.RunPassBarTest#issue895Runs")
	fun `the issue 895 verdicts do not move`(
		example: String?,
		exited: Long
	) {
		val before = exited >= OLD_BAR_895
		assertThat(aggregator.runPassed(run(example, exited = exited, wrongExits = null))).isEqualTo(before)
	}

	// ── Report ────────────────────────────────────────────────────────────────

	@Test
	fun `an example without a bar shows not measured on its verdict`() {
		val md = aggregator.renderMarkdown(listOf(aggregator.aggregate(listOf(run(UNKNOWN_EXAMPLE, exited = 4L)))))

		val gate = rowCells(sectionRow(md, "### Decision Hygiene", "### Railway Outcomes"))[GATE_COLUMN]
		assertThat(gate).contains(RunReportAggregator.EXIT_BAR_NOT_MEASURED)
		val outcome = rowCells(sectionRow(md, "### Railway Outcomes", "### Performance"))
		assertThat(outcome[EXIT_BAR_COLUMN], "exit bar").isEqualTo(RunReportAggregator.EXIT_BAR_NOT_MEASURED)
	}

	@Test
	fun `an example with a bar shows the bar and the own-InOut exits`() {
		val runs =
			listOf(
				run(ExampleNames.CERVENY_UJEZD, exited = 19L, wrongExits = 2L, runId = "c1"),
				run(ExampleNames.CERVENY_UJEZD, exited = 17L, wrongExits = 0L, runId = "c2")
			)
		val md = aggregator.renderMarkdown(listOf(aggregator.aggregate(runs)))

		val header = rowCells(sectionHeader(md, "### Railway Outcomes", "### Performance"))
		assertThat(header[OWN_EXITS_COLUMN]).isEqualTo("Exits by own InOut")
		assertThat(header[EXIT_BAR_COLUMN]).isEqualTo("Exit bar (per run)")
		val outcome = rowCells(sectionRow(md, "### Railway Outcomes", "### Performance"))
		assertThat(outcome.size, "column count").isEqualTo(OUTCOMES_COLUMNS)
		assertThat(outcome[OWN_EXITS_COLUMN], "own exits summed").isEqualTo("34")
		assertThat(outcome[EXIT_BAR_COLUMN], "bar").isEqualTo("10")
		val gate = rowCells(sectionRow(md, "### Decision Hygiene", "### Railway Outcomes"))[GATE_COLUMN]
		assertThat(gate).doesNotContain(RunReportAggregator.EXIT_BAR_NOT_MEASURED)
	}

	@Test
	fun `Arm Comparison counts the pooled runs that had no exit bar`() {
		val runs =
			listOf(
				run(ExampleNames.CERVENY_UJEZD, exited = 19L, wrongExits = 0L, runId = "c1"),
				run(UNKNOWN_EXAMPLE, exited = 4L, runId = "x1")
			)

		val gate = rowCells(armComparisonRow(aggregator.renderMarkdown(aggregator.aggregateByArm(runs))))[ARM_GATE_COLUMN]

		assertThat(gate).endsWith("${RunReportAggregator.EXIT_BAR_NOT_MEASURED} for 1 of 2 runs")
	}

	@Test
	fun `Arm Comparison marks an arm whose runs all had no exit bar`() {
		val runs = listOf(run(UNKNOWN_EXAMPLE, exited = 4L, runId = "x1"), run(UNKNOWN_EXAMPLE, exited = 5L, runId = "x2"))

		val gate = rowCells(armComparisonRow(aggregator.renderMarkdown(aggregator.aggregateByArm(runs))))[ARM_GATE_COLUMN]

		assertThat(gate).endsWith(" · ${RunReportAggregator.EXIT_BAR_NOT_MEASURED}")
	}

	@Test
	fun `the legend says the bar counts own-InOut exits and that Arm Comparison pools bars`() {
		val md = aggregator.renderMarkdown(listOf(aggregator.aggregate(listOf(run(ExampleNames.SHUNTING_LOOP, 11L)))))

		assertThat(md).contains("- **Exits by own InOut / Exit bar (issue #1315)**")
		assertThat(md).contains("Arm Comparison pools runs judged against different bars")
	}

	private fun sectionHeader(
		md: String,
		start: String,
		end: String
	): String =
		md
			.substringAfter(start)
			.substringBefore(end)
			.lines()
			.first { it.startsWith("| Arm ") }

	private fun sectionRow(
		md: String,
		start: String,
		end: String
	): String =
		md.substringAfter(start).substringBefore(end).lines().first {
			it.startsWith("| ${DispatcherArm.RULE_BASED} ")
		}

	private fun armComparisonRow(md: String): String =
		md.substringAfter("## Arm Comparison").substringBefore("## ").lines().first {
			it.startsWith("| ${DispatcherArm.RULE_BASED} ")
		}

	private fun rowCells(line: String): List<String> =
		line
			.split("|")
			.drop(1)
			.dropLast(1)
			.map { it.trim() }

	companion object {
		/** A GUI run from an XML file is named by the file (Issue #1328); it has no measured bar. */
		private const val UNKNOWN_EXAMPLE = "vyhybna.xml"

		/** The constant `trainsExited >= 6` rule the #895 verdicts were read against. */
		private const val OLD_BAR_895 = 6L

		/** Gate cell of the Decision Hygiene table: the Example column, then Runs, Passing, Gate. */
		private const val GATE_COLUMN = 14

		/** Gate cell of the Arm Comparison table: Arm, Runs, Passing, Gate. */
		private const val ARM_GATE_COLUMN = 3

		private const val OWN_EXITS_COLUMN = 23
		private const val EXIT_BAR_COLUMN = 24
		private const val OUTCOMES_COLUMNS = 25

		@JvmStatic
		fun issue895Runs(): List<Arguments> =
			listOf(null, ExampleNames.SHUNTING_LOOP, ExampleNames.SHUNTING_LOOP_AI).flatMap { example ->
				(0L..15L).map { exited -> Arguments.of(example, exited) }
			}
	}
}
