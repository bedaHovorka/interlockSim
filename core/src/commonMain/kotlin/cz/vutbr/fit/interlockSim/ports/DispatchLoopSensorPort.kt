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

/**
 * Sensor port exposing the dispatch-loop's per-tick observation inputs to agent consumers.
 *
 * This is one of two dispatch-loop port interfaces introduced by SP4.1 (Issue #563) to give
 * agents (algorithmic or LLM-driven) a stable, loop-independent surface for reading the
 * current dispatch-relevant state of [cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
 *
 * The port forms the **"sense"** side of the agent's sense → decide → act loop for
 * ShuntingLoop-specific dispatch inputs (unapproved train queue and directional block-input
 * observations). The general-purpose network state (semaphore aspects, block occupancy, train
 * positions) is covered by [NetworkPerceptionPort] (SP0.2, Issue #541).
 *
 * ## What this exposes
 *
 * - **Queued trains** — trains that have been generated but not yet approved (moved onto the
 *   active network). An agent reads this list to decide which trains to admit.
 * - **Inner block inputs** — directional inputs of every inner track block (RailSemaphore–
 *   RailSemaphore bounds). Each input carries occupancy state, owner train, approach direction,
 *   and whether a forward reservation already extends beyond this input — the exact data
 *   [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher] needs for forward-path decisions.
 * - **Outer block inputs** — same data for outer (InOut–RailSemaphore) track blocks.
 *
 * ## Threading
 *
 * Implementations must be **safe to call from the agent driver thread** (off the kDisco
 * simulation thread). The default implementation ([DefaultDispatchLoopSensorPort]) reads
 * from a `@Volatile`-backed [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.TickObservation]
 * reference published atomically by the kDisco sim thread, so off-thread reads are always
 * consistent (all three fields come from the same tick).
 *
 * ## Usage
 *
 * ```kotlin
 * val sensorPort: DispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
 *
 * // In the agent driver loop:
 * val queued = sensorPort.getQueuedTrains()       // which trains need approval?
 * val inner  = sensorPort.getInnerBlockInputs()   // inner-block approach/reservation state
 * val outer  = sensorPort.getOuterBlockInputs()   // outer-block approach/reservation state
 * ```
 *
 * @see DefaultDispatchLoopSensorPort
 * @see DispatchLoopActuatorPort
 * @see NetworkPerceptionPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
interface DispatchLoopSensorPort {

	/**
	 * Returns the list of trains currently queued for dispatch (not yet approved).
	 *
	 * Each entry represents a train that was generated but has not yet been admitted to the
	 * simulation. An agent calls this to discover which trains are waiting and decide whether
	 * to issue an [DispatchLoopActuatorPort.approveTrain] command.
	 *
	 * The returned list is a **snapshot** of the unapproved queue as of the last tick
	 * published by the simulation shell. It may be one tick stale relative to the current
	 * simulation time — this is acceptable for the algorithmic and LLM-driven dispatch
	 * patterns this port serves.
	 *
	 * @return Snapshot of the unapproved-train queue; empty if no trains are waiting.
	 */
	fun getQueuedTrains(): List<QueuedTrainObservation>

	/**
	 * Returns the directional block-input observations for all **inner** track blocks.
	 *
	 * Inner blocks are bounded by two [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]
	 * separators on both ends (e.g. `k1`, `k2` in `vyhybna.xml`). Each input carries the block's
	 * occupancy state, the name of the semaphore at this input, whether the occupant/reservant is
	 * approaching or reserved toward this end, and whether the path has already been extended
	 * beyond this semaphore — allowing the agent to decide whether a forward reservation is needed.
	 *
	 * @return Snapshot of all inner-block inputs; empty if the simulation has not yet started.
	 */
	fun getInnerBlockInputs(): List<BlockInputObservation>

	/**
	 * Returns the directional block-input observations for all **outer** track blocks.
	 *
	 * Outer blocks are bounded by one [cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut] and
	 * one [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore] separator
	 * (e.g. `kA`, `kB` in `vyhybna.xml`). The observation is from the semaphore end — the
	 * direction through which a train enters from the InOut side.
	 *
	 * @return Snapshot of all outer-block inputs; empty if the simulation has not yet started.
	 */
	fun getOuterBlockInputs(): List<BlockInputObservation>
}
