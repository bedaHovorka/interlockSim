/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.ProvidesDispatchLoopObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation

/**
 * [DispatchLoopSensorPort] backed by [context]'s main process, resolved lazily at query time.
 *
 * Backs [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]'s `queued_trains` /
 * `block_inputs` tools — without a live sensor port here the LLM dispatcher has no way to see
 * which trains are queued or which block inputs need a path reservation (Goal 10
 * dispatcher-cannot-approve-trains fix).
 *
 * Uses the same interface-based lookup as [mainProcessActiveTrains] (PR #769 rationale): casting
 * to [ProvidesDispatchLoopObservation] — a plain interface — instead of naming
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop] directly keeps kDisco's `Process` type off
 * `:dispatcher-agent`'s main compile classpath (`kdisco-core-jvm` is `testImplementation`-only
 * here; see `dispatcher-agent/build.gradle.kts`).
 *
 * Falls back to [DispatchLoopSnapshot.EMPTY] when there is no main process yet, or it does not
 * implement [ProvidesDispatchLoopObservation] (e.g. `MultiTrainLoop`/`ThreeTrainLoop`, which do
 * not use the AI dispatch-loop tools today).
 *
 * @since Goal 10 dispatcher-cannot-approve-trains fix
 */
class MainProcessDispatchLoopSensorPort(
	private val context: DefaultSimulationContext
) : DispatchLoopSensorPort {
	private fun snapshotOrEmpty(): DispatchLoopSnapshot =
		(context.getMainProcess() as? ProvidesDispatchLoopObservation)?.latestDispatchLoopSnapshot()
			?: DispatchLoopSnapshot.EMPTY

	override fun snapshot(): DispatchLoopSnapshot = snapshotOrEmpty()

	override fun getQueuedTrains(): List<QueuedTrainObservation> = snapshotOrEmpty().queuedTrains

	override fun getInnerBlockInputs(): List<BlockInputObservation> = snapshotOrEmpty().innerBlockInputs

	override fun getOuterBlockInputs(): List<BlockInputObservation> = snapshotOrEmpty().outerBlockInputs
}
