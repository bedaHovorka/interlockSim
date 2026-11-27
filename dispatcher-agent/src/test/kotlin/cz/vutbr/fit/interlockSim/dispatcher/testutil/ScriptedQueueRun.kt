/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.CommandSource
import cz.vutbr.fit.interlockSim.dispatcher.planner.CommandSourceKind
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
import cz.vutbr.fit.interlockSim.dispatcher.planner.toRunEndCause
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.ScriptedPlanner
import cz.vutbr.fit.interlockSim.testutil.ScriptedRailwayOutcome
import cz.vutbr.fit.interlockSim.testutil.scriptedRailwayOutcome

/**
 * What [runScriptedThroughQueue] returns: the loop, the frozen run snapshot, the railway outcome and
 * the number of control ticks the planner was called on.
 */
class ScriptedQueueRun(
	val loop: ShuntingLoop,
	val snapshot: DispatcherRunSnapshot,
	val railwayOutcome: ScriptedRailwayOutcome,
	val controlTicks: Long
)

/**
 * The small adapter of Issue #1479 that feeds a [ScriptedPlanner] into today's [ActuatorCommandQueue],
 * runs `shuntingLoop` on [context] until [endTime] and records the run. It lives in the test sources
 * of `:dispatcher-agent` because `:core-test` must not depend on this module; #1478 re-points it to
 * the high-level queue.
 *
 * Inline, as ADR part 1 §3.4 of #1474 rules for CI twins: in each control step, on the simulation
 * thread, after the tick's observation is published, it calls the planner once with that
 * observation, posts the commands to the queue and drains them through [DispatchDecisionApplier] in
 * the same step (O = N = D). There is no driver thread and no wait.
 *
 * Recording, so the run is judged like an LLM run (ruling Q2 of #1474):
 * - every command is posted with author [ActionAuthor.LLM], explicitly, and registered in a
 *   [CommandCorrelationMap], so the applier never falls back to a default author and an applied
 *   command is never unattributed;
 * - each control step is one tick: [TickOutcome.LLM_ACTIONS] when the planner wrote a command,
 *   [TickOutcome.LLM_NO_OP] when it was silent (a scripted silence is an explicit decision to do
 *   nothing, counted under the `NO_OP` silent-cycle cause);
 * - the run-level command source is [CommandSourceKind.SCRIPTED] with the script name;
 * - the end cause follows the run-end classification of the context.
 */
fun runScriptedThroughQueue(
	context: DefaultSimulationContext,
	planner: ScriptedPlanner,
	endTime: Long
): ScriptedQueueRun {
	context.getInOuts()
	val loop = ShuntingLoop(context, endTime)
	val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
	val correlationMap = CommandCorrelationMap()
	val queue = ActuatorCommandQueue(correlationMap = correlationMap)
	val recorder =
		DefaultDispatcherRunRecorder(
			runId = "scripted-${planner.name}",
			arm = DispatcherArm.LLM_TOOL_CALLING,
			params = scriptedRunParameters(planner)
		)
	recorder.recordCommandSource(CommandSource(CommandSourceKind.SCRIPTED, planner.name))
	val applier =
		DispatchDecisionApplier(
			queue = queue,
			networkActuator = DefaultNetworkActuatorPort(env = context),
			onApproveTrain = loop::approveQueuedTrain,
			activeTrainCountProvider = { loop.getApprovedTrains().size },
			maxConcurrentTrains = loop.stationCapacity,
			onBlockTransition = loop::incrementBlockTransition,
			onFailedReservation = loop::incrementFailedReservation,
			correlationMap = correlationMap,
			actionOutcomeSink = recorder::onActionOutcome
		)
	var controlTicks = 0L
	loop.snapshotCaptureHook = perception::captureSnapshot
	loop.controlStepListener =
		ControlStepListener {
			val observation = loop.latestDispatchLoopSnapshot()
			queue.advanceCorrelationCycle()
			val commands = planner.commandsAt(observation)
			check(queue.postAll(commands, ActionAuthor.LLM, "script ${planner.name}")) {
				"The command queue refused the scripted commands of tick ${observation.tick}"
			}
			val outcome = if (commands.isEmpty()) TickOutcome.LLM_NO_OP else TickOutcome.LLM_ACTIONS
			recorder.onTick(TickRecord(outcome, simTime = observation.snapshot.simTime))
			controlTicks++
			applier.onControlStep()
		}
	context.setMainProcess(loop)
	context.run()

	val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
	val cause = checkNotNull(summary.classification) { "The run ended without a run-end classification" }
	return ScriptedQueueRun(
		loop = loop,
		snapshot = recorder.finish(cause.toRunEndCause()),
		railwayOutcome = context.scriptedRailwayOutcome(),
		controlTicks = controlTicks
	)
}

/** Run parameters of a scripted run: no model, so the model field names the script. */
private fun scriptedRunParameters(planner: ScriptedPlanner): RunParameters =
	RunParameters(
		tickPeriodMs = 0L,
		historyN = 0,
		temperature = 0.0,
		maxActionsPerTick = Int.MAX_VALUE,
		model = "scripted:${planner.name}",
		seed = null
	)
