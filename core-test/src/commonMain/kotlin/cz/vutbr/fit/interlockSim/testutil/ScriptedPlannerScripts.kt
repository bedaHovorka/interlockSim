/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: shared scripts and the railway outcome of a scripted run (Issue #1479)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification

/**
 * Scripts for [ScriptedPlanner] shared by the `:core` and `:dispatcher-agent` tests (Issue #1479).
 *
 * @since Issue #1479 (Goal 9B SP5.6)
 */
object ScriptedPlannerScripts {
	/** Script name of [shuntingLoopTwoTrains]. */
	const val SHUNTING_LOOP_TWO_TRAINS: String = "shuntingLoop-two-trains"

	/**
	 * End time of a [shuntingLoopTwoTrains] run, in simulated seconds: both scripted trains have
	 * left the network by then, and the third generated train is still waiting for admission, so
	 * the run ends with natural completion.
	 */
	const val SHUNTING_LOOP_END_TIME: Long = 120L

	/**
	 * The fixed list on `shuntingLoop` (`vyhybna.xml`): the first generated train is admitted at
	 * tick 2 and routed zB → doA1 → A, the second at tick 27 and routed zA → doB1 → B. The ticks
	 * are those at which the trains are queued and their next signal is reached, the same steps the
	 * rule-based dispatcher takes on this station. Later trains stay queued.
	 */
	fun shuntingLoopTwoTrains(): ScriptedPlanner =
		ScriptedPlanner.fixedList(
			SHUNTING_LOOP_TWO_TRAINS,
			mapOf(
				2L to listOf(ScriptedCommand.ApproveTrain(1)),
				3L to listOf(ScriptedCommand.ReservePath(1, "zB", "doA1")),
				7L to listOf(ScriptedCommand.ReservePath(1, "doA1", "A")),
				27L to listOf(ScriptedCommand.ApproveTrain(2)),
				28L to listOf(ScriptedCommand.ReservePath(2, "zA", "doB1")),
				32L to listOf(ScriptedCommand.ReservePath(2, "doB1", "B"))
			)
		)
}

/**
 * The railway outcome of a scripted run that Issue #1479 compares across runs: trains entered,
 * trains exited, block transitions (route grants) and the end cause. It holds no train name, so
 * two runs in one process compare equal although the process-wide counter names their trains
 * differently.
 *
 * @since Issue #1479 (Goal 9B SP5.6)
 */
data class ScriptedRailwayOutcome(
	val trainsEntered: Int,
	val trainsExited: Int,
	val blockTransitions: Int,
	val endClassification: RunEndClassification?
)

/** The [ScriptedRailwayOutcome] of the run this environment has finished, from its run-end summary. */
fun SimulationEnvironment.scriptedRailwayOutcome(): ScriptedRailwayOutcome =
	getMetricsServices().getMetricsCollectionService().runEndSummary().let {
		ScriptedRailwayOutcome(
			trainsEntered = it.trainsEntered,
			trainsExited = it.trainsExited,
			blockTransitions = it.blockTransitions,
			endClassification = it.classification
		)
	}
