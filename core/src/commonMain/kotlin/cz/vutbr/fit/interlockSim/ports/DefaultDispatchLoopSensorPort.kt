/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop

/**
 * Default implementation of [DispatchLoopSensorPort] backed by a
 * [ShuntingLoop.TickObservation] provider.
 *
 * Reads the most recent per-tick observation bundle published by the
 * [ShuntingLoop] shell via [ShuntingLoop.getLatestObservation]. Because
 * [ShuntingLoop.latestObservation] is `@Volatile` and updated as a single reference
 * write, each call to [observationProvider] returns a consistent snapshot in which
 * all three fields (queued trains, inner block inputs, outer block inputs) come from
 * the same simulation tick — no inter-tick tearing is possible.
 *
 * ## Typical wiring
 *
 * ```kotlin
 * val loop = ShuntingLoop(context, endTime)
 * val sensorPort: DispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
 * ```
 *
 * ## Threading
 *
 * Safe to call from the agent driver thread. [observationProvider] reads from
 * `ShuntingLoop.latestObservation` which is `@Volatile`; the three per-field
 * accessors delegate to the same provider invocation via the caller's own reference
 * to the returned [ShuntingLoop.TickObservation] instance.
 *
 * @param observationProvider Supplier for the latest [ShuntingLoop.TickObservation]
 *   published by the simulation shell (typically `loop::getLatestObservation`).
 *
 * @see DispatchLoopSensorPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class DefaultDispatchLoopSensorPort(
	private val observationProvider: () -> ShuntingLoop.TickObservation
) : DispatchLoopSensorPort {

	override fun getQueuedTrains(): List<QueuedTrainObservation> =
		observationProvider().queuedTrains

	override fun getInnerBlockInputs(): List<BlockInputObservation> =
		observationProvider().innerBlockInputs

	override fun getOuterBlockInputs(): List<BlockInputObservation> =
		observationProvider().outerBlockInputs
}
