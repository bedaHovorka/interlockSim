/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation

/**
 * Immutable single-tick snapshot of the dispatch-loop observation inputs.
 *
 * This is the **"sense"** value type for the dispatch-loop agent control loop
 * (SP4.1, Issue #563): an agent calls [DispatchLoopSensorPort.snapshot] to obtain all three
 * dispatch-relevant fields — the unapproved-train queue plus the inner and outer block-input
 * observations — from a **single** simulation tick in one atomic read. Because the underlying
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.latestObservation] is published as one `@Volatile`
 * reference write, a [DispatchLoopSensorPort.snapshot] call returns either all three fields
 * from tick N or all three from tick N-1, never a mix across ticks.
 *
 * ## Why a snapshot rather than three accessors
 *
 * The per-field accessors on [DispatchLoopSensorPort] (`getQueuedTrains`,
 * `getInnerBlockInputs`, `getOuterBlockInputs`) each invoke the observation provider
 * independently. If the kDisco simulation thread republishes a new observation between two
 * accessor calls, the caller sees fields from different ticks — tearing the single-tick
 * guarantee [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.TickObservation] exists to provide.
 * Callers that need more than one field together **must** use [DispatchLoopSensorPort.snapshot]
 * to read them atomically; the per-field accessors are single-field conveniences only.
 *
 * Because every field is an immutable list of immutable `data class` entries, the snapshot is
 * safe to pass across thread boundaries and to hold after the tick ends.
 *
 * ## Contents
 *
 * | Field | Source | Agent use |
 * |-------|--------|-----------|
 * | [queuedTrains]     | Unapproved-train queue | Which trains are waiting for admission |
 * | [innerBlockInputs] | Inner (semaphore–semaphore) block inputs | Forward-path / reservation decisions for inner blocks |
 * | [outerBlockInputs] | Outer (InOut–semaphore) block inputs | Forward-path / reservation decisions for outer blocks |
 *
 * The snapshot may be one tick stale relative to the current simulation time by the time an
 * off-thread agent consumes it; it is a record of the instant it was taken, not a live view.
 * This is acceptable for the algorithmic and LLM-driven dispatch patterns this port serves.
 *
 * @property queuedTrains     Trains generated but not yet approved (admitted to the active
 *   network). Empty if no trains are waiting.
 * @property innerBlockInputs Directional block-input observations for all inner track blocks.
 *   Empty if the simulation has not yet started.
 * @property outerBlockInputs Directional block-input observations for all outer track blocks.
 *   Empty if the simulation has not yet started.
 *
 * @see DispatchLoopSensorPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
data class DispatchLoopSnapshot(
	val queuedTrains: List<QueuedTrainObservation>,
	val innerBlockInputs: List<BlockInputObservation>,
	val outerBlockInputs: List<BlockInputObservation>
) {
	companion object {
		/**
		 * Empty snapshot, useful as a default / before the simulation has published its first
		 * observation. Safe to hand to a dispatcher from any thread: it carries no queued trains
		 * and no block-input state, so a well-behaved dispatcher responds with a no-op decision.
		 */
		val EMPTY: DispatchLoopSnapshot =
			DispatchLoopSnapshot(
				queuedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)
	}
}
