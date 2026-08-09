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
 * or produces a non-success result at apply time on the simulation thread.
 *
 * **Distinction from [RejectionCode]:** [RejectionCode] is produced by [ActionValidator] on the
 * driver thread (pre-queue rejection, against the stale snapshot the driver holds).
 * [ApplyFailureCode] is produced by [DispatchDecisionApplier] on the kDisco simulation thread,
 * against the live simulation state at the moment each command is drained from the queue.
 * Pre-queue rejections appear as [ValidationVerdict.Rejected] with a [RejectionCode];
 * apply-time refusals appear as
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome] subtypes with a non-null
 * [ApplyFailureCode] reason.
 *
 * `ALL_PATHS_BLOCKED` is not an LLM failure: it arises from ordinary network contention at the
 * moment the route-reservation algorithm runs on the sim thread, which [ActionValidator] cannot
 * predict since it never runs pathfinding. It is reported separately from validator rejections
 * and excluded from the invalid-output rate.
 *
 * [ALL_PATHS_BLOCKED], [CONFLICT], and [NO_ROUTE_EXISTS] map one-to-one to the `:core`
 * `RouteRequestResult` non-success sealed subtypes and are read-only.
 */
enum class ApplyFailureCode {
	/**
	 * All candidate paths to the requested destination were physically occupied at the moment
	 * the route-reservation algorithm ran on the sim thread.
	 *
	 * Maps to `RouteRequestResult.AllPathsBlocked` (`:core`, read-only).
	 *
	 * **Not** an LLM failure — ordinary network contention.  Excluded from the invalid-output
	 * rate.  The agent should retry in a later tick when a path clears.
	 */
	ALL_PATHS_BLOCKED,

	/**
	 * A conflicting reservation or occupancy prevented the requested route from being reserved.
	 *
	 * Maps to `RouteRequestResult.Conflict` (`:core`, read-only).
	 */
	CONFLICT,

	/**
	 * No route exists in the network topology between the requested endpoints, or the interlocking
	 * kernel refused before topology lookup was attempted (Issue #834).
	 *
	 * Maps to `RouteRequestResult.NoRouteExists` (`:core`, read-only), whose KDoc documents both
	 * producing outcomes and why the second one is not reported as contention.
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
	 * KDoc). The [ActionOutcomeAggregator] pre-populates it with a zero count so it stays
	 * present in summaries.
	 */
	APPROVE_REJECTED,

	/**
	 * The station concurrent-train capacity was already full when the `approve_train`
	 * command was applied on the simulation thread.
	 *
	 * Can happen even when the pre-queue check passed: [ActionValidator] checks the cap against
	 * a snapshot
	 * ([cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation.activeCount])
	 * taken at the start of the previous tick, so two `approve_train` commands queued in the
	 * same driver cycle can both observe the same pre-tick count and both pass.
	 * [DispatchDecisionApplier] then enforces the cap in FIFO order on the sim thread: the
	 * first is admitted, the second receives this code because the live count now equals the
	 * maximum. The resulting
	 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Approved] surfaces in
	 * the next tick's `applied_outcomes` block so the agent learns the admission did not take
	 * effect. Not to be confused with the pre-queue [RejectionCode.CAPACITY_FULL] code.
	 */
	CAP_EXCEEDED_APPLY,

	/**
	 * The requested route origin was not contiguous with the train's actual position: it bounds
	 * none of the blocks the train holds or occupies, so the train could never reach the route.
	 *
	 * Maps to `RouteRequestResult.OriginNotContiguous` (`:core`).
	 *
	 * **Is** an LLM failure, unlike [ALL_PATHS_BLOCKED]. Contention clears on its own and a retry
	 * eventually succeeds; a wrongly *placed* route never will while the train stays put — the
	 * dispatcher has to ask for a different origin. Counting the two together would hide exactly
	 * the defect this code exists to measure (a correctly directed but wrongly placed route can
	 * reserve the whole line against its own train, leaving no train able to complete a journey).
	 * Fires on both the facade and legacy actuator paths, so a zero count is meaningful evidence.
	 */
	ORIGIN_NOT_CONTIGUOUS,

	/**
	 * A command was dropped at apply time because it was structurally invalid in a way that
	 * the driver-thread [ActionValidator] did not (or could not) detect.
	 *
	 * This code is applied defensively for forward-compatibility; a correctly-operating
	 * system should never produce it.  Its appearance in metrics indicates a gap in
	 * [ActionValidator] coverage.
	 */
	DROPPED_INVALID
}
