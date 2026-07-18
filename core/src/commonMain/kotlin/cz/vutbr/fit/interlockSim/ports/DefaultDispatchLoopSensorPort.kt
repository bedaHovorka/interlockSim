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
 * write, **each individual read** is self-consistent (one field from one tick).
 *
 * ## Atomic vs per-field reads
 *
 * [snapshot] invokes [observationProvider] exactly once and bundles all three fields
 * (queued trains, inner block inputs, outer block inputs) into a [DispatchLoopSnapshot]
 * from the same simulation tick — no inter-tick tearing. This is the method callers must
 * use when they need more than one field together.
 *
 * The three per-field accessors ([getQueuedTrains], [getInnerBlockInputs],
 * [getOuterBlockInputs]) each invoke [observationProvider] independently. Each accessor is
 * self-consistent for its own field, but if the kDisco sim thread republishes
 * [ShuntingLoop.latestObservation] between two accessor calls, the caller observes fields
 * from different ticks. This mirrors the warning in
 * [ShuntingLoop.getLatestObservation]: prefer a single snapshot read over three separate
 * accessor calls when more than one field is needed.
 *
 * ## Typical wiring
 *
 * ```kotlin
 * val loop = ShuntingLoop(context, endTime)
 * val sensorPort: DispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
 *
 * // Atomic single-tick read:
 * val state: DispatchLoopSnapshot = sensorPort.snapshot()
 * ```
 *
 * ## Threading
 *
 * Safe to call from the agent driver thread. [observationProvider] reads from
 * `ShuntingLoop.latestObservation` which is `@Volatile`; a single [snapshot] (or a single
 * per-field accessor) call therefore reads a consistent reference published by the kDisco
 * sim thread.
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
	override fun snapshot(): DispatchLoopSnapshot {
		val o = observationProvider()
		return DispatchLoopSnapshot(o.queuedTrains, o.innerBlockInputs, o.outerBlockInputs)
	}

	override fun getQueuedTrains(): List<QueuedTrainObservation> = observationProvider().queuedTrains

	override fun getInnerBlockInputs(): List<BlockInputObservation> = observationProvider().innerBlockInputs

	override fun getOuterBlockInputs(): List<BlockInputObservation> = observationProvider().outerBlockInputs
}
