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
 * reference published atomically by the kDisco sim thread as a single reference write. Each
 * individual read is therefore self-consistent (one field from one tick), but the three
 * per-field accessors invoke the provider independently — if the sim thread republishes
 * between two accessor calls, the caller observes fields from **different** ticks.
 *
 * To read more than one field together without inter-tick tearing, use [snapshot], which
 * invokes the provider exactly once and bundles all three fields from the same tick. This
 * mirrors the [NetworkPerceptionPort.snapshot] / [SimulationSnapshot] pattern (SP0.4).
 *
 * ## Usage
 *
 * ```kotlin
 * val sensorPort: DispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
 *
 * // In the agent driver loop — read all three fields from one tick atomically:
 * val state: DispatchLoopSnapshot = sensorPort.snapshot()
 * val queued = state.queuedTrains       // which trains need approval?
 * val inner  = state.innerBlockInputs   // inner-block approach/reservation state
 * val outer  = state.outerBlockInputs   // outer-block approach/reservation state
 *
 * // Single-field convenience (self-consistent for that one field; may differ in tick from
 * // a separate accessor call) — prefer snapshot() when you need more than one field together:
 * val onlyQueued = sensorPort.getQueuedTrains()
 * ```
 *
 * @see DefaultDispatchLoopSensorPort
 * @see DispatchLoopSnapshot
 * @see DispatchLoopActuatorPort
 * @see NetworkPerceptionPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
interface DispatchLoopSensorPort {
	/**
	 * Returns a single-tick snapshot of all three dispatch-loop observation inputs in one
	 * atomic read.
	 *
	 * The provider is invoked exactly once, so the returned [DispatchLoopSnapshot] is
	 * internally consistent: [DispatchLoopSnapshot.queuedTrains],
	 * [DispatchLoopSnapshot.innerBlockInputs] and [DispatchLoopSnapshot.outerBlockInputs]
	 * all come from the same simulation tick. This is the method callers **must** use when
	 * they need more than one field together — the per-field accessors below invoke the
	 * provider independently and can observe different ticks if the sim thread republishes
	 * between calls.
	 *
	 * The snapshot may be one tick stale relative to the current simulation time; this is
	 * acceptable for the algorithmic and LLM-driven dispatch patterns this port serves.
	 *
	 * @return Atomic single-tick snapshot of the dispatch-loop observation inputs;
	 *   [DispatchLoopSnapshot.EMPTY] before the simulation has published its first observation.
	 */
	fun snapshot(): DispatchLoopSnapshot

	/**
	 * Returns the list of trains currently queued for dispatch (not yet approved).
	 *
	 * Each entry represents a train that was generated but has not yet been admitted to the
	 * simulation. An agent calls this to discover which trains are waiting and decide whether
	 * to issue an [DispatchLoopActuatorPort.approveTrain] command.
	 *
	 * This accessor invokes the observation provider independently; the returned list is
	 * self-consistent for this one field, but may be from a different tick than a separate
	 * [getInnerBlockInputs] / [getOuterBlockInputs] call. Prefer [snapshot] when you need the
	 * queue together with block-input state.
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
	 * This accessor invokes the observation provider independently; the returned list is
	 * self-consistent for this one field, but may be from a different tick than a separate
	 * [getQueuedTrains] / [getOuterBlockInputs] call. Prefer [snapshot] when you need inner
	 * block inputs together with the queue or outer block inputs.
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
	 * This accessor invokes the observation provider independently; the returned list is
	 * self-consistent for this one field, but may be from a different tick than a separate
	 * [getQueuedTrains] / [getInnerBlockInputs] call. Prefer [snapshot] when you need outer
	 * block inputs together with the queue or inner block inputs.
	 *
	 * @return Snapshot of all outer-block inputs; empty if the simulation has not yet started.
	 */
	fun getOuterBlockInputs(): List<BlockInputObservation>
}
