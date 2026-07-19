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

import cz.vutbr.fit.interlockSim.domain.MINIMAL_TRAIN_DECELERATION
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import kotlin.math.sqrt

/**
 * SP2a.2 *decide* step for the reactive train agent (Issue #553).
 *
 * Pure, deterministic, side-effect-free policy that maps a first-person
 * [TrainPerceptionReading] to the correct [TrainAccelerationDecision] for the current
 * instant. It is the reflexive decision function of the SP2a sense → decide → act loop:
 * it does **not** plan routes, operate switches, or reason about other trains — it only
 * reacts to the signals, speed limit, and own kinematics the perception exposes
 * (matching the reactive-agent scope of Issue #537).
 *
 * ## Algorithm
 *
 * The policy first computes the **permitted target speed**, then classifies it against
 * the train's current [TrainPerceptionReading.velocity] into accelerate / coast / brake.
 *
 * The permitted target speed decodes the `(signalAheadAspect, nextSignalAheadAspect)`
 * pair exactly as the SŽ D1 distant-signal (*předvěst*) model prescribes — see the
 * [TrainPerceptionReading] KDoc "Předvěst / Výstraha are ENCODED by the 2-aspects pair":
 *
 * - **No authority** — `signalAheadAspect == null` (no reserved path) or `STOP`
 *   (red immediate signal): target `0.0` → brake to a stand.
 * - **Volno ahead** — immediate allowing and next allowing (or absent, i.e. within one
 *   semaphore of the destination InOut): target = `min(track limit, immediate allowed
 *   speed, next allowed speed)`, unrestricted running toward the permitted speed.
 * - **Výstraha** — immediate allowing but next `STOP`: the train may pass the immediate
 *   signal yet must brake to a stand at the second signal. The perception exposes only
 *   the immediate signal's distance (not the second's), so the policy caps the target to
 *   the speed from which the train can still stop within
 *   [TrainPerceptionReading.distanceToSignalAheadMetres] — a **fail-safe lower bound**,
 *   since the second signal is at least that far, so a train able to stop by the
 *   immediate signal can certainly stop by the second. This mirrors the kernel's own
 *   "start on caution" ramp ([Train] `Motor.onWarning`).
 *
 * The track [TrainPerceptionReading.currentSpeedLimitMps] always caps the target — even a
 * FREE signal cannot license exceeding the physical track limit.
 *
 * ## Intent classification
 *
 * With target speed `t` and current velocity `v`:
 * `t > v + ε` → [AccelerationTarget.ACCELERATE]; `t < v − ε` → [AccelerationTarget.BRAKE];
 * otherwise [AccelerationTarget.COAST] (holding speed, or holding a stop when `t = v = 0`).
 * `ε` = [SPEED_MATCH_TOLERANCE_MPS] absorbs floating-point and near-target jitter.
 *
 * @since Issue #553 (SP2a.2 — Goal 10 reactive train agent)
 */
object ReactiveTrainDecider {
	/**
	 * Speed band (m/s) within which a target speed is considered "matched" by the current
	 * velocity, yielding [AccelerationTarget.COAST] instead of a spurious accelerate/brake.
	 */
	const val SPEED_MATCH_TOLERANCE_MPS: Double = 0.1

	/** Braking deceleration magnitude (m/s²), from [MINIMAL_TRAIN_DECELERATION]. */
	private val brakingDecelerationMps2: Double = -MINIMAL_TRAIN_DECELERATION.toDouble()

	/**
	 * Decide the correct acceleration target for [reading].
	 *
	 * @param reading The train's first-person perception snapshot.
	 * @return The acceleration-target decision (intent + permitted target speed + rationale).
	 */
	fun decide(reading: TrainPerceptionReading): TrainAccelerationDecision {
		val immediate = reading.signalAheadAspect
		val next = reading.nextSignalAheadAspect

		val (targetSpeed, reason) =
			when {
				// No reserved path ahead — no movement authority.
				immediate == null -> 0.0 to "No path reserved ahead; hold"
				// Red immediate signal — stop at the signal.
				immediate == Signal.STOP -> 0.0 to "STOP signal ahead; brake to a stand"
				else -> permittedTargetForAllowingSignal(reading, immediate, next)
			}

		val target = classify(reading.velocity, targetSpeed)
		return TrainAccelerationDecision(target, targetSpeed, reason)
	}

	/**
	 * Permitted target speed when the immediate signal is allowing, decoding the second
	 * signal's aspect (Volno ahead vs. Výstraha) per SŽ D1.
	 */
	private fun permittedTargetForAllowingSignal(
		reading: TrainPerceptionReading,
		immediate: Signal,
		next: Signal?
	): Pair<Double, String> {
		val base = minOf(reading.currentSpeedLimitMps, immediate.allowedSpeed())
		return when {
			// No second signal on the reserved route (near destination InOut): immediate governs.
			next == null -> base to "Clear ahead (no second signal); run to permitted speed"
			// Volno ahead: cap by the second signal's allowed speed as well.
			next.isAllowing() -> minOf(base, next.allowedSpeed()) to "Volno ahead; run to permitted speed"
			// Výstraha: second signal is STOP — brake so the train can stop by it.
			else -> {
				val brakeToStop = brakingSpeedLimit(reading.distanceToSignalAheadMetres)
				minOf(base, brakeToStop) to "Výstraha (next signal STOP); reduce speed to stop at second signal"
			}
		}
	}

	/**
	 * Highest speed (m/s) from which the train can brake to a stand within [distanceMetres]
	 * at the [brakingDecelerationMps2] service-braking rate: `v = sqrt(2·a·s)`.
	 */
	private fun brakingSpeedLimit(distanceMetres: Double): Double =
		if (distanceMetres <= 0.0) 0.0 else sqrt(2.0 * brakingDecelerationMps2 * distanceMetres)

	private fun classify(
		velocity: Double,
		targetSpeed: Double
	): AccelerationTarget =
		when {
			targetSpeed > velocity + SPEED_MATCH_TOLERANCE_MPS -> AccelerationTarget.ACCELERATE
			targetSpeed < velocity - SPEED_MATCH_TOLERANCE_MPS -> AccelerationTarget.BRAKE
			else -> AccelerationTarget.COAST
		}
}
