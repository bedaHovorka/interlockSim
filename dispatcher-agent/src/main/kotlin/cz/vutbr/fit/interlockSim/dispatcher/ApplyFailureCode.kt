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
 * Failure codes produced by [DispatchDecisionApplier] when an actuator command is refused
 * or produces a non-success result at apply time on the simulation thread (SP2c.18, Issue #841;
 * expanded in SP2c.20, Issue #843).
 *
 * ## Distinction from [RejectionCode]
 *
 * - [RejectionCode] codes are produced by [ActionValidator] on the **driver thread**
 *   (pre-queue rejection), against the stale snapshot the driver holds.
 * - [ApplyFailureCode] codes are produced by [DispatchDecisionApplier] on the **kDisco
 *   simulation thread**, against the live simulation state at the moment each command is
 *   drained from the queue.
 *
 * The distinction matters for metrics (SP2c.20): pre-queue rejections appear as
 * [ValidationVerdict.Rejected] with a [RejectionCode]; apply-time refusals appear as
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome] subtypes with a non-null
 * [ApplyFailureCode] reason.
 *
 * ## `ALL_PATHS_BLOCKED` is not an LLM failure
 *
 * [ALL_PATHS_BLOCKED] arises from ordinary network contention at the moment the route-reservation
 * algorithm runs on the sim thread.  The [ActionValidator] cannot run pathfinding (it is
 * sim-thread-bound), so a request may pass the validator and still be blocked.  Conflating
 * `ALL_PATHS_BLOCKED` with invalid-output errors would make a correctly-cautious agent appear
 * broken.  It is reported separately from validator rejections and excluded from the
 * invalid-output rate (SP2c.20 gate).
 *
 * ## One-to-one mapping with `RouteRequestResult` subtypes (read-only)
 *
 * [ALL_PATHS_BLOCKED], [CONFLICT], and [NO_ROUTE_EXISTS] map directly to the
 * `:core` `RouteRequestResult` non-success sealed subtypes.  These codes are read-only; no
 * `:core` file is changed to add them (Constraint C10).
 *
 * @since Issue #841 (SP2c.18 — Goal 10 apply-time cap enforcement);
 *        expanded Issue #843 (SP2c.20 — action attribution + C7 violation gate)
 */
enum class ApplyFailureCode {
	/**
	 * All candidate paths to the requested destination were physically occupied at the moment
	 * the route-reservation algorithm ran on the sim thread.
	 *
	 * Maps to `RouteRequestResult.AllPathsBlocked` (`:core`, read-only).
	 *
	 * **Not** an LLM failure — ordinary network contention.  Excluded from the invalid-output
	 * rate (SP2c.20 gate).  The agent should retry in a later tick when a path clears.
	 *
	 * @since Issue #843 (SP2c.20)
	 */
	ALL_PATHS_BLOCKED,

	/**
	 * A conflicting reservation or occupancy prevented the requested route from being reserved.
	 *
	 * Maps to `RouteRequestResult.Conflict` (`:core`, read-only).
	 *
	 * @since Issue #843 (SP2c.20)
	 */
	CONFLICT,

	/**
	 * No route exists in the network topology between the requested endpoints.
	 *
	 * Maps to `RouteRequestResult.NoRouteExists` (`:core`, read-only).
	 *
	 * @since Issue #843 (SP2c.20)
	 */
	NO_ROUTE_EXISTS,

	/**
	 * The `approve_train` command was applied but the admission callback rejected the train
	 * (e.g. the train was already active, had already exited, or was otherwise invalid).
	 * Distinct from [CAP_EXCEEDED_APPLY], which fires when the capacity ceiling is hit before
	 * the callback is invoked.
	 *
	 * **Not currently emitted in production** — `applyApproveTrain` only ever refuses with
	 * [CAP_EXCEEDED_APPLY]; surfacing a richer refusal reason here requires a wider
	 * `onApproveTrain` callback-signature change (the callback is `(String) -> Unit`, kept
	 * that way for backward compatibility — see the [DispatchDecisionApplier] constructor
	 * KDoc). Documented deferred follow-up to SP2c.20 (#843); the [ActionOutcomeAggregator]
	 * pre-populates it with a zero count so it stays present in summaries.
	 *
	 * @since Issue #843 (SP2c.20)
	 */
	APPROVE_REJECTED,

	/**
	 * The station concurrent-train capacity was already full when the `approve_train`
	 * command was applied on the simulation thread.
	 *
	 * ## Why this can happen even when the pre-queue check passed
	 *
	 * [ActionValidator] checks the cap against [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.activeCount],
	 * which is a snapshot captured at the start of the previous tick. Two `approve_train`
	 * commands queued in the same driver cycle both observe the same pre-tick count; [ActionValidator]
	 * may pass both if one slot appears free. [DispatchDecisionApplier] then enforces the cap
	 * in FIFO order on the sim thread: the first command is admitted (count incremented
	 * in-flight), and the second receives [CAP_EXCEEDED_APPLY] because the live count now equals
	 * the maximum.
	 *
	 * The [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Approved] carrying this code is published through the
	 * SP2c.17 channel and surfaces in the next tick's `applied_outcomes` block so the agent
	 * learns the admission did not take effect.
	 *
	 * Renamed from `CAP_EXCEEDED` in SP2c.20 (Issue #843) for disambiguation from the
	 * pre-queue [RejectionCode.CAPACITY_FULL] code.
	 */
	CAP_EXCEEDED_APPLY,

	/**
	 * A command was dropped at apply time because it was structurally invalid in a way that
	 * the driver-thread [ActionValidator] did not (or could not) detect.
	 *
	 * This code is applied defensively for forward-compatibility; a correctly-operating
	 * system should never produce it.  Its appearance in metrics indicates a gap in
	 * [ActionValidator] coverage.
	 *
	 * @since Issue #843 (SP2c.20)
	 */
	DROPPED_INVALID
}
