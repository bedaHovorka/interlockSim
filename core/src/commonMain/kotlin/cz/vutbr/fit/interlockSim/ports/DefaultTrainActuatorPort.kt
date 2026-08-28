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

import cz.vutbr.fit.interlockSim.sim.Train

/**
 * Simulation-backed implementation of [TrainActuatorPort].
 *
 * Wraps a single [Train] and translates [setTargetSpeed] and [holdAtStation] calls into
 * the train's physics model.  This is the **act** side of a train agent's
 * sense → decide → act loop.
 *
 * ## Design constraint
 *
 * Train agents are **algorithmic only** (no LLM, per the 2026-07-04 decision).  This
 * implementation gives a deterministic algorithm a stable handle on the train motor
 * without coupling it to kDisco internals — the same isolation guarantee that
 * [DefaultNetworkActuatorPort] provides for the dispatcher.
 *
 * ## Thread-safety
 *
 * Not thread-safe.  All calls must originate from the single kDisco simulation thread.
 *
 * @param train The train whose motor this port controls.
 *
 * @see TrainActuatorPort
 * @see DefaultNetworkActuatorPort
 * @since Issue #545 (SP0.6 — Goal 10)
 */
class DefaultTrainActuatorPort(
	private val train: Train
) : TrainActuatorPort {
	/**
	 * Set the target speed for the wrapped train.
	 *
	 * Validates the precondition and delegates to [Train.setTargetSpeed].  The train's
	 * physics model (acceleration ramp, braking ramp) handles the actual velocity
	 * transition; this method only sets the desired end-state.
	 *
	 * @param speed Target speed in m/s.  Must be ≥ 0.
	 * @throws IllegalArgumentException if [speed] is negative.
	 */
	override fun setTargetSpeed(speed: Double) {
		require(speed >= 0.0) { "Target speed must be >= 0, got $speed" }
		train.setTargetSpeed(speed)
	}

	/**
	 * Hold the train at a station for [dwellDurationSeconds] simulation seconds.
	 *
	 * Validates the precondition and delegates to [Train.holdAtStation].  A fire-and-forget
	 * kDisco process is spawned inside [Train] that cancels current acceleration, waits for
	 * [dwellDurationSeconds] sim-seconds, and then completes — leaving the motor stopped
	 * until the agent's next [setTargetSpeed] call restarts it.
	 *
	 * @param dwellDurationSeconds Dwell time in simulation seconds (must be > 0).
	 * @throws IllegalArgumentException if [dwellDurationSeconds] is ≤ 0.
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	override fun holdAtStation(dwellDurationSeconds: Double) {
		require(dwellDurationSeconds > 0.0) { "dwellDurationSeconds must be > 0, got $dwellDurationSeconds" }
		train.holdAtStation(dwellDurationSeconds)
	}
}
