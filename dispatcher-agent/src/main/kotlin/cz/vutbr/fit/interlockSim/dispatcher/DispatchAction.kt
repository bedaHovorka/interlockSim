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
	data class ApproveTrain(
		val trainId: String
	) : DispatchAction {
		override val kind: String = "approve_train"
	}

	/**
	 * Request the interlocking to find and atomically reserve a free path for a named train.
	 *
	 * @property trainId Identifier of the train that will use the reserved route.
	 * @property fromEndpointName Name of the entry InOut or Signal (must be in
	 *   [ActionValidator]'s `validEndpointNames`; must not be a block ID).
	 * @property toEndpointName Name of the exit InOut or Signal (same constraints as
	 *   [fromEndpointName]).
	 * @property scope Whether [toEndpointName] is the train's final declared destination
	 *   ([RouteScope.EndToEnd], the default) or an intermediate hop toward it
	 *   ([RouteScope.Section]). See [RouteScope] for why this exists.
	 */
	data class RequestRoute(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		val scope: RouteScope = RouteScope.EndToEnd
	) : DispatchAction {
		override val kind: String = "request_route"
	}

	/**
	 * Release all track blocks reserved for a named train (the symmetric counterpart of
	 * [RequestRoute]).
	 *
	 * @property trainId Identifier of the train whose route should be released.
	 */
	data class CancelRoute(
		val trainId: String
	) : DispatchAction {
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

/**
 * Discriminates whether a [DispatchAction.RequestRoute]'s `toEndpointName` is the train's final
 * declared destination or an intermediate hop toward it (Issue #848's traffic-simulation-expert
 * ruling, implemented in Issue #829 — SP2c.6).
 *
 * ## Why this exists
 *
 * [ActionValidator] originally modeled every `RequestRoute` as targeting the train's declared
 * destination ([TrainView.destinationInOutName][cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView.destinationInOutName]).
 * That is correct for the LLM's `request_route` tool, but [RuleBasedEmissionStrategy] maps the
 * rule-based dispatcher's **hop-level** `DispatchDecision.ReservePath` (one block-boundary
 * separator at a time — see [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkInput]) onto
 * the same verb. Validating every hop's target against the *final* destination rejected nearly
 * every rule-based `RequestRoute` with `RejectionCode.TARGET_NOT_TRAIN_DESTINATION`, deadlocking
 * the Goal 10 Stage A3 determinism gate (`trainsExited == 0`, confirmed 2026-08-01).
 *
 * A payload field (not a sub-payload / nested type) keeps the four-tool actuator surface (C1/C8)
 * and the `request_route` tool schema the model sees completely unchanged — only production code
 * that already knows it is emitting a hop (`RuleBasedEmissionStrategy`) sets [Section].
 *
 * @since Issue #829 (SP2c.6 — Goal 10); Issue #848 (traffic-simulation-expert ruling)
 */
enum class RouteScope {
	/**
	 * `toEndpointName` is an intermediate hop (a block-boundary separator or signal) toward the
	 * train's destination, not the destination itself. [ActionValidator] does not check
	 * [DispatchAction.RequestRoute.toEndpointName] against the train's declared destination for
	 * this scope.
	 */
	Section,

	/**
	 * `toEndpointName` is the train's final declared destination. This is the default: every
	 * existing caller (the LLM's `request_route` tool, [ActionCandidateEnumerator][cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator])
	 * already targets the destination, so this preserves prior behavior unchanged.
	 */
	EndToEnd
}
