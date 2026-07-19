/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.ports.TrainActuatorPort

/**
 * SP2a.3 *act* step for the reactive train agent (Issue #554).
 *
 * Translates a [TrainAccelerationDecision] produced by the SP2a.2 *decide* step
 * ([ReactiveTrainDecider]) into actual motor commands on a [TrainActuatorPort].  It is
 * the **"act"** half of the reactive train agent's sense → decide → act loop.
 *
 * ## Responsibilities
 *
 * 1. **Acceleration / braking** — [applyDecision] calls
 *    [TrainActuatorPort.setTargetSpeed] with the decided [TrainAccelerationDecision.targetSpeedMps].
 *    The simulation kernel applies the ramp within physics limits; this object only
 *    forwards the desired target.
 *
 * 2. **Station dwell** — [holdAtStation] calls [TrainActuatorPort.holdAtStation].  The
 *    port implementation cancels in-progress motor acceleration and spawns a
 *    fire-and-forget kDisco process that holds for [dwellDurationSeconds] simulation
 *    seconds.  The agent's act step is not blocked — subsequent sense cycles observe
 *    `isDwelling = true` until the dwell expires, at which point the agent will decide
 *    to accelerate and call [applyDecision] again.
 *
 * ## What this object does NOT do
 *
 * - Does **not** choose routes, reserve paths, or operate switches (dispatcher scope).
 * - Does **not** maintain state across cycles — it is a pure, stateless translator.
 * - Does **not** apply the dwell decision autonomously; the caller (e.g. SP2a.4
 *   [TrainDecisionPolicy]) decides *when* to call [holdAtStation].
 *
 * ## Usage
 *
 * ```kotlin
 * val reading   = networkPerception.trainPerception(trainId)
 * val decision  = ReactiveTrainDecider.decide(reading)
 * ReactiveTrainActuator.applyDecision(decision, actuator)
 *
 * // On a station-stop cycle:
 * ReactiveTrainActuator.holdAtStation(dwellDurationSeconds, actuator)
 * ```
 *
 * @since Issue #554 (SP2a.3 — Goal 10 reactive train agent)
 */
object ReactiveTrainActuator {
	/**
	 * Apply [decision] by commanding the [actuator] to the decided target speed.
	 *
	 * Translates the qualitative [AccelerationTarget] intent into a single
	 * [TrainActuatorPort.setTargetSpeed] call.  The simulation kernel's physics model
	 * performs the actual acceleration or braking ramp; this method only forwards the
	 * permitted target speed.
	 *
	 * - [AccelerationTarget.ACCELERATE] / [AccelerationTarget.COAST] / [AccelerationTarget.BRAKE]
	 *   all map to `setTargetSpeed(decision.targetSpeedMps)` — the kernel determines the
	 *   direction of the ramp from the sign of `(target − current velocity)`.
	 * - A target of `0.0` (STOP signal or no movement authority) triggers full-service
	 *   braking.
	 *
	 * @param decision The acceleration target decided by [ReactiveTrainDecider.decide].
	 * @param actuator The actuator port wrapping the train's motor.
	 */
	fun applyDecision(
		decision: TrainAccelerationDecision,
		actuator: TrainActuatorPort
	) {
		actuator.setTargetSpeed(decision.targetSpeedMps)
	}

	/**
	 * Hold the train stationary at a station for [dwellDurationSeconds] simulation seconds.
	 *
	 * Delegates to [TrainActuatorPort.holdAtStation].  The port cancels the current motor
	 * acceleration and schedules a fire-and-forget dwell period after which the motor
	 * remains stopped.  The calling agent's next sense → decide → act cycle will detect
	 * the resumed state and call [applyDecision] to restart movement.
	 *
	 * @param dwellDurationSeconds Station dwell time in simulation seconds (must be > 0).
	 * @param actuator The actuator port wrapping the train's motor.
	 * @throws IllegalArgumentException if [dwellDurationSeconds] is ≤ 0.
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	fun holdAtStation(
		dwellDurationSeconds: Double,
		actuator: TrainActuatorPort
	) {
		actuator.holdAtStation(dwellDurationSeconds)
	}
}
