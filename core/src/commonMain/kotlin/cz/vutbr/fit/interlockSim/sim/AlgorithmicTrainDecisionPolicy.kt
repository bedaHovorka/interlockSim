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

import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SP2a.4 default [TrainDecisionPolicy] — the deterministic LS-inspired algorithmic
 * accelerate/coast/brake policy (Issue #555).
 *
 * ## Normal operation: delegation to [ReactiveTrainDecider]
 *
 * Under normal conditions [decide] delegates entirely to [ReactiveTrainDecider], which
 * implements the SŽ D1 předvěst/Výstraha model. The algorithm is *ecological*:
 *
 * - It reads **up to 2 signal aspects ahead** (the immediate signal and the second signal
 *   along the reserved route, per the [cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading]
 *   KDoc "Předvěst / Výstraha" semantics).
 * - When the immediate signal is allowing but the second is STOP (Výstraha), braking begins
 *   early — the train never halts abruptly at a signal; it reduces speed gradually so it can
 *   stop at the second signal.
 * - "Ecological" here means the train does not stop unnecessarily before a green signal: if
 *   both the immediate and the second signal are allowing, the train accelerates to the track
 *   speed limit without intermediate stops.
 *
 * ## Dispatcher hold override
 *
 * When the dispatcher issues a [TrainDirective.HoldAt] or [TrainDirective.HoldImmediately]
 * via [acceptDirective], [holdActive] is set to `true` and [decide] returns
 * `(BRAKE, 0.0, "Dispatcher hold order active; braking to a stand")` regardless of what the
 * perception says, overriding the reactive algorithm completely.
 *
 * The hold is cleared only when the dispatcher sends [TrainDirective.RouteGranted], which
 * resets [holdActive] to `false`. A [TrainDirective.RouteDenied] does **not** activate a hold:
 * the perception already shows no reserved path, and [ReactiveTrainDecider] naturally returns
 * COAST at 0 for that case.
 *
 * ## LS (Liniový vlakový zabezpečovač) inspiration
 *
 * Czech LS conveys the current and next signal aspect continuously in-cab; the driver reacts:
 * - Green (Volno): proceed at line speed — modelled by [ReactiveTrainDecider].
 * - Yellow (Výstraha): begin braking now, next signal red — modelled by the 2-aspect
 *   předvěst logic in [ReactiveTrainDecider].
 * - Red (Stůj): full service brake — modelled by [ReactiveTrainDecider].
 * - "Postaveno a volno" radio/voice: route granted → [TrainDirective.RouteGranted] clears hold.
 * - "Stůj!" radio/voice: emergency stop → [TrainDirective.HoldImmediately] activates hold.
 *
 * ## Thread-safety
 *
 * [holdActive] is `@Volatile` so a write on the dispatcher-agent thread is immediately visible
 * when [decide] is called on the kDisco simulation thread.
 *
 * @see TrainDecisionPolicy
 * @see ReactiveTrainDecider
 * @see TrainDirective
 * @since Issue #555 (SP2a.4 — Goal 10 reactive train agent)
 */
class AlgorithmicTrainDecisionPolicy : TrainDecisionPolicy {
	/**
	 * When `true`, a dispatcher hold order is active and has not yet been cleared by a
	 * [TrainDirective.RouteGranted].  [decide] returns `(BRAKE/COAST, 0.0)` until cleared.
	 */
	@kotlin.concurrent.Volatile
	private var holdActive: Boolean = false

	/**
	 * Decide the correct acceleration target.
	 *
	 * If [holdActive] is set (dispatcher hold order), returns BRAKE (moving) or COAST
	 * (already stopped) with target speed 0.0 m/s, overriding the reactive algorithm.
	 * Otherwise delegates to [ReactiveTrainDecider.decide].
	 */
	override fun decide(reading: TrainPerceptionReading): TrainAccelerationDecision {
		if (holdActive) {
			val target =
				if (reading.velocity < ReactiveTrainDecider.SPEED_MATCH_TOLERANCE_MPS) {
					AccelerationTarget.COAST
				} else {
					AccelerationTarget.BRAKE
				}
			logger.debug {
				"Train ${reading.trainId}: dispatcher hold active — overriding to $target (target 0.0 m/s)"
			}
			return TrainAccelerationDecision(
				target,
				0.0,
				"Dispatcher hold order active; braking to a stand"
			)
		}
		return ReactiveTrainDecider.decide(reading)
	}

	/**
	 * Update the hold state based on the received [directive].
	 *
	 * - [TrainDirective.HoldImmediately] → sets [holdActive] = `true` (emergency stop).
	 * - [TrainDirective.HoldAt] → sets [holdActive] = `true` (stop at named signal).
	 * - [TrainDirective.RouteGranted] → clears [holdActive] = `false` (proceed permitted).
	 * - [TrainDirective.RouteDenied] → no-op on hold state (perception handles the wait).
	 */
	override fun acceptDirective(directive: TrainDirective) {
		when (directive) {
			is TrainDirective.HoldImmediately -> {
				logger.info { "AlgorithmicTrainDecisionPolicy: immediate hold directive received" }
				holdActive = true
			}
			is TrainDirective.HoldAt -> {
				logger.info {
					"AlgorithmicTrainDecisionPolicy: hold-at directive received (signal: ${directive.signalName})"
				}
				holdActive = true
			}
			is TrainDirective.RouteGranted -> {
				logger.info {
					"AlgorithmicTrainDecisionPolicy: route granted, clearing hold " +
						"(aspect: ${directive.aspect.humanLabel()}, limit: ${directive.speedLimitKmh} km/h)"
				}
				holdActive = false
			}
			is TrainDirective.RouteDenied -> {
				// RouteDenied does not activate a hold: the perception already shows no reserved path,
				// so ReactiveTrainDecider will return (COAST, 0.0) naturally.  Log at debug level.
				logger.debug {
					"AlgorithmicTrainDecisionPolicy: route denied (${directive.reason}) — hold unchanged, " +
						"perception-based algorithm continues"
				}
			}
		}
	}
}
