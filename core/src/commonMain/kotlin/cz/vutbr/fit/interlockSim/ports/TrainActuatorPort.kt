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

/**
 * Actuator port for a train agent's movement commands.
 *
 * This is one of two actuator port interfaces introduced by SP0.3 (Issue #542) to give
 * agents a stable, kernel-independent surface for effecting changes in the simulation.
 * The port forms the **"act"** side of the train agent's sense → decide → act loop.
 *
 * ## Responsibility
 *
 * A train agent's only physical output is controlling the train's target speed and
 * scheduling station dwells.  It never plans routes, operates switches, or reasons
 * about other trains — those concerns belong to the dispatcher ([NetworkActuatorPort]).
 * The underlying physics (acceleration ramp, braking ramp, speed-limit enforcement) are
 * handled entirely by the simulation kernel; the agent supplies only the desired
 * *target speed* and the kernel does the rest.
 *
 * ## Design constraint
 *
 * Train agents are **algorithmic only** (no LLM, per the 2026-07-04 decision).  This
 * interface gives a deterministic algorithm a stable handle on the train motor without
 * coupling it to kDisco internals or simulation implementation details.  Keeping the
 * interface minimal ensures the algorithmic implementation stays easy to reason about.
 *
 * ## Usage example
 *
 * ```kotlin
 * class ReactiveTrainAgent(private val actuator: TrainActuatorPort) {
 *     fun onSignalAspectChanged(allowedSpeed: Double) {
 *         actuator.setTargetSpeed(allowedSpeed)
 *     }
 *
 *     fun onStationApproach() {
 *         actuator.setTargetSpeed(0.0)   // full stop at platform
 *     }
 *
 *     fun onStationDwell(dwellSeconds: Double) {
 *         actuator.holdAtStation(dwellSeconds)  // hold; resume after dwell expires
 *     }
 * }
 * ```
 *
 * @see NetworkActuatorPort
 * @since Issue #542 (SP0.3 — Goal 10)
 */
interface TrainActuatorPort {
	/**
	 * Set the target speed for the train's motor.
	 *
	 * The simulation kernel accelerates or decelerates the train towards [speed] within
	 * the physics constraints (maximum acceleration / minimum braking force defined by
	 * [cz.vutbr.fit.interlockSim.sim.Train]).  Calling this method is idempotent for the
	 * same [speed] value.
	 *
	 * @param speed Target speed in m/s.  Must be ≥ 0.  A value of `0.0` means "stop as
	 *   quickly as the physics allow" (full-service brake).
	 * @throws IllegalArgumentException if [speed] is negative.
	 */
	fun setTargetSpeed(speed: Double)

	/**
	 * Hold the train stationary at a station for [dwellDurationSeconds] simulation seconds.
	 *
	 * SP2a.3 act step for station dwell (Issue #554).  Schedules a *fire-and-forget* dwell
	 * period of [dwellDurationSeconds] sim-seconds.  After this period the train is
	 * released; the **next** [setTargetSpeed] call from the agent's act step restarts the
	 * motor.
	 *
	 * ## Precondition: the train must already be stopped
	 *
	 * This is a dwell *timer*, not a brake — it does not stop a moving train.  The agent
	 * must brake first ([setTargetSpeed]`(0.0)`) and call this only once the train has come
	 * to a stand.  Calling it on a moving train is rejected, because a dwell timer running
	 * alongside a rolling train would silently misrepresent a station stop.
	 *
	 * This call is **fire-and-forget** from the agent's perspective — the act step does
	 * not block waiting for the dwell to expire.  The agent's subsequent sense cycles will
	 * observe `isStationDwelling = true` during the dwell; once the dwell expires that flag
	 * flips to `false` and the agent will naturally decide to resume and call
	 * [setTargetSpeed].
	 *
	 * ## Thread safety
	 *
	 * Must be called on the kDisco simulation thread, identical to [setTargetSpeed].
	 *
	 * @param dwellDurationSeconds Dwell time in simulation seconds (must be > 0).
	 * @throws IllegalArgumentException if [dwellDurationSeconds] is ≤ 0.
	 * @throws cz.vutbr.fit.interlockSim.exceptions.SimulationException if the train is moving
	 *   or a station dwell is already in progress.
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	fun holdAtStation(dwellDurationSeconds: Double)
}
