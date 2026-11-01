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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * How the run-record figures of schema version 11 reach `report.md` and the arm gate
 * (Issue #676, Goal 6 SP5 fix round 1).
 *
 * Rulings (gemma4 as traffic-simulation-expert): [RunReportAggregator.runPassed] does not fail a run
 * on wrong exits or on a cutoff; the Railway Outcomes table makes both visible instead. Runs without
 * any dispatcher are their own arm, [DispatcherArm.NO_DISPATCHER], which never passes the arm gate.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class RunRecordReportTest {
	private val aggregator = RunReportAggregator(mockk(relaxed = true))

	private fun record(
		wrongExits: Long?,
		classification: RunEndClassification?
	) = RailwayOutcome(
		journeysCompleted = 17L,
		trainsEntered = 19L,
		trainsExited = 17L,
		maxConcurrentTrains = 4L,
		blockTransitions = 35L,
		conflicts = 0L,
		failedReservations = 142L,
		unresolvedAtRunEnd = 0L,
		wrongExits = wrongExits,
		endClassification = classification
	)

	private fun outcomeCells(
		md: String,
		arm: DispatcherArm
	): List<String> =
		md
			.substringAfter("### Railway Outcomes")
			.lines()
			.first { it.startsWith("| $arm ") }
			.split("|")
			.map { it.trim() }
			.drop(1)
			.dropLast(1)

	@Test
	fun `Railway Outcomes shows the wrong exits and the cutoff runs after the existing columns`() {
		val runs =
			listOf(
				runSnapshot(runId = "r1", railwayOutcome = record(10L, RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)),
				runSnapshot(runId = "r2", railwayOutcome = record(0L, RunEndClassification.NATURAL_COMPLETION))
			)

		val md = aggregator.renderMarkdown(listOf(aggregator.aggregate(runs)))

		val header = md.substringAfter("### Railway Outcomes").lines().first { it.startsWith("| Arm ") }
		assertThat(header).contains("| Unresolved at run end | Wrong exits | Cutoff runs |")
		val cells = outcomeCells(md, DispatcherArm.RULE_BASED)
		assertThat(cells.size, "column count").isEqualTo(22)
		assertThat(cells[19], "unresolved at run end, unmoved").isEqualTo("0")
		assertThat(cells[20], "wrong exits summed").isEqualTo("10")
		assertThat(cells[21], "cutoff runs counted").isEqualTo("1")
		assertThat(md).contains("- **Wrong exits / Cutoff runs (issue #676)**")
	}

	@Test
	fun `a file written before schema version 11 shows the two figures as not measured`() {
		val runs = listOf(runSnapshot(runId = "v10", railwayOutcome = record(null, null)))

		val cells = outcomeCells(aggregator.renderMarkdown(listOf(aggregator.aggregate(runs))), DispatcherArm.RULE_BASED)

		assertThat(cells[20], "wrong exits").isEqualTo("n/a")
		assertThat(cells[21], "cutoff runs").isEqualTo("n/a")
	}

	@Test
	fun `a run is not failed for its wrong exits or for being cut off`() {
		val cutOff = runSnapshot(railwayOutcome = record(10L, RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS))

		assertThat(aggregator.runPassed(cutOff), "runPassed").isTrue()
	}

	@Test
	fun `ten clean runs without a dispatcher never pass the arm gate`() {
		val runs = (1..10).map { runSnapshot(runId = "nd-$it", arm = DispatcherArm.NO_DISPATCHER) }

		val report = aggregator.aggregate(runs)

		assertThat(report.passingRuns, "passing runs").isEqualTo(10)
		assertThat(report.gatePassed, "gate").isFalse()
		val md = aggregator.renderMarkdown(listOf(report))
		assertThat(md).contains("| ${DispatcherArm.NO_DISPATCHER} | 10 | 10 | n/a (no dispatcher) |")
		assertThat(outcomeCells(md, DispatcherArm.NO_DISPATCHER)[1], "model").isEqualTo("none")
	}

	@Test
	fun `ten clean rule-based runs still pass the arm gate`() {
		val report = aggregator.aggregate((1..10).map { runSnapshot(runId = "rb-$it") })

		assertThat(report.gatePassed, "gate").isTrue()
	}

	@Test
	fun `the report lists the no-dispatcher arm only when it has runs`() {
		val withoutIt = aggregator.aggregateByArm(listOf(runSnapshot(runId = "rb")))
		val withIt = aggregator.aggregateByArm(listOf(runSnapshot(runId = "nd", arm = DispatcherArm.NO_DISPATCHER)))

		assertThat(withoutIt.map { it.arm }, "arms without a no-dispatcher run")
			.isEqualTo(listOf(DispatcherArm.RULE_BASED, DispatcherArm.LLM_TOOL_CALLING, DispatcherArm.LLM_CONSTRAINED_JSON))
		assertThat(withIt.map { it.arm }, "arms with a no-dispatcher run").contains(DispatcherArm.NO_DISPATCHER)
		assertThat(aggregator.renderMarkdown(withoutIt)).doesNotContain("| ${DispatcherArm.NO_DISPATCHER} |")
	}
}
