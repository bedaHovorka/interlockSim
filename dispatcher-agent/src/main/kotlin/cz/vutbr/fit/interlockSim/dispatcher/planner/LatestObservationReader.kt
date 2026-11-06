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
		 * The production reader: the same published record `AgentLoopDriver` builds every cycle's
		 * observation from, read through the same port.
		 *
		 * [dispatchLoopSensorPort]'s [DispatchLoopSensorPort.snapshot] is read once. It returns
		 * one tick-stamped record — the queued trains, both block-input lists and the perception
		 * snapshot `ShuntingLoop` published together in one `@Volatile` write (Issue #1354) — so
		 * every field of the returned observation comes from the same tick.
		 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.captureSnapshot] is never called.
		 *
		 * ## Nothing published yet
		 *
		 * Returns `null` while the record still carries [SimulationSnapshot.EMPTY] — before the
		 * first capture, or [DispatchLoopSnapshot.EMPTY] when there is no dispatch loop to read —
		 * so a caller keeps its own whole observation instead of combining it with an empty one.
		 * Checked by reference, as [KoogAgentPlanAdapter] checks [SimulationSnapshot.EMPTY].
		 */
		fun fromPorts(dispatchLoopSensorPort: DispatchLoopSensorPort): LatestObservationReader =
			LatestObservationReader {
				val record = dispatchLoopSensorPort.snapshot()
				if (record.snapshot === SimulationSnapshot.EMPTY) {
					null
				} else {
					DispatchObservation(
						snapshot = record.snapshot,
						unapprovedTrains = record.queuedTrains,
						innerBlockInputs = record.innerBlockInputs,
						outerBlockInputs = record.outerBlockInputs
					)
				}
			}
	}
}
