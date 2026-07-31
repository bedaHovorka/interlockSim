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
 * Rejection codes produced by [ActionValidator] when a [DispatchAction] fails pre-execution
 * validation (SP2c.3, Issue #826).
 *
 * Codes are grouped by the action type they guard and by their semantic category.
 *
 * ## Reading the groups
 *
 * - **Shape / identity** — argument-level guards applicable to any action with a `trainId` or
 *   endpoint-name argument.
 * - **approve_train** — guards specific to [DispatchAction.ApproveTrain].
 * - **request_route** — guards specific to [DispatchAction.RequestRoute].
 * - **cancel_route** — guards specific to [DispatchAction.CancelRoute].
 * - **meta** — batch-level limits managed by [ActionValidator.validateBatch].
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
enum class RejectionCode {
	// ── Shape / identity ─────────────────────────────────────────────────────

	/** `trainId` was not found in the current [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation]. */
	UNKNOWN_TRAIN,

	/** A required string argument was blank (empty or whitespace-only). */
	BLANK_ARGUMENT,

	/**
	 * `fromEndpointName` or `toEndpointName` was not found in the set of valid InOut / Signal
	 * names for this network.
	 */
	UNKNOWN_ENDPOINT,

	/**
	 * `fromEndpointName` or `toEndpointName` is a block ID (from the block-ID set) rather than
	 * an InOut or Signal name. Block IDs are never valid route endpoints.
	 */
	ENDPOINT_IS_BLOCK_ID,

	// ── approve_train ─────────────────────────────────────────────────────────

	/**
	 * The train is already approved and active in the simulation (phase is
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.RUNNING],
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.DWELLING], or
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.HELD]).
	 */
	TRAIN_ALREADY_ACTIVE,

	/**
	 * The train is known but is not currently waiting in the admission queue
	 * ([cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.queued]).
	 */
	TRAIN_NOT_QUEUED,

	/** The station is at full concurrent-train capacity; no room to admit another train. */
	CAPACITY_FULL,

	/**
	 * The train has already exited the network (phase is
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.EXITED]).
	 */
	TRAIN_ALREADY_EXITED,

	// ── request_route ─────────────────────────────────────────────────────────

	/**
	 * The train already holds a path reservation whose target equals the requested
	 * `toEndpointName` — re-requesting the same route is a no-op that should not reach the
	 * interlocking. This is the machine-readable defence against Issue #814 Symptom 3.
	 *
	 * The outer [ActionValidator] gate fires **before** any [ActuatorCommandQueue] post, and
	 * before [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService] is
	 * reached, so the redundant request never becomes a
	 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision], never enters the queue, and never
	 * reaches `reservePath`.
	 */
	ROUTE_ALREADY_HELD_TO_SAME_TARGET,

	/**
	 * The train holds a reservation toward a *different* target. The dispatcher must cancel
	 * that route with [DispatchAction.CancelRoute] before requesting a new one.
	 */
	ROUTE_HELD_TO_DIFFERENT_TARGET,

	/**
	 * The train has not been admitted to the simulation yet (phase is
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.QUEUED]); requesting a
	 * route for a queued train is premature.
	 */
	TRAIN_NOT_ADMITTED,

	/**
	 * `toEndpointName` does not match the train's declared destination
	 * ([cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView.destinationInOutName]).
	 *
	 * **Domain open point (railway-civil-engineer ruling pending):** this rejection may be
	 * too strict for shunting and reversal scenarios where an intermediate endpoint serves as
	 * a temporary target. The first-cut implementation treats it as a hard rejection; a future
	 * ruling may demote it to a warning surfaced in the affordance line instead.
	 */
	TARGET_NOT_TRAIN_DESTINATION,

	/**
	 * Every track block visible in the current observation is already owned (reserved or
	 * occupied) by another train; no path to any endpoint is plausibly free.
	 *
	 * ## Stated limitation
	 *
	 * True path-freedom requires running the reservation algorithm, which is impure and
	 * sim-thread-bound. [ActionValidator] **must not** run pathfinding. This code is therefore
	 * decided from observation-visible ownership only: it fires when *all* blocks in
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.blocks] are
	 * non-FREE (owned by other trains). That is sound — if no block is free there is definitely
	 * no free path — but incomplete: requests that pass this check may still fail inside
	 * `reservePath` and surface as `ALL_PATHS_BLOCKED` applied-outcomes next tick (SP2c.17).
	 *
	 * Do not present this validator as complete. The `:core` guard is retained as defence-in-depth.
	 */
	NO_FREE_PATH,

	/**
	 * The `fromEndpointName` does not correspond to the train's current position.
	 *
	 * A [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase.HELD] train is stopped
	 * at [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView.signalAheadName]; the
	 * route must start from that signal. If `fromEndpointName` differs from `signalAheadName`
	 * when the train is HELD and `signalAheadName` is non-null, the origin is wrong.
	 *
	 * ## Stated limitation
	 *
	 * This check is limited to the HELD+signalAheadName case. For RUNNING trains the
	 * block-to-endpoint mapping requires topology data not present in the observation, so no
	 * origin check is performed. The `:core` interlocking layer rejects routes whose origin
	 * does not connect to the train's current block regardless.
	 */
	ORIGIN_NOT_AT_TRAIN_POSITION,

	// ── cancel_route ─────────────────────────────────────────────────────────

	/** The train holds no path reservation; there is nothing to cancel. */
	NO_ROUTE_HELD,

	/**
	 * The train is physically occupying (phase OCCUPIED in
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView.state]) at least one block
	 * in its reservation. Releasing a route while the train is on a reserved block is unsafe.
	 */
	TRAIN_ON_RESERVED_BLOCK,

	// ── Meta (batch-level) ────────────────────────────────────────────────────

	/**
	 * The same action value appeared more than once in the current [ActionValidator.validateBatch]
	 * call. Duplicate actions are structurally identical (same action type and same field values).
	 */
	DUPLICATE_ACTION_THIS_TICK,

	/**
	 * The batch already contains [ActionValidator.maxActionsPerTick] valid (non-[DispatchAction.NoOp])
	 * actions; this action would exceed the per-tick limit.
	 */
	ACTION_LIMIT_EXCEEDED
}
