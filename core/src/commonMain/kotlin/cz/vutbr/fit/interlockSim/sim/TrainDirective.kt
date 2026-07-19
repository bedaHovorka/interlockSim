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

import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.MovementAuthority

/**
 * Typed directive from the dispatcher to a train agent's [TrainDecisionPolicy] (SP2a.4, Issue #555).
 *
 * A [TrainDirective] is the `:core`-layer representation of the dispatcher→train speech acts
 * from the SP3.3 message protocol ([cz.vutbr.fit.interlockSim.lang.proto.Message]):
 *
 * | [TrainDirective] subtype | Originating speech act |
 * |---|---|
 * | [RouteGranted]    | `Message.RouteGrant` — "postaveno a volno" |
 * | [RouteDenied]     | `Message.RouteDenial` — "Nikoliv, čekejte." |
 * | [HoldAt]          | `Message.HoldOrder` — "Stůj u návěstidla X." |
 * | [HoldImmediately] | `Message.HoldOrder` (emergency) — "Stůj!" |
 *
 * Keeping this sealed interface in `:core` (rather than in `:dispatcher-agent`) ensures that
 * [AlgorithmicTrainDecisionPolicy] — the default algorithmic policy shipped with SP2a and
 * used in `:fast-sim` — never takes a compile-time dependency on Koog, kotlinx-serialization,
 * or any other agent-framework library that lives only in `:dispatcher-agent`.
 *
 * The `:dispatcher-agent` layer converts a received
 * [cz.vutbr.fit.interlockSim.lang.proto.Message] into a [TrainDirective] before calling
 * [TrainDecisionPolicy.acceptDirective], bridging the two layers at the boundary.
 *
 * ## LS inspiration
 *
 * Czech LS (Liniový vlakový zabezpečovač — cab-signalling system) conveys dispatcher
 * commands in-cab continuously. These directives model the voice/radio commands that
 * override the automatic aspect-based algorithm:
 * - "Postaveno a volno" → [RouteGranted] (route set and locked, signal cleared)
 * - "Nikoliv, čekejte." → [RouteDenied] (route request refused, wait)
 * - "Stůj u návěstidla X." → [HoldAt] (stop at a specific signal)
 * - "Stůj!" (mimořádné zastavení) → [HoldImmediately] (emergency stop)
 *
 * @see TrainDecisionPolicy
 * @see AlgorithmicTrainDecisionPolicy
 * @since Issue #555 (SP2a.4 — Goal 10 reactive train agent)
 */
sealed interface TrainDirective {
	/**
	 * Dispatcher has set and locked a route; the entry signal is cleared.
	 *
	 * Corresponds to "postaveno a volno" (SŽDC D1). Receiving this directive clears any active
	 * hold override set by a prior [HoldAt] or [HoldImmediately]; subsequent [TrainDecisionPolicy.decide]
	 * calls revert to the perception-based [ReactiveTrainDecider] algorithm.
	 *
	 * @property aspect The signal aspect now shown at the entry signal of the granted route.
	 * @property speedLimitKmh Maximum permitted speed under the movement authority (km/h, ≥ 0).
	 * @property movementAuthority Full ETCS/LS-style movement authority (oprávnění k jízdě),
	 *   or `null` when the dispatcher is not using MA-based supervision.
	 */
	data class RouteGranted(
		val aspect: Aspect,
		val speedLimitKmh: Int,
		val movementAuthority: MovementAuthority? = null
	) : TrainDirective {
		init {
			require(speedLimitKmh >= 0) { "speedLimitKmh must be non-negative, got $speedLimitKmh" }
		}
	}

	/**
	 * Dispatcher has denied the train's route request.
	 *
	 * Corresponds to "Nikoliv, čekejte." (D1/D2). The train should hold its current position
	 * and not attempt to proceed. The perception-based algorithm already handles this naturally
	 * (no reserved path → COAST at 0 or BRAKE), so this directive does **not** activate a
	 * hold override — it is informational, allowing the policy to log or count denials.
	 *
	 * @property reason Human-readable denial reason (e.g. `"section U3 occupied"`).
	 */
	data class RouteDenied(
		val reason: String
	) : TrainDirective

	/**
	 * Dispatcher orders the train to stop at or before the named signal.
	 *
	 * Corresponds to "Stůj u návěstidla [signalName]." (D1). Activates a hold override:
	 * [TrainDecisionPolicy.decide] will return BRAKE (or COAST when already at rest) with
	 * target speed 0.0, regardless of the current signal aspect, until a subsequent
	 * [RouteGranted] directive clears the hold.
	 *
	 * @property signalName Name of the signal at which the train must stop (e.g. `"L1"`, `"zA"`).
	 */
	data class HoldAt(
		val signalName: String
	) : TrainDirective {
		init {
			require(signalName.isNotBlank()) { "signalName must not be blank" }
		}
	}

	/**
	 * Dispatcher orders an immediate emergency stop.
	 *
	 * Corresponds to "Stůj!" (mimořádné zastavení, D1). The train must brake to a stand
	 * immediately, wherever it is, regardless of the current signal aspect or reserved path.
	 * Activates a hold override that is cleared only by a subsequent [RouteGranted] directive.
	 */
	data object HoldImmediately : TrainDirective
}
