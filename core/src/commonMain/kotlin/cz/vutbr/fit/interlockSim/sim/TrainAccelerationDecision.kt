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

/**
 * The qualitative acceleration intent a reactive train agent decides on for the
 * current instant — the **decide** step of the SP2a sense → decide → act loop.
 *
 * A reactive train agent has a single physical control output: the train's target
 * speed (see [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort]). Comparing the
 * decided [TrainAccelerationDecision.targetSpeedMps] with the train's current
 * velocity yields one of three intents:
 *
 * - [ACCELERATE] — the permitted target speed is above the current velocity; the
 *   agent should raise the target so the kernel accelerates (within physics limits).
 * - [COAST] — the target speed matches the current velocity (within tolerance); hold
 *   the current speed. Also covers a stationary train that should stay stopped.
 * - [BRAKE] — the permitted target speed is below the current velocity; the agent
 *   should lower the target so the kernel brakes. Covers both a red (STOP) signal
 *   ahead and the SŽ D1 *Výstraha* case (allowing signal ahead, next signal STOP).
 *
 * @since Issue #553 (SP2a.2 — Goal 10 reactive train agent)
 */
enum class AccelerationTarget {
	/** Raise speed toward the permitted target. */
	ACCELERATE,

	/** Maintain the current speed (target ≈ current velocity, or hold a stop). */
	COAST,

	/** Reduce speed toward the permitted target (signal STOP or Výstraha). */
	BRAKE
}

/**
 * Result of the SP2a.2 *decide* step: the correct acceleration target for a train at
 * one simulation instant, derived purely from its [cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading].
 *
 * This is a *request*, not an accomplished fact — the **act** step (SP2a.3) applies
 * [targetSpeedMps] via [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort.setTargetSpeed],
 * and the simulation kernel performs the actual acceleration/braking ramp within its
 * physics limits ([cz.vutbr.fit.interlockSim.domain.MAXIMAL_TRAIN_ACCELERATION],
 * [cz.vutbr.fit.interlockSim.domain.MINIMAL_TRAIN_DECELERATION]).
 *
 * @property target The qualitative intent (accelerate / coast / brake).
 * @property targetSpeedMps The permitted target speed in **m/s** (≥ 0) the agent should
 *   command. `0.0` means "stop as quickly as physics allow".
 * @property rationale Short human-readable explanation of why this target was chosen,
 *   for logging and observability.
 *
 * @since Issue #553 (SP2a.2 — Goal 10 reactive train agent)
 */
data class TrainAccelerationDecision(
	val target: AccelerationTarget,
	val targetSpeedMps: Double,
	val rationale: String
) {
	init {
		require(targetSpeedMps >= 0.0) { "targetSpeedMps must be >= 0, was $targetSpeedMps" }
	}
}
