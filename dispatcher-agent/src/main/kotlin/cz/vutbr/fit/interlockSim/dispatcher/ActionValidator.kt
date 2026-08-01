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

import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Pure, pre-execution validator for [DispatchAction] values (SP2c.3, Issue #826).
 *
 * ## Guarantees
 *
 * - **Pure**: [validate] and [validateBatch] never mutate any field of this instance or any
 *   of their arguments. Calling [validate] 1 000× on the same inputs always returns
 *   structurally equal [ValidationVerdict] values.
 * - **No simulation state**: this class holds no reference to
 *   [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort], [ActuatorCommandQueue], or any
 *   other live simulation object. All information comes from the constructor-supplied static
 *   sets and the [DispatcherObservation] passed at call time.
 *
 * ## Defence-in-depth contract
 *
 * [ActionValidator] is the **outer** gate: it runs on the agent driver thread before any
 * [ActuatorCommandQueue.postAll] call. The [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]
 * guard that was added in commit `e1e4b660` is the **inner** gate: it runs on the kDisco
 * simulation thread when `reservePath` is actually applied. Both gates check the same
 * reservation-conflict predicate for [DispatchAction.RequestRoute] (specifically
 * [RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET]). The outer gate produces a
 * machine-readable [RejectionCode] that the model can read next tick; the inner gate is
 * defence-in-depth (C10) and cannot produce a channel back to the agent.
 *
 * ## Limitation of [RejectionCode.NO_FREE_PATH]
 *
 * True path-freedom requires the reservation algorithm, which is impure and sim-thread-bound.
 * This validator must **not** run pathfinding. [RejectionCode.NO_FREE_PATH] is therefore
 * decided from observation-visible ownership only — it fires when all blocks in the observation
 * are non-FREE. That is sound but incomplete: requests passing this check may still fail inside
 * `reservePath`. Do not present this validator as a complete path-feasibility oracle; the `:core`
 * guard is retained as defence-in-depth for exactly this reason.
 *
 * ## Limitation of [RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION]
 *
 * This check is limited to HELD trains whose [cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView.signalAheadName]
 * is non-null. For RUNNING trains the block-to-endpoint mapping requires topology data not
 * present in the observation, so no origin check is performed. The `:core` interlocking layer
 * rejects routes whose origin does not connect to the train's current block regardless.
 *
 * @param validEndpointNames Exact set of InOut and Signal names this network recognises.
 *   Captured once at agent construction — topology is static and never changes during a run.
 * @param blockIds Exact set of block IDs. Used to detect
 *   [RejectionCode.ENDPOINT_IS_BLOCK_ID] before the endpoint-existence check.
 * @param maxActionsPerTick Maximum number of non-[DispatchAction.NoOp] valid actions allowed
 *   in one [validateBatch] call. Defaults to `3`.
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
class ActionValidator(
	private val validEndpointNames: Set<String>,
	private val blockIds: Set<String>,
	private val maxActionsPerTick: Int = DEFAULT_MAX_ACTIONS_PER_TICK
) {
	companion object {
		/** Default per-tick action limit used when [maxActionsPerTick] is not specified. */
		const val DEFAULT_MAX_ACTIONS_PER_TICK: Int = 3
	}

	/**
	 * Validates a single [DispatchAction] against the given [observation].
	 *
	 * Returns [ValidationVerdict.Valid] when the action passes all checks, or
	 * [ValidationVerdict.Rejected] with a [RejectionCode] and human-readable detail message
	 * when any check fails. Never mutates [action] or [observation].
	 *
	 * Note: batch-level codes ([RejectionCode.DUPLICATE_ACTION_THIS_TICK],
	 * [RejectionCode.ACTION_LIMIT_EXCEEDED]) are never produced by this method — use
	 * [validateBatch] to apply those checks.
	 */
	fun validate(
		action: DispatchAction,
		observation: DispatcherObservation
	): ValidationVerdict =
		when (action) {
			is DispatchAction.ApproveTrain -> validateApproveTrain(action, observation)
			is DispatchAction.RequestRoute -> validateRequestRoute(action, observation)
			is DispatchAction.CancelRoute -> validateCancelRoute(action, observation)
			is DispatchAction.NoOp -> ValidationVerdict.Valid
		}

	/**
	 * Validates a batch of [actions] in order, applying individual validation plus batch-level
	 * duplicate and action-count limits.
	 *
	 * ## Batch-level checks
	 *
	 * After each action passes individual validation, two additional batch checks are applied:
	 *
	 * - **[RejectionCode.DUPLICATE_ACTION_THIS_TICK]**: a structurally equal action has already
	 *   appeared in this batch (tracked by value equality). [DispatchAction.NoOp] is exempt from
	 *   duplicate tracking — multiple NoOps are silently accepted.
	 * - **[RejectionCode.ACTION_LIMIT_EXCEEDED]**: the running count of valid non-NoOp actions
	 *   has reached [maxActionsPerTick]; this action would exceed the limit.
	 *
	 * Duplicate tracking applies to ALL action types (valid or individually rejected) except
	 * [DispatchAction.NoOp]. The action-count limit applies only to individually-valid non-NoOp
	 * actions.
	 *
	 * ## Production caller (SP2c.5)
	 *
	 * [DispatchTickLoop][cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] does NOT call this
	 * batch method: it validates each emitted action one at a time via [validate] and enforces the
	 * per-tick cap with `take(maxActionsPerTick)` on the emission list. The optimistic intra-tick
	 * projection (`applyOptimistically`) feeds each action's post-state into the next [validate]
	 * call, so within-tick duplicates are caught by the single-action conflict rule rather than by
	 * [RejectionCode.DUPLICATE_ACTION_THIS_TICK]. This batch method is therefore exercised by tests
	 * and by any future caller that validates a pre-built action list in one shot; it is kept in sync
	 * with [validate] so the two paths cannot drift.
	 *
	 * @return One [Pair] per input action, in the same order, pairing each action with its
	 *   [ValidationVerdict].
	 */
	fun validateBatch(
		actions: List<DispatchAction>,
		observation: DispatcherObservation
	): List<Pair<DispatchAction, ValidationVerdict>> {
		val seenActions = mutableSetOf<DispatchAction>()
		var validCount = 0

		return actions.map { action ->
			val verdict =
				when {
					action is DispatchAction.NoOp -> ValidationVerdict.Valid

					action in seenActions ->
						ValidationVerdict.Rejected(
							RejectionCode.DUPLICATE_ACTION_THIS_TICK,
							"Duplicate action in this tick: ${action.kind}"
						)

					else -> {
						seenActions.add(action)
						val individual = validate(action, observation)
						if (individual is ValidationVerdict.Valid) {
							if (validCount >= maxActionsPerTick) {
								ValidationVerdict.Rejected(
									RejectionCode.ACTION_LIMIT_EXCEEDED,
									"Per-tick action limit ($maxActionsPerTick) exceeded; drop non-essential actions"
								)
							} else {
								validCount++
								ValidationVerdict.Valid
							}
						} else {
							individual
						}
					}
				}
			action to verdict
		}
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	private fun validateApproveTrain(
		action: DispatchAction.ApproveTrain,
		observation: DispatcherObservation
	): ValidationVerdict {
		if (action.trainId.isBlank()) {
			return rejected(RejectionCode.BLANK_ARGUMENT, "trainId must not be blank")
		}

		val trainInList = observation.trains.find { it.trainId == action.trainId }
		val trainInQueue = observation.queued.find { it.trainId == action.trainId }

		if (trainInList == null && trainInQueue == null) {
			return rejected(RejectionCode.UNKNOWN_TRAIN, "Unknown train '${action.trainId}'")
		}

		if (trainInList != null && trainInList.phase == TrainPhase.EXITED) {
			return rejected(
				RejectionCode.TRAIN_ALREADY_EXITED,
				"Train '${action.trainId}' has already exited the network"
			)
		}

		if (trainInList != null &&
			trainInList.phase in setOf(TrainPhase.RUNNING, TrainPhase.DWELLING, TrainPhase.HELD)
		) {
			return rejected(
				RejectionCode.TRAIN_ALREADY_ACTIVE,
				"Train '${action.trainId}' is already active (phase: ${trainInList.phase})"
			)
		}

		if (trainInQueue == null) {
			return rejected(
				RejectionCode.TRAIN_NOT_QUEUED,
				"Train '${action.trainId}' is not in the admission queue"
			)
		}

		if (observation.activeCount >= observation.capacity) {
			return rejected(
				RejectionCode.CAPACITY_FULL,
				"Station at capacity: ${observation.activeCount} of ${observation.capacity} active trains"
			)
		}

		return ValidationVerdict.Valid
	}

	private fun validateRequestRoute(
		action: DispatchAction.RequestRoute,
		observation: DispatcherObservation
	): ValidationVerdict {
		validateRequestRouteArguments(action)?.let { return it }

		val trainInList = observation.trains.find { it.trainId == action.trainId }
		val trainInQueue = observation.queued.find { it.trainId == action.trainId }

		validateRequestRouteTrainPhase(action, trainInList, trainInQueue)?.let { return it }
		validateRequestRouteConflict(action, observation)?.let { return it }
		validateRequestRouteDestinationAndOrigin(action, trainInList)?.let { return it }
		validateRequestRoutePathAvailability(action, observation)?.let { return it }

		return ValidationVerdict.Valid
	}

	// ── validateRequestRoute helpers (split out to keep cyclomatic complexity under threshold) ──

	private fun validateRequestRouteArguments(action: DispatchAction.RequestRoute): ValidationVerdict.Rejected? {
		if (action.trainId.isBlank() || action.fromEndpointName.isBlank() || action.toEndpointName.isBlank()) {
			return rejected(
				RejectionCode.BLANK_ARGUMENT,
				"All three arguments (trainId, fromEndpointName, toEndpointName) must be non-blank"
			)
		}

		if (action.fromEndpointName in blockIds || action.toEndpointName in blockIds) {
			val offender =
				if (action.fromEndpointName in blockIds) action.fromEndpointName else action.toEndpointName
			return rejected(
				RejectionCode.ENDPOINT_IS_BLOCK_ID,
				"'$offender' is a block ID, not an InOut or Signal name — " +
					"valid endpoint names: ${validEndpointNames.sorted().joinToString(", ")}"
			)
		}

		if (action.fromEndpointName !in validEndpointNames || action.toEndpointName !in validEndpointNames) {
			val offender =
				if (action.fromEndpointName !in validEndpointNames) action.fromEndpointName else action.toEndpointName
			return rejected(
				RejectionCode.UNKNOWN_ENDPOINT,
				"Unknown endpoint '$offender' — valid names: ${validEndpointNames.sorted().joinToString(", ")}"
			)
		}

		return null
	}

	private fun validateRequestRouteTrainPhase(
		action: DispatchAction.RequestRoute,
		trainInList: TrainView?,
		trainInQueue: QueuedTrainView?
	): ValidationVerdict.Rejected? {
		if (trainInList == null && trainInQueue == null) {
			return rejected(RejectionCode.UNKNOWN_TRAIN, "Unknown train '${action.trainId}'")
		}

		if (trainInList != null && trainInList.phase == TrainPhase.EXITED) {
			return rejected(
				RejectionCode.TRAIN_ALREADY_EXITED,
				"Train '${action.trainId}' has already exited; cannot request a route"
			)
		}

		if ((trainInList != null && trainInList.phase == TrainPhase.QUEUED) ||
			(trainInQueue != null && trainInList == null)
		) {
			return rejected(
				RejectionCode.TRAIN_NOT_ADMITTED,
				"Train '${action.trainId}' has not been admitted yet (still queued)"
			)
		}

		return null
	}

	private fun validateRequestRouteConflict(
		action: DispatchAction.RequestRoute,
		observation: DispatcherObservation
	): ValidationVerdict.Rejected? {
		val existingReservation =
			observation.reservations.find { it.trainId == action.trainId } ?: return null

		// Forward extension: train holds a route to X, and the new request starts from X — this is
		// the normal multi-hop case where the dispatcher extends the path one section at a time.
		// PathReservationRegistry's own merge precondition is new.start == old.target; mirror it.
		if (action.fromEndpointName == existingReservation.targetName) {
			return null
		}

		return if (existingReservation.targetName == action.toEndpointName) {
			rejected(
				RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET,
				"Train '${action.trainId}' already holds a route to '${action.toEndpointName}' " +
					"(blocks: ${existingReservation.blockIds.joinToString(",")})"
			)
		} else {
			rejected(
				RejectionCode.ROUTE_HELD_TO_DIFFERENT_TARGET,
				"Train '${action.trainId}' holds a route to '${existingReservation.targetName}'; " +
					"issue cancel_route first before requesting a new route to '${action.toEndpointName}'"
			)
		}
	}

	private fun validateRequestRouteDestinationAndOrigin(
		action: DispatchAction.RequestRoute,
		trainInList: TrainView?
	): ValidationVerdict.Rejected? {
		// Section-scoped requests (Issue #848 / #829) target an intermediate hop, not the train's
		// final destination — RuleBasedEmissionStrategy's per-block-boundary ReservePath mapping is
		// the production example. Only EndToEnd requests are checked against the declared
		// destination.
		if (action.scope == RouteScope.EndToEnd &&
			trainInList != null &&
			action.toEndpointName != trainInList.destinationInOutName
		) {
			return rejected(
				RejectionCode.TARGET_NOT_TRAIN_DESTINATION,
				"'${action.toEndpointName}' is not the declared destination of train '${action.trainId}' " +
					"(declared: '${trainInList.destinationInOutName}'). " +
					"Note: domain ruling pending — this may be relaxed for shunting/reversal scenarios."
			)
		}

		if (trainInList != null &&
			trainInList.phase == TrainPhase.HELD &&
			trainInList.signalAheadName != null &&
			action.fromEndpointName != trainInList.signalAheadName
		) {
			return rejected(
				RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION,
				"Train '${action.trainId}' is HELD at signal '${trainInList.signalAheadName}'; " +
					"fromEndpointName must be '${trainInList.signalAheadName}', got '${action.fromEndpointName}'"
			)
		}

		return null
	}

	private fun validateRequestRoutePathAvailability(
		action: DispatchAction.RequestRoute,
		observation: DispatcherObservation
	): ValidationVerdict.Rejected? {
		val allBlocksBusy =
			observation.blocks.isNotEmpty() &&
				observation.blocks.all { block ->
					block.state != TrackFacility.State.FREE && block.occupantTrainId != action.trainId
				}
		if (allBlocksBusy) {
			return rejected(
				RejectionCode.NO_FREE_PATH,
				"All observable blocks are owned by other trains; no plausible free path to '${action.toEndpointName}'. " +
					"Note: this check is observation-only and incomplete — failed requests surface as " +
					"ALL_PATHS_BLOCKED outcomes next tick."
			)
		}
		return null
	}

	private fun validateCancelRoute(
		action: DispatchAction.CancelRoute,
		observation: DispatcherObservation
	): ValidationVerdict {
		if (action.trainId.isBlank()) {
			return rejected(RejectionCode.BLANK_ARGUMENT, "trainId must not be blank")
		}

		val trainInList = observation.trains.find { it.trainId == action.trainId }
		val trainInQueue = observation.queued.find { it.trainId == action.trainId }

		if (trainInList == null && trainInQueue == null) {
			return rejected(RejectionCode.UNKNOWN_TRAIN, "Unknown train '${action.trainId}'")
		}

		if (trainInList != null && trainInList.phase == TrainPhase.EXITED) {
			return rejected(
				RejectionCode.TRAIN_ALREADY_EXITED,
				"Train '${action.trainId}' has already exited; no route to cancel"
			)
		}

		val reservation = observation.reservations.find { it.trainId == action.trainId }
		if (reservation == null) {
			return rejected(
				RejectionCode.NO_ROUTE_HELD,
				"Train '${action.trainId}' holds no path reservation; nothing to cancel"
			)
		}

		// Safety check: is the train physically on a reserved block?
		val frontSection = trainInList?.frontSectionName
		if (frontSection != null) {
			val frontBlock = observation.blocks.find { it.blockId == frontSection }
			if (frontBlock != null &&
				frontBlock.state == TrackFacility.State.OCCUPIED &&
				frontBlock.occupantTrainId == action.trainId &&
				frontSection in reservation.blockIds
			) {
				return rejected(
					RejectionCode.TRAIN_ON_RESERVED_BLOCK,
					"Train '${action.trainId}' is currently occupying block '$frontSection', " +
						"which is part of its reservation — unsafe to cancel route mid-transit"
				)
			}
		}

		return ValidationVerdict.Valid
	}

	private fun rejected(
		code: RejectionCode,
		detail: String
	): ValidationVerdict.Rejected = ValidationVerdict.Rejected(code, detail)
}
