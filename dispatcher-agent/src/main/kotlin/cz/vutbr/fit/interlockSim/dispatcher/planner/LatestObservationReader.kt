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

import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation

/**
 * Reads the dispatch state the simulation thread published most recently, for the rule-based
 * fallback that runs after a failed LLM cycle (Issue #1343, see
 * [KoogAgentPlanAdapter.latestObservationReader]).
 *
 * A read never requests or awaits a sim-thread capture — constraint C3 of the F1 paused-clock
 * ruling (`docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`). It only reads references the
 * simulation thread has already published, so it returns at once even while the clock is paused.
 */
fun interface LatestObservationReader {
	/**
	 * The latest published state as one [DispatchObservation], or `null` while nothing has been
	 * published yet.
	 */
	fun read(): DispatchObservation?

	companion object {
		/**
		 * The production reader: the same two published references `AgentLoopDriver` builds every
		 * cycle's observation from, read through the same ports.
		 *
		 * - [dispatchLoopSensorPort]'s [DispatchLoopSensorPort.snapshot] — the queued trains and
		 *   both block-input lists, one atomic read of `ShuntingLoop`'s per-tick observation;
		 * - [perceptionPort]'s [NetworkPerceptionPort.snapshot] — the `@Volatile` reference to the
		 *   last sim-thread capture, the only off-thread-safe member of that port (the pattern
		 *   `RequestRouteTool` uses). [NetworkPerceptionPort.captureSnapshot] is never called.
		 *
		 * ## Read order
		 *
		 * The simulation publishes the two references one after the other in each iteration —
		 * the snapshot first (`snapshotCaptureHook`), the dispatch-loop observation second — and
		 * neither carries a tick stamp, so no read can prove they come from the same tick. The
		 * dispatch-loop observation is read **first**: whatever tick it comes from, that tick's
		 * snapshot was published before it, so the snapshot read afterwards is from the same
		 * tick or a later one, never an earlier one. The snapshot feeds only
		 * [DispatchObservation.approvedTrainCount] into the rule-based admission decision, so at
		 * worst the fallback sees a train already approved while the queue still lists it: the
		 * free-slot count is then the lower, current one, and re-approving an active train is a
		 * no-op downstream (`ShuntingLoop.approveQueuedTrain`). The opposite order could pair an
		 * older approved-train count with a newer queue and overcount free slots.
		 *
		 * ## Nothing published yet
		 *
		 * Returns `null` while either port still returns its sentinel —
		 * [SimulationSnapshot.EMPTY] before the first capture, or [DispatchLoopSnapshot.EMPTY]
		 * when there is no dispatch loop to read — so a caller keeps its own whole observation
		 * instead of combining it with an empty one. Both are checked by reference, as
		 * [KoogAgentPlanAdapter] checks [SimulationSnapshot.EMPTY].
		 */
		fun fromPorts(
			perceptionPort: NetworkPerceptionPort,
			dispatchLoopSensorPort: DispatchLoopSensorPort
		): LatestObservationReader =
			LatestObservationReader {
				val tick = dispatchLoopSensorPort.snapshot()
				val snapshot = perceptionPort.snapshot()
				if (snapshot === SimulationSnapshot.EMPTY || tick === DispatchLoopSnapshot.EMPTY) {
					null
				} else {
					DispatchObservation(
						snapshot = snapshot,
						unapprovedTrains = tick.queuedTrains,
						innerBlockInputs = tick.innerBlockInputs,
						outerBlockInputs = tick.outerBlockInputs
					)
				}
			}
	}
}
