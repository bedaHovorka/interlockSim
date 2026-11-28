/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.all
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import assertk.assertions.isZero
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.CleanRunRule
import cz.vutbr.fit.interlockSim.dispatcher.planner.CommandSource
import cz.vutbr.fit.interlockSim.dispatcher.planner.CommandSourceKind
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.SilentCycleCause
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.ScriptedQueueRun
import cz.vutbr.fit.interlockSim.dispatcher.testutil.runScriptedThroughQueue
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.testutil.ScriptedPlanner
import cz.vutbr.fit.interlockSim.testutil.ScriptedPlannerScripts
import cz.vutbr.fit.interlockSim.testutil.ScriptedRailwayOutcome
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The scripted high-level command source of Issue #1479 fed into today's [ActuatorCommandQueue]
 * through the test adapter [runScriptedThroughQueue], on `shuntingLoop` (`vyhybna.xml`).
 *
 * The adapter runs inline on the simulation thread (O = N = D, ADR part 1 §3.4 of #1474): it posts
 * the commands of a tick with author `LLM` and drains them through [DispatchDecisionApplier] in the
 * same control step, and it records the run's command source as scripted with the script name.
 *
 * @since Issue #1479 (Goal 9B SP5.6)
 */
@DisplayName("ScriptedPlanner through ActuatorCommandQueue (Issue #1479)")
class ScriptedPlannerQueueRunTest : DispatcherKoinTestBase() {
	private fun run(planner: ScriptedPlanner): ScriptedQueueRun =
		runScriptedThroughQueue(
			context = TestFixtures.newShuntingSimulationContext().tracked(),
			planner = planner,
			endTime = ScriptedPlannerScripts.SHUNTING_LOOP_END_TIME
		)

	@Test
	fun `every scripted command carries author LLM and the run is marked scripted, read back from the run JSON`(
		@TempDir dir: Path
	) {
		val run = run(ScriptedPlannerScripts.shuntingLoopTwoTrains())
		val store = DefaultRunSnapshotStore(dir)
		store.write(run.snapshot)

		val read = store.readAll(dir).single()

		assertThat(read).all {
			prop(DispatcherRunSnapshot::commandSource)
				.isEqualTo(CommandSource(CommandSourceKind.SCRIPTED, ScriptedPlannerScripts.SHUNTING_LOOP_TWO_TRAINS))
			// Six scripted commands, all applied and all LLM; no other author acted.
			prop(DispatcherRunSnapshot::actionsByAuthor)
				.transform { byAuthor -> byAuthor.filterValues { it != 0L } }
				.isEqualTo(mapOf(ActionAuthor.LLM.name to SCRIPTED_COMMANDS))
			prop(DispatcherRunSnapshot::unattributedApplies).isZero()
		}
		assertThat(CleanRunRule.violations(read)).isEmpty()
		assertThat(run.railwayOutcome.trainsExited).isEqualTo(2)
	}

	@Test
	fun `silent mode on shuntingLoop records no action, admits no train and counts every silent cycle by cause`() {
		val run = run(ScriptedPlanner.silent(SILENT_SCRIPT))

		assertThat(
			run.snapshot.actionsByAuthor.values
				.sum(),
			name = "recorded actions"
		).isZero()
		assertThat(run.loop.getMaxConcurrentTrains(), name = "trains ever admitted at once").isZero()
		assertThat(run.loop.getApprovedTrains()).isEmpty()
		assertThat(run.controlTicks, name = "control ticks").isGreaterThan(0L)
		// A scripted silence is an explicit decision to do nothing: an LLM no_op cycle.
		assertThat(run.snapshot.silentCyclesByCause).isEqualTo(
			mapOf(
				SilentCycleCause.TIMEOUT.name to 0L,
				SilentCycleCause.THINKING_TRUNCATED.name to 0L,
				SilentCycleCause.EMPTY_PLAN.name to 0L,
				SilentCycleCause.NO_OP.name to run.controlTicks
			)
		)
		assertThat(run.snapshot.totalTicks).isEqualTo(run.controlTicks)
		assertThat(run.snapshot.commandSource).isEqualTo(CommandSource(CommandSourceKind.SCRIPTED, SILENT_SCRIPT))
		// Holds until the #1485 idle-work trigger: in silent mode trains wait with actionable work, so
		// #1485 ends such a run as failed and flips this to not clean with its end cause. #1485
		// converts this assertion; it does not delete it.
		assertThat(CleanRunRule.isClean(run.snapshot), name = "a silent run is clean").isTrue()
	}

	@Test
	@Tag("integration-test")
	fun `the fixed list on shuntingLoop gives equal railway outcomes over ten runs`() {
		val outcomes = (1..RUNS).map { run(ScriptedPlannerScripts.shuntingLoopTwoTrains()).railwayOutcome }

		assertThat(outcomes.toSet(), name = "distinct railway outcomes of $RUNS runs").isEqualTo(
			setOf(
				ScriptedRailwayOutcome(
					trainsEntered = 3,
					trainsExited = 2,
					blockTransitions = 4,
					endClassification = RunEndClassification.NATURAL_COMPLETION
				)
			)
		)
		// The end cause the adapter persists follows the run-end classification.
		assertThat(run(ScriptedPlannerScripts.shuntingLoopTwoTrains()).snapshot.endCause)
			.isEqualTo(RunEndCause.NATURAL_COMPLETION)
	}

	private companion object {
		const val SILENT_SCRIPT = "silent"
		const val RUNS = 10

		/** Commands of [ScriptedPlannerScripts.shuntingLoopTwoTrains]: two approvals, four path reservations. */
		const val SCRIPTED_COMMANDS = 6L
	}
}
