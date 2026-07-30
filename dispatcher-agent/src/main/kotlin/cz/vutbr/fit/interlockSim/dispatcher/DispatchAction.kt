/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

/**
 * Sealed four-action vocabulary for dispatcher decisions that flow from an LLM agent (or any
 * non-rule-based decision-maker) through [ActionValidator] before reaching the
 * [ActuatorCommandQueue] (SP2c.3, Issue #826).
 *
 * ## Purpose
 *
 * `DispatchAction` is the *input* type for [ActionValidator]. It is deliberately distinct from
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision]: the latter is an already-validated,
 * sim-thread-bound command; this type is an unvalidated *intent* that may be rejected before it
 * ever becomes a `DispatchDecision`.
 *
 * ## Four actions only — sealed
 *
 * Exactly four subtypes exist. The `sealed` keyword guarantees that no additional subtypes can
 * be added outside this file, so every `when` expression on a `DispatchAction` is exhaustive at
 * compile time.
 *
 * If `HoldTrain` (SP2c.25 vocabulary ruling) needs a fifth action it will be added here as
 * `data class HoldTrain(val trainId: String, val holdDurationSeconds: Double) : DispatchAction`.
 * Until that ruling lands, this vocabulary is intentionally minimal.
 *
 * ## The [kind] property
 *
 * [kind] is a stable, machine-readable string identifier for each subtype — useful for
 * serialisation, prompt generation, and log messages without relying on `javaClass.simpleName`.
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
sealed interface DispatchAction {
	/**
	 * Stable machine-readable identifier for this action type.
	 *
	 * Example values: `"approve_train"`, `"request_route"`, `"cancel_route"`, `"no_op"`.
	 */
	val kind: String

	/**
	 * Approve a queued train by name, admitting it from the pending-admission queue into the
	 * active simulation.
	 *
	 * @property trainId Identifier of the train to approve (must be non-blank and currently
	 *   present in [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.queued]).
	 */
	data class ApproveTrain(val trainId: String) : DispatchAction {
		override val kind: String = "approve_train"
	}

	/**
	 * Request the interlocking to find and atomically reserve a free end-to-end path for a
	 * named train.
	 *
	 * @property trainId Identifier of the train that will use the reserved route.
	 * @property fromEndpointName Name of the entry InOut or Signal (must be in
	 *   [ActionValidator]'s `validEndpointNames`; must not be a block ID).
	 * @property toEndpointName Name of the exit InOut or Signal (same constraints as
	 *   [fromEndpointName]).
	 */
	data class RequestRoute(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
	) : DispatchAction {
		override val kind: String = "request_route"
	}

	/**
	 * Release all track blocks reserved for a named train (the symmetric counterpart of
	 * [RequestRoute]).
	 *
	 * @property trainId Identifier of the train whose route should be released.
	 */
	data class CancelRoute(val trainId: String) : DispatchAction {
		override val kind: String = "cancel_route"
	}

	/**
	 * No-op: the dispatcher deliberately takes no action this tick.
	 *
	 * Carrying an explicit [NoOp] in a batch is valid and does not count toward the per-tick
	 * action limit checked by [ActionValidator.validateBatch].
	 */
	data object NoOp : DispatchAction {
		override val kind: String = "no_op"
	}
}
