/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.HighLevelCommandSource
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.ScriptedPlanner
import cz.vutbr.fit.interlockSim.testutil.ScriptedPlannerScripts
import cz.vutbr.fit.interlockSim.testutil.ScriptedRailwayOutcome
import cz.vutbr.fit.interlockSim.testutil.scriptedRailwayOutcome
import org.koin.core.component.get
import kotlin.test.Test

/**
 * The scripted high-level command source of Issue #1479 driving a real `shuntingLoop`
 * (`vyhybna.xml`) inline, on JVM and linuxX64 (commonTest).
 *
 * The run uses the inline path of ADR part 1 §3.4 of #1474: the source is called once per control
 * tick on the simulation thread, after the observation is published, and its commands are applied
 * in the same control step, so O = N = D. This class applies them with the synchronous wiring's
 * `applyDecision`; the `:dispatcher-agent` test adapter feeds the same source into
 * `ActuatorCommandQueue` (`ScriptedPlannerQueueRunTest`).
 *
 * @since Issue #1479 (Goal 9B SP5.6)
 */
class ScriptedPlannerInlineTest : CommonKoinTestBase() {
	private fun shuntingLoopContext(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.VYHYBNA_XML, get()).tracked()

	/**
	 * Runs `shuntingLoop` until [endTime] with [source] as its only dispatcher, inline: one
	 * [HighLevelCommandSource.commandsAt] call per control tick with that tick's published
	 * observation, its commands applied in the same step.
	 */
	private fun runInline(
		source: HighLevelCommandSource,
		endTime: Long = ScriptedPlannerScripts.SHUNTING_LOOP_END_TIME,
		onCall: (ShuntingLoop) -> Unit = {}
	): Pair<ShuntingLoop, ScriptedRailwayOutcome> {
		val context = shuntingLoopContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime)
		val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val actuator = DefaultNetworkActuatorPort(env = context)
		loop.snapshotCaptureHook = perception::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				onCall(loop)
				source.commandsAt(loop.latestDispatchLoopSnapshot()).forEach { applyDecision(it, loop, actuator) }
			}
		context.setMainProcess(loop)
		context.run()
		return loop to context.scriptedRailwayOutcome()
	}

	@Test
	fun `the fixed list drives two trains through shuntingLoop and back out`() {
		val (_, outcome) = runInline(ScriptedPlannerScripts.shuntingLoopTwoTrains())

		assertThat(outcome).isEqualTo(
			ScriptedRailwayOutcome(
				// Entered counts every train queued at its entry InOut: the third one is generated
				// before the end time and never admitted, so it is no unfinished journey.
				trainsEntered = 3,
				trainsExited = 2,
				blockTransitions = 4,
				endClassification = RunEndClassification.NATURAL_COMPLETION
			)
		)
	}

	@Test
	fun `the fixed list writes exactly its commands at their ticks for the generated trains`() {
		val planner = ScriptedPlannerScripts.shuntingLoopTwoTrains()
		val written = mutableMapOf<Long, List<DispatchDecision>>()
		val recording =
			HighLevelCommandSource { observation ->
				planner.commandsAt(observation).also { if (it.isNotEmpty()) written[observation.tick] = it }
			}

		runInline(recording)

		val first = planner.generatedTrains.nameOf(1)
		val second = planner.generatedTrains.nameOf(2)
		assertThat(written).isEqualTo(
			mapOf(
				2L to listOf(DispatchDecision.ApproveTrain(first)),
				3L to listOf(DispatchDecision.ReservePath(first, "zB", "doA1")),
				7L to listOf(DispatchDecision.ReservePath(first, "doA1", "A")),
				27L to listOf(DispatchDecision.ApproveTrain(second)),
				28L to listOf(DispatchDecision.ReservePath(second, "zA", "doB1")),
				32L to listOf(DispatchDecision.ReservePath(second, "doB1", "B"))
			)
		)
	}

	@Test
	fun `two fixed-list runs in one process give equal railway outcomes although train names differ`() {
		val firstPlanner = ScriptedPlannerScripts.shuntingLoopTwoTrains()
		val secondPlanner = ScriptedPlannerScripts.shuntingLoopTwoTrains()

		val (_, first) = runInline(firstPlanner)
		val (_, second) = runInline(secondPlanner)

		assertThat(second).isEqualTo(first)
		// The process-wide counter names the trains of the second run differently; the script
		// addresses them by generation ordinal, so both runs still route the same trains.
		assertThat(secondPlanner.generatedTrains.nameOf(1)).isNotEqualTo(firstPlanner.generatedTrains.nameOf(1))
	}

	@Test
	fun `silent mode admits no train and is still called on every control tick`() {
		val planner = ScriptedPlanner.silent("silent")
		var publishedTicks = 0L

		val (loop, outcome) = runInline(planner) { publishedTicks = it.getLatestObservation().tick }

		assertThat(outcome.trainsExited).isZero()
		assertThat(outcome.blockTransitions).isZero()
		assertThat(loop.getMaxConcurrentTrains(), name = "trains ever admitted at once").isZero()
		assertThat(loop.getApprovedTrains()).isEmpty()
		assertThat(loop.getQueuedTrains()).isNotEmpty()
		assertThat(planner.calledTicks).isEqualTo((1L..publishedTicks).toList())
	}

	@Test
	fun `a rule-driven callback sees the observation of the tick it is called on`() {
		val seen = mutableListOf<Pair<Long, Long>>()
		val queueMatches = mutableListOf<Boolean>()
		var loopRef: ShuntingLoop? = null
		val planner =
			ScriptedPlanner.ruleDriven("approve-queued") { observation, _ ->
				val live = checkNotNull(loopRef)
				seen += observation.tick to live.getLatestObservation().tick
				queueMatches += observation.queuedTrains == live.getQueuedTrains()
				observation.queuedTrains.map { DispatchDecision.ApproveTrain(it.trainId) }
			}

		runInline(planner) { loopRef = it }

		assertThat(seen).isNotEmpty()
		assertThat(seen.map { it.first }).isEqualTo(seen.map { it.second })
		assertThat(seen.map { it.first }).isEqualTo((1L..seen.size).toList())
		// The fields of the observation are those of the same tick, not only its stamp.
		assertThat(queueMatches.all { it }, name = "observed queue equals the tick's published queue").isTrue()
		assertThat(planner.generatedTrains.count, name = "trains the rule saw").isGreaterThan(0)
	}

	@Test
	fun `generated trains are numbered by first appearance in the observed queue`() {
		val planner = ScriptedPlanner.silent("silent")

		val (loop, _) = runInline(planner)

		assertThat(planner.generatedTrains.names).containsExactly(*loop.getQueuedTrains().map { it.trainId }.toTypedArray())
		assertThat(planner.generatedTrains.ordinalOf(planner.generatedTrains.nameOf(1))).isEqualTo(1)
	}
}
