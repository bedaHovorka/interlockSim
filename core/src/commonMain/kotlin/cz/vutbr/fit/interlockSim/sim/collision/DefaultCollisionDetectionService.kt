/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

import cz.hovorka.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [CollisionDetectionService].
 *
 * SP1 (#611) shipped this as an intentionally thin backbone: warning-listener registry,
 * warning emission, and the [PauseController] bridge. Detection rules are added per
 * Goal 3 sub-phase so each rule ships with its own dedicated tests:
 * - [CollisionWarning.ReservationConflict] detection  → SP2 (#612) — implemented here
 * - [CollisionWarning.BlockEntryViolation] detection  → SP3 (#613) — implemented here
 * - [CollisionWarning.PredictiveCollision] detection   → SP4 (#614) — implemented here
 *
 * **Detection rules (SP2, #612):**
 * - [CollisionWarning.ReservationConflict]: emitted when [BlockEvent.BlockReserved] fires
 *   for a block whose [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.trainName]
 *   already belongs to a different train — indicating a double-reservation.
 * - [CollisionWarning.ReservationConflict]: also emitted when
 *   [BlockEvent.ReservationConflictDetected] fires (path blocked by a different train
 *   during [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath]).
 *   Duplicate warnings for the same conflict pair are suppressed within a
 *   [DEDUP_WINDOW_SECONDS]-second window.
 *
 * **Detection rules (SP3, #613):**
 * - [CollisionWarning.BlockEntryViolation] (reservation mismatch): emitted when
 *   [BlockEvent.OccupancySet] fires and the entering occupant's name does not match the
 *   block's reserved train name — the interlocking let a train onto a block reserved for
 *   another train. Entry into an **unreserved** block does not fire.
 * - [CollisionWarning.BlockEntryViolation] (double-occupancy): emitted when
 *   [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter] is called on an
 *   already-occupied block; `DynamicTrackBlock` emits the warning via `emitCustom` before
 *   throwing, and this service receives it via [SimulationEnvironment.onSimulationEvent].
 *   The two paths cannot double-fire for one entry: on double-occupancy, `enter()` throws
 *   before its [BlockEvent.OccupancySet] emission is reached.
 *
 * **Detection rules (SP4, #614) — predictive TTC:**
 * - [CollisionWarning.PredictiveCollision]: emitted after each [BlockEvent.BlockReserved]
 *   or [BlockEvent.BlockReleased] event when a snapshot provider (registered via
 *   [registerTrainSnapshotProvider]) indicates that a fast trailing train will reach the
 *   leading train's tail in fewer than [MIN_TIME_TO_COLLISION_SECONDS] seconds.
 *
 *   **Algorithm:**
 *   1. Maintain a set of currently active train IDs, and the set of blocks each one
 *      currently has reserved, by tracking `BlockReserved` / `BlockReleased` events per
 *      train.
 *   2. After each reservation-change event, collect a [TrainSnapshot] for every active
 *      train via the registered provider.
 *   3. For each ordered pair (leading, trailing) that currently **share at least one
 *      reserved block** — trains with no shared block are skipped, since
 *      [TrainSnapshot.totalDistance] is a per-train odometer and is not spatially
 *      comparable across unrelated routes — where `trailing.totalDistance <
 *      leading.totalDistance` **and** `trailing.velocity > leading.velocity`:
 *      - `separation = leading.totalDistance − leading.length − trailing.totalDistance`
 *      - `relativeVelocity = trailing.velocity − leading.velocity`
 *      - `ttc = separation / relativeVelocity`
 *   4. If `ttc ≤ [MIN_TIME_TO_COLLISION_SECONDS]` (and positive), emit
 *      [CollisionWarning.PredictiveCollision].  Duplicate warnings for the same
 *      (trailing, leading) pair are suppressed within a
 *      [PREDICTIVE_DEDUP_WINDOW_SECONDS]-second window.
 *
 *   Configure via [safetyMarginSeconds] and [minTimeToCollisionSeconds].
 *   Register snapshot providers with [registerTrainSnapshotProvider].
 *
 * The [BlockEvent] and [SimulationEvent] subscriptions are registered in the constructor
 * so they are buffered by [SimulationEnvironment.onBlockEvent] /
 * [SimulationEnvironment.onSimulationEvent] before
 * [cz.vutbr.fit.interlockSim.context.SimulationContext.run] is called.
 *
 * When a warning is emitted, the service:
 * 1. Calls all registered [onCollisionWarning] listeners synchronously. Each listener
 *    is isolated — a throwing listener is logged and does **not** prevent the remaining
 *    listeners or the pause request.
 * 2. Calls [PauseController.requestPause] so the operator can inspect the state.
 * 3. If [autoHaltTrainOnViolation] is true and the warning is a
 *    [CollisionWarning.BlockEntryViolation], calls the halt callback registered via
 *    [registerHaltCallback] for the entering train.
 *
 * @param pauseController Receives [PauseController.requestPause] on every emitted warning.
 * @param env The simulation environment used to subscribe to block and simulation events. Production
 *   wiring (CoreModule) always provides it; `null` is permitted only for unit tests that
 *   exercise the emission machinery directly via [emitWarning].
 * @since Issue #611 (Goal 3 SP1)
 */
class DefaultCollisionDetectionService(
	private val pauseController: PauseController,
	env: SimulationEnvironment? = null
) : CollisionDetectionService {
	private val listeners: MutableList<(CollisionWarning) -> Unit> = mutableListOf()
	private val haltCallbacks: MutableMap<String, () -> Unit> = mutableMapOf()

	/**
	 * When true, the halt callback registered via [registerHaltCallback] for the
	 * entering train is called immediately after a [CollisionWarning.BlockEntryViolation]
	 * is detected.
	 *
	 * Defaults to false. Can be set before [cz.vutbr.fit.interlockSim.context.SimulationContext.run]
	 * is called.
	 *
	 * @since Issue #613 (Goal 3 SP3)
	 */
	var autoHaltTrainOnViolation: Boolean = false

	/**
	 * Register a halt callback for a specific train.
	 *
	 * When [autoHaltTrainOnViolation] is true and a [CollisionWarning.BlockEntryViolation]
	 * is detected for [trainId], the [callback] is invoked immediately after warning delivery.
	 *
	 * @param trainId The train identifier to associate with the callback.
	 * @param callback The action to take to halt the train (e.g., call `fireStop()`).
	 * @since Issue #613 (Goal 3 SP3)
	 */
	fun registerHaltCallback(
		trainId: String,
		callback: () -> Unit
	) {
		haltCallbacks[trainId] = callback
	}

	/**
	 * Deduplication state for [BlockEvent.ReservationConflictDetected].
	 *
	 * Key: ordered pair (trainId, conflictingTrainId).
	 * Value: simulation time of the last emitted warning for that pair.
	 *
	 * Entries are kept small because a typical simulation has at most a handful of
	 * concurrent trains. No eviction is needed beyond the time-window guard.
	 */
	private val lastConflictWarningTime: MutableMap<Pair<String, String>, Double> = mutableMapOf()

	// ── SP4: Predictive TTC state ─────────────────────────────────────────────

	/**
	 * Safety margin added to [minTimeToCollisionSeconds] in the TTC check.
	 *
	 * A warning is emitted when `ttc < minTimeToCollisionSeconds + safetyMarginSeconds`.
	 * Increase this value to trigger warnings earlier.
	 *
	 * @since Issue #614 (Goal 3 SP4)
	 */
	var safetyMarginSeconds: Double = DEFAULT_SAFETY_MARGIN_SECONDS

	/**
	 * Maximum TTC value (seconds) at which a warning is emitted.
	 *
	 * Trains with a computed TTC **greater** than this value are considered safely
	 * separated and no warning is emitted.
	 *
	 * @since Issue #614 (Goal 3 SP4)
	 */
	var minTimeToCollisionSeconds: Double = DEFAULT_MIN_TIME_TO_COLLISION_SECONDS

	/**
	 * Snapshot provider for active trains.
	 *
	 * Returns the current kinematic [TrainSnapshot] for the given train ID, or `null`
	 * if no snapshot is available for that train (e.g., the train has not yet started).
	 *
	 * Default: always returns `null` (TTC checks produce no results). Override via
	 * [registerTrainSnapshotProvider].
	 */
	private var snapshotProvider: (trainId: String) -> TrainSnapshot? = { null }

	/**
	 * Deduplication state for [CollisionWarning.PredictiveCollision] warnings.
	 *
	 * Key: ordered pair (trailingTrainId, leadingTrainId).
	 * Value: simulation time of the last emitted warning for that pair.
	 */
	private val lastPredictiveTtcWarningTime: MutableMap<Pair<String, String>, Double> = mutableMapOf()

	/**
	 * Per-train count of currently reserved blocks, used to determine when a train
	 * has completely left the network (count → 0) and should be removed from
	 * [activeTrainIds].
	 */
	private val trainReservationCount: MutableMap<String, Int> = mutableMapOf()

	/**
	 * IDs of trains that currently have at least one reserved block.
	 *
	 * Populated by [BlockEvent.BlockReserved] and pruned by [BlockEvent.BlockReleased].
	 * Used as the candidate set for TTC evaluation.
	 */
	private val activeTrainIds: MutableSet<String> = mutableSetOf()

	/**
	 * Currently reserved blocks per train, populated by [BlockEvent.BlockReserved] and
	 * pruned by [BlockEvent.BlockReleased].
	 *
	 * Used to restrict TTC evaluation ([evaluatePredictiveTtc]) to train pairs that
	 * actually share a reserved block — per the SP4 algorithm ("for any shared block,
	 * estimate..."). Without this restriction, trains on unrelated routes would be
	 * compared by raw [TrainSnapshot.totalDistance], which is a per-train odometer
	 * since that train's own start and is not a shared spatial coordinate — comparing
	 * it across trains with no common block produces meaningless results.
	 *
	 * @since Issue #614 (Goal 3 SP4)
	 */
	private val trainReservedBlocks: MutableMap<String, MutableSet<DynamicTrackBlock>> = mutableMapOf()

	/**
	 * Register a snapshot provider for the predictive TTC monitor.
	 *
	 * The [provider] is called once per active train every time TTC is evaluated
	 * (after each [BlockEvent.BlockReserved] or [BlockEvent.BlockReleased] event).
	 * Returning `null` for a train ID means no TTC check is performed for that train.
	 *
	 * In production, register a lambda that reads position/velocity from the live
	 * [cz.vutbr.fit.interlockSim.sim.Train] processes (e.g., via
	 * [cz.vutbr.fit.interlockSim.sim.MultiTrainLoop.getTrainSnapshot]).  In tests,
	 * register a stub that returns controlled [TrainSnapshot] values.
	 *
	 * @param provider Callback returning [TrainSnapshot] for a given trainId, or `null`.
	 * @since Issue #614 (Goal 3 SP4)
	 */
	fun registerTrainSnapshotProvider(provider: (trainId: String) -> TrainSnapshot?) {
		snapshotProvider = provider
	}

	init {
		// Subscribe to domain-level block events (reserve / occupancy changes).
		// The call happens before run(), so it is buffered by DefaultSimulationContext.
		env?.onBlockEvent { event -> handleBlockEvent(event) }

		// Subscribe to raw kdisco events to receive CollisionWarning.BlockEntryViolation
		// emitted by DynamicTrackBlock.enter() via emitCustom before throwing on double-occupancy.
		env?.onSimulationEvent { event -> handleSimulationEvent(event) }
	}

	override fun onCollisionWarning(listener: (CollisionWarning) -> Unit) {
		listeners += listener
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun emit(warning: CollisionWarning) {
		logger.warn { "Collision warning: $warning" }
		// Snapshot the listener list so a re-entrant onCollisionWarning during delivery
		// cannot trigger a ConcurrentModificationException, and isolate each listener so
		// one bad callback cannot prevent the pause or starve later listeners.
		listeners.toList().forEach { listener ->
			try {
				listener(warning)
			} catch (e: Throwable) {
				logger.error(e) {
					"Collision warning listener threw; continuing to remaining listeners and pause"
				}
			}
		}
		pauseController.requestPause()
		if (autoHaltTrainOnViolation && warning is CollisionWarning.BlockEntryViolation) {
			haltCallbacks[warning.trainId]?.invoke()
		}
	}

	private fun handleSimulationEvent(event: SimulationEvent) {
		// Receive CollisionWarning.BlockEntryViolation emitted by DynamicTrackBlock.enter()
		// via emitCustom before throwing on double-occupancy.
		if (event is SimulationEvent.Custom) {
			val payload = event.payload
			if (payload is CollisionWarning.BlockEntryViolation) {
				emit(payload)
			}
		}
	}

	// Internal (not private) as a test seam: commonTest drives the detection rules
	// directly with synthetic BlockEvents, mirroring the emitWarning() precedent.
	internal fun handleBlockEvent(event: BlockEvent) {
		when (event) {
			is BlockEvent.BlockReserved -> {
				// Check whether the block is already reserved for a different train.
				val existingOwner = event.block.trainName
				if (existingOwner != null && existingOwner != event.trainId) {
					emit(
						CollisionWarning.ReservationConflict(
							trainId = event.trainId,
							conflictingTrainId = existingOwner,
							time = event.time,
							conflictingBlock = event.block
						)
					)
				}
				// SP4: track active train, its reserved blocks, and re-evaluate TTC.
				activeTrainIds.add(event.trainId)
				trainReservationCount[event.trainId] =
					(trainReservationCount[event.trainId] ?: 0) + 1
				trainReservedBlocks.getOrPut(event.trainId) { mutableSetOf() }.add(event.block)
				evaluatePredictiveTtc(event.time)
			}
			is BlockEvent.ReservationConflictDetected -> handleReservationConflictDetected(event)
			is BlockEvent.OccupancySet -> {
				// SP3 rule (#613): the entering train must match the block's reservation.
				// An unreserved block does not fire — only a mismatch with an existing
				// reservation indicates the interlocking let a train onto a foreign block.
				val reservedFor = event.block.trainName
				val occupantId = event.occupant.name
				if (reservedFor != null && reservedFor != occupantId) {
					emit(
						CollisionWarning.BlockEntryViolation(
							trainId = occupantId,
							block = event.block,
							time = event.time,
							reservedForAtDetection = reservedFor
						)
					)
				}
			}
			is BlockEvent.BlockReleased -> {
				// SP4: update reservation count; remove train when all blocks released.
				val remaining = (trainReservationCount[event.trainId] ?: 1) - 1
				trainReservedBlocks[event.trainId]?.remove(event.block)
				if (remaining <= 0) {
					activeTrainIds.remove(event.trainId)
					trainReservationCount.remove(event.trainId)
					trainReservedBlocks.remove(event.trainId)
				} else {
					trainReservationCount[event.trainId] = remaining
				}
				// Re-evaluate TTC because the leading train may have cleared a block,
				// changing the separation distance.
				evaluatePredictiveTtc(event.time)
			}
			is BlockEvent.OccupancyCleared -> {
				// Occupancy-cleared events do not by themselves indicate a hazard.
			}
		}
	}

	/**
	 * Re-emit [BlockEvent.ReservationConflictDetected] as a [CollisionWarning.ReservationConflict]
	 * after applying a [DEDUP_WINDOW_SECONDS]-second deduplication window per conflict pair.
	 *
	 * Two trains repeatedly competing for the same path would produce a
	 * [BlockEvent.ReservationConflictDetected] on every reservation attempt. Without
	 * deduplication this creates log spam and pauses the simulation too frequently.
	 * Warnings for the same (trainId, conflictingTrainId) pair are suppressed if the
	 * previous warning was emitted within [DEDUP_WINDOW_SECONDS] simulation seconds.
	 *
	 * @since Issue #612 (Goal 3 SP2)
	 */
	private fun handleReservationConflictDetected(event: BlockEvent.ReservationConflictDetected) {
		val key = Pair(event.trainId, event.conflictingTrainId)
		val lastTime = lastConflictWarningTime[key]
		if (lastTime != null && (event.time - lastTime) < DEDUP_WINDOW_SECONDS) {
			logger.debug {
				"handleReservationConflictDetected: suppressed duplicate for " +
					"(${event.trainId}, ${event.conflictingTrainId}) at t=${event.time} " +
					"(last at t=$lastTime)"
			}
			return
		}
		lastConflictWarningTime[key] = event.time
		emit(
			CollisionWarning.ReservationConflict(
				trainId = event.trainId,
				conflictingTrainId = event.conflictingTrainId,
				time = event.time,
				conflictingBlock = event.block
			)
		)
	}

	/**
	 * Evaluate predictive time-to-collision for all pairs of active trains.
	 *
	 * Called after each [BlockEvent.BlockReserved] or [BlockEvent.BlockReleased] event.
	 * Iterates over all ordered (leading, trailing) pairs and emits
	 * [CollisionWarning.PredictiveCollision] when the trailing train will reach the
	 * leading train's tail in fewer than [minTimeToCollisionSeconds] +
	 * [safetyMarginSeconds] seconds.
	 *
	 * @param eventTime Simulation time at which the triggering event occurred.
	 * @since Issue #614 (Goal 3 SP4)
	 */
	private fun evaluatePredictiveTtc(eventTime: Double) {
		if (activeTrainIds.size < 2) return

		// Collect snapshots; skip trains whose provider returns null.
		val snapshotPairs =
			activeTrainIds.mapNotNull { id ->
				snapshotProvider(id)?.let { snap -> id to snap }
			}
		val snapshots: Map<String, TrainSnapshot> = snapshotPairs.toMap()

		if (snapshots.size < 2) return

		val trainIds = snapshots.keys.toList()
		val threshold = minTimeToCollisionSeconds + safetyMarginSeconds

		for (leadingId in trainIds) {
			for (trailingId in trainIds) {
				if (leadingId == trailingId) continue
				val leading = snapshots[leadingId] ?: continue
				val trailing = snapshots[trailingId] ?: continue
				if (!sharesReservedBlock(leadingId, trailingId)) continue

				checkTrainPair(leadingId, leading, trailingId, trailing, eventTime, threshold)
			}
		}
	}

	/**
	 * True if [firstId] and [secondId] currently have at least one reserved block in
	 * common. Used to restrict TTC comparison to trains on convergent routes — see
	 * [trainReservedBlocks].
	 *
	 * @since Issue #614 (Goal 3 SP4)
	 */
	private fun sharesReservedBlock(
		firstId: String,
		secondId: String
	): Boolean {
		val firstBlocks = trainReservedBlocks[firstId]
		val secondBlocks = trainReservedBlocks[secondId]
		if (firstBlocks.isNullOrEmpty() || secondBlocks.isNullOrEmpty()) return false
		return firstBlocks.any { it in secondBlocks }
	}

	/**
	 * Evaluate a single ordered (leading, trailing) pair and emit
	 * [CollisionWarning.PredictiveCollision] if the trailing train is on a collision
	 * course with the leading train's tail within [threshold] seconds.
	 *
	 * @since Issue #614 (Goal 3 SP4)
	 */
	private fun checkTrainPair(
		leadingId: String,
		leading: TrainSnapshot,
		trailingId: String,
		trailing: TrainSnapshot,
		eventTime: Double,
		threshold: Double
	) {
		// Trailing must be behind leading (lower total distance).
		if (trailing.totalDistance >= leading.totalDistance) return

		// Trailing must be faster (catching up to leading).
		val relativeVelocity = trailing.velocity - leading.velocity
		if (relativeVelocity <= 0.0) return

		// Gap between trailing front and leading tail.
		val separation = leading.totalDistance - leading.length - trailing.totalDistance
		if (separation <= 0.0) return // already overlapping — BlockEntryViolation handles this

		val ttc = separation / relativeVelocity
		if (ttc > threshold) return

		// Deduplication: suppress repeated warnings for the same pair.
		val dedupKey = Pair(trailingId, leadingId)
		val lastTime = lastPredictiveTtcWarningTime[dedupKey]
		if (lastTime != null && (eventTime - lastTime) < PREDICTIVE_DEDUP_WINDOW_SECONDS) {
			logger.debug {
				"evaluatePredictiveTtc: suppressed duplicate for " +
					"($trailingId → $leadingId) at t=$eventTime (last at t=$lastTime)"
			}
			return
		}
		lastPredictiveTtcWarningTime[dedupKey] = eventTime

		logger.warn {
			val ttcTenths = (ttc * 10).toLong().coerceAtLeast(0)
			"evaluatePredictiveTtc: TTC=${ttcTenths / 10}.${ttcTenths % 10}s for " +
				"trailing=$trailingId (v=${trailing.velocity} m/s, " +
				"pos=${trailing.totalDistance} m) → " +
				"leading=$leadingId (v=${leading.velocity} m/s, " +
				"pos=${leading.totalDistance} m, len=${leading.length} m)"
		}
		emit(
			CollisionWarning.PredictiveCollision(
				trainId = trailingId,
				targetTrainId = leadingId,
				time = eventTime
			)
		)
	}

	/**
	 * Emit a [CollisionWarning] directly, bypassing internal event detection.
	 *
	 * Provided for test scenarios that need to trigger warning delivery without
	 * constructing a full collision-inducing simulation scenario. Not intended for
	 * production call sites.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	internal fun emitWarning(warning: CollisionWarning) = emit(warning)

	private companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Minimum gap (in simulation seconds) between two warnings for the same
		 * (trainId, conflictingTrainId) conflict pair. Prevents log spam when
		 * two trains continuously compete for the same path segment.
		 *
		 * @since Issue #612 (Goal 3 SP2)
		 */
		private const val DEDUP_WINDOW_SECONDS = 1.0

		/**
		 * Default safety margin in seconds added to [DEFAULT_MIN_TIME_TO_COLLISION_SECONDS].
		 * @since Issue #614 (Goal 3 SP4)
		 */
		internal const val DEFAULT_SAFETY_MARGIN_SECONDS = 10.0

		/**
		 * Default threshold: do not warn when computed TTC exceeds this value.
		 * @since Issue #614 (Goal 3 SP4)
		 */
		internal const val DEFAULT_MIN_TIME_TO_COLLISION_SECONDS = 30.0

		/**
		 * Deduplication window for predictive TTC warnings (seconds).
		 * Prevents the same pair from generating a storm of warnings at every
		 * block-reservation event.
		 * @since Issue #614 (Goal 3 SP4)
		 */
		private const val PREDICTIVE_DEDUP_WINDOW_SECONDS = 5.0
	}
}
