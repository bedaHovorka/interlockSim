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
 * **Dormant pipeline stage (Issue #978).** This object has no production caller today; the
 * owner ruled it dormant, not dead (2026-09-25). Production applies train decisions without
 * it: [applyTrainDecisions] in `SynchronousDispatcherWiring.kt` calls [TrainDecisionPolicy.decide]
 * (whose default [AlgorithmicTrainDecisionPolicy] implementation delegates to
 * [ReactiveTrainDecider.decide] except for dispatcher hold directives) and forwards the result
 * via an inline `DefaultTrainActuatorPort(train).setTargetSpeed(decision.targetSpeedMps)` call rather than
 * through [applyDecision]; nothing calls [holdAtStation] in production either. It remains the
 * intended single point through which a [TrainAccelerationDecision] would be applied once
 * wired — mirroring the sibling dormant stages of the same Issue #978 decision,
 * [CandidatePathRuleEngine] and [PathCommandTranslator], where none of
 * `DispatchDecision.HoldTrain`, `SetSignalAspect`, or `SetSwitchPosition` has a live producer
 * either (`docs/GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md`); that stage's ESA-11
 * switch-before-signal ordering is enforced instead by
 * [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]'s
 * `configureAndRegisterSwitches`. Its only observer is its own unit test suite
 * (`ReactiveTrainActuatorTest`); no golden/heavy/parity gate executes it, so any change here
 * is validated by that test alone. Do not delete in a dead-code sweep without re-opening
 * Issue #978.
 *
 * ## Responsibilities
 *
 * 1. **Acceleration / braking** — [applyDecision] calls
 *    [TrainActuatorPort.setTargetSpeed] with the decided [TrainAccelerationDecision.targetSpeedMps].
 *    The simulation kernel applies the ramp within physics limits; this object only
 *    forwards the desired target.
 *
 * 2. **Station dwell** — [holdAtStation] calls [TrainActuatorPort.holdAtStation], which
 *    spawns a fire-and-forget kDisco process holding the train for the requested number
 *    of simulation seconds.  The train must already be stopped when this is called (the
 *    port rejects a moving train); brake first via [applyDecision] with a `0.0` target.
 *    The agent's act step is not blocked — subsequent sense cycles observe
 *    `isStationDwelling = true` until the dwell expires, at which point the agent will
 *    decide to accelerate and call [applyDecision] again.
 *
 * ## What this object does NOT do
 *
 * - Does **not** choose routes, reserve paths, or operate switches (dispatcher scope).
 * - Does **not** maintain state across cycles — it is a pure, stateless translator.
 * - Does **not** apply the dwell decision autonomously; the caller decides *when* to call
 *   [holdAtStation] — no production caller does so today (see the dormant-stage note above).
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
	 * Delegates to [TrainActuatorPort.holdAtStation], which schedules a fire-and-forget
	 * dwell period after which the motor remains stopped.
	 *
	 * The train must already be at rest — this starts a dwell timer, it does not brake.
	 * Call [applyDecision] with a `0.0` target speed first and wait for the train to come
	 * to a stand; the port rejects the call while the train is moving.  After the dwell
	 * expires the agent's next [applyDecision] call restarts movement — the dwell itself
	 * does not re-accelerate the train.
	 *
	 * @param dwellDurationSeconds Station dwell time in simulation seconds (must be > 0).
	 * @param actuator The actuator port wrapping the train's motor.
	 * @throws IllegalArgumentException if [dwellDurationSeconds] is ≤ 0.
	 * @throws cz.vutbr.fit.interlockSim.exceptions.SimulationException if the train is moving
	 *   or a station dwell is already in progress.
	 * @since Issue #554 (SP2a.3 — Goal 10)
	 */
	fun holdAtStation(
		dwellDurationSeconds: Double,
		actuator: TrainActuatorPort
	) {
		actuator.holdAtStation(dwellDurationSeconds)
	}
}
