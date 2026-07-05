/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Temporal conflict detector for Goal 9 SP2 (#583).
 *
 * Scans a configurable lookahead window to identify future resource contention
 * **before** trains physically arrive at the contested block.  For each pair of
 * active trains, the detector projects each train's planned occupancy across
 * the next [lookaheadWindowSeconds] seconds.  When two projections overlap on the
 * same [DynamicTrackBlock] within that window, a [TemporalConflictEvent] is emitted
 * to all registered listeners.
 *
 * ## Algorithm
 *
 * After each [BlockEvent.BlockReserved] or [BlockEvent.BlockReleased] event:
 * 1. Collect a [List]`<`[ProjectedOccupancy]`>` for every active train via the
 *    registered [projectionProvider].
 * 2. For each ordered pair (A, B) of trains, iterate over all block pairs
 *    (occA ∈ A's projections, occB ∈ B's projections):
 *    - Skip if the blocks differ.
 *    - Skip if either block's enter offset exceeds [lookaheadWindowSeconds].
 *    - Overlap check: `occA.enterOffset < occB.exitOffset && occB.enterOffset < occA.exitOffset`
 * 3. On overlap, emit one [TemporalConflictEvent] per (trainA, trainB, block) pair,
 *    suppressing duplicates within [DEDUP_WINDOW_SECONDS].
 *
 * The pairwise scan in step 2 is O(activeTrains² × occupanciesPerTrain²) per triggering
 * event. This is accepted as-is for expected fleet sizes (a handful of concurrent
 * trains per simulation); revisit only if profiling shows it is a bottleneck at larger
 * scale.
 *
 * ## Provider contract
 *
 * Register a provider via [registerProjectionProvider].  The provider is called once
 * per active train during each evaluation.  Returning `null` for a train ID means the
 * train is skipped for that scan (e.g., the train has not yet started or its path is
 * unknown).  Returning an empty list also skips the train.
 *
 * Each [ProjectedOccupancy] in the returned list must satisfy:
 * - `enterOffsetSeconds ≥ 0.0`
 * - `exitOffsetSeconds > enterOffsetSeconds`
 *
 * In production, wire this via `MultiTrainLoop` or a similar loop once it knows the
 * ordered sequence of reserved blocks and each train's current velocity.  In tests,
 * register a stub that returns controlled [ProjectedOccupancy] values.
 *
 * **Current production status (as of Goal 9 SP2, #583):** no production code path calls
 * [registerProjectionProvider] yet. With the default no-op provider (always returns
 * `null`), this detector is fully wired into [BlockEvent] delivery but inert — it never
 * emits a [TemporalConflictEvent] in a real simulation run. Spatial conflict detection
 * (Goal 9 SP1, #580) already ships; wiring a real projection provider (e.g. from
 * `MultiTrainLoop`) is tracked as follow-up work in a later sprint, not part of this PR.
 *
 * ## Configuring the lookahead window
 *
 * ```kotlin
 * val detector = env.getTemporalConflictDetector()
 * detector.lookaheadWindowSeconds = 60.0   // override default of 30 s
 * ```
 *
 * @param env The simulation environment used to subscribe to block events.
 *   Production wiring (CoreModule) always provides it; `null` is permitted only for
 *   unit tests that drive [handleBlockEvent] directly.
 *
 * @since Issue #583 (Goal 9 SP2)
 */
class TemporalConflictDetector(
	env: SimulationEnvironment? = null
) {
	private val listeners: MutableList<(TemporalConflictEvent) -> Unit> = mutableListOf()

	/**
	 * Lookahead window in simulation seconds.
	 *
	 * Block occupancies whose [ProjectedOccupancy.enterOffsetSeconds] exceeds this value
	 * are ignored by the scan.  Decrease to reduce false-positive risk; increase to give
	 * the conflict-resolution layer more lead time.
	 *
	 * @since Issue #583 (Goal 9 SP2)
	 */
	var lookaheadWindowSeconds: Double = DEFAULT_LOOKAHEAD_WINDOW_SECONDS

	/**
	 * Projection provider for active trains.
	 *
	 * Returns the ordered list of [ProjectedOccupancy] entries for the given train ID
	 * within the lookahead window, or `null` if no projection is available.
	 *
	 * Default: always returns `null` (no conflicts detected).  Override via
	 * [registerProjectionProvider].
	 */
	private var projectionProvider: (trainId: String) -> List<ProjectedOccupancy>? = { null }

	// ── Active train tracking ─────────────────────────────────────────────────

	/** Per-train count of currently reserved blocks.  Used to determine when a
	 *  train has left the network (count → 0) and should be removed. */
	private val trainReservationCount: MutableMap<String, Int> = mutableMapOf()

	/** IDs of trains that currently have at least one reserved block. */
	private val activeTrainIds: MutableSet<String> = mutableSetOf()

	// ── Deduplication ─────────────────────────────────────────────────────────

	/**
	 * Simulation time of the last emitted warning for a (trainA, trainB, block) triple.
	 *
	 * Keys are normalised so that (A, B) and (B, A) map to the same entry:
	 * the lexicographically smaller train ID is stored as the first element.
	 */
	private val lastConflictTime: MutableMap<Triple<String, String, DynamicTrackBlock>, Double> =
		mutableMapOf()

	/**
	 * Number of dedup entries currently retained. Exposed as `internal` purely so unit
	 * tests can assert that [pruneDedupStateForDepartedTrain] evicts stale entries; not
	 * part of the public API.
	 */
	internal val lastConflictTimeSizeForTests: Int
		get() = lastConflictTime.size

	init {
		env?.onBlockEvent { event -> handleBlockEvent(event) }
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Register a listener that is called each time a [TemporalConflictEvent] is detected.
	 *
	 * Listeners are called synchronously on the simulation thread.  Each listener is
	 * isolated — a throwing listener is logged but does **not** prevent delivery to the
	 * remaining listeners.
	 *
	 * Unlike [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onTemporalConflictEvent],
	 * this method has **no** "silently ignored after run() has started" guard: it always
	 * registers the listener immediately. Callers that obtain the detector directly via
	 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getTemporalConflictDetector]
	 * (rather than going through the `SimulationEnvironment` wrapper) do not get that
	 * pre-run-only contract.
	 *
	 * @param listener Callback invoked for each detected temporal conflict.
	 * @since Issue #583 (Goal 9 SP2)
	 */
	fun onTemporalConflictEvent(listener: (TemporalConflictEvent) -> Unit) {
		listeners += listener
	}

	/**
	 * Register the projection provider used to obtain future block occupancies for a
	 * given train.
	 *
	 * The provider is called once per active train on every evaluation cycle (triggered
	 * after [BlockEvent.BlockReserved] or [BlockEvent.BlockReleased]).  Returning `null`
	 * means the train is skipped for that scan; returning an empty list has the same
	 * effect.
	 *
	 * In production, use the train-loop's knowledge of reserved-path ordering and
	 * current velocity to build the list.  In tests, use a fixed stub.
	 *
	 * This method is intentionally callable at any time, including after the simulation
	 * has started, and is **not** subject to the "silently ignored after run()" guard that
	 * applies to [cz.vutbr.fit.interlockSim.context.SimulationEnvironment]'s listener
	 * subscriptions. The real production wiring (e.g. `MultiTrainLoop`) may only learn a
	 * train's projected path *while the simulation is running*, so registering — and
	 * re-registering, if the provider needs to be swapped — must remain possible after
	 * `run()` has started.
	 *
	 * @param provider Callback returning `List<ProjectedOccupancy>` for a trainId, or `null`.
	 * @since Issue #583 (Goal 9 SP2)
	 */
	fun registerProjectionProvider(provider: (trainId: String) -> List<ProjectedOccupancy>?) {
		projectionProvider = provider
	}

	// ── Internal (package-private for tests) ─────────────────────────────────

	/**
	 * Handle a [BlockEvent].  Called by the block-event subscription registered in [init].
	 * Exposed as `internal` so that unit tests can drive it directly with synthetic events
	 * without starting a full simulation.
	 */
	internal fun handleBlockEvent(event: BlockEvent) {
		when (event) {
			is BlockEvent.BlockReserved -> {
				activeTrainIds.add(event.trainId)
				trainReservationCount[event.trainId] =
					(trainReservationCount[event.trainId] ?: 0) + 1
				evaluateTemporalConflicts(event.time)
			}
			is BlockEvent.BlockReleased -> {
				val remaining = (trainReservationCount[event.trainId] ?: 1) - 1
				if (remaining <= 0) {
					activeTrainIds.remove(event.trainId)
					trainReservationCount.remove(event.trainId)
					pruneDedupStateForDepartedTrain(event.trainId)
				} else {
					trainReservationCount[event.trainId] = remaining
				}
				evaluateTemporalConflicts(event.time)
			}
			is BlockEvent.ReservationConflictDetected,
			is BlockEvent.OccupancySet,
			is BlockEvent.OccupancyCleared -> {
				// These events are not relevant for temporal lookahead scanning.
			}
		}
	}

	/**
	 * Remove dedup-state entries that reference a train after it has fully departed
	 * the network (all its reserved blocks released), preventing [lastConflictTime]
	 * from growing without bound over a long-running simulation with many trains
	 * cycling through.
	 */
	private fun pruneDedupStateForDepartedTrain(trainId: String) {
		lastConflictTime.keys.removeAll { (first, second, _) -> first == trainId || second == trainId }
	}

	// ── Private detection logic ───────────────────────────────────────────────

	/**
	 * Run a full lookahead scan for all pairs of active trains.
	 *
	 * O(activeTrains²) pairwise scan — see the class-level "Algorithm" section for the
	 * full complexity discussion and why it is accepted at current expected fleet sizes.
	 *
	 * @param detectionTime Simulation time at which the triggering block event occurred.
	 */
	private fun evaluateTemporalConflicts(detectionTime: Double) {
		if (activeTrainIds.size < 2) return

		// Collect projections; skip trains for which no provider data is available.
		val projections: Map<String, List<ProjectedOccupancy>> =
			activeTrainIds
				.mapNotNull { trainId ->
					projectionProvider(trainId)
						?.takeIf { it.isNotEmpty() }
						?.let { trainId to it }
				}.toMap()

		if (projections.size < 2) return

		val trainIds = projections.keys.toList()

		for (i in 0 until trainIds.size) {
			for (j in (i + 1) until trainIds.size) {
				val trainA = trainIds[i]
				val trainB = trainIds[j]
				val projA = projections[trainA] ?: continue
				val projB = projections[trainB] ?: continue

				checkTrainPair(trainA, projA, trainB, projB, detectionTime)
			}
		}
	}

	/**
	 * Check every (occA, occB) block pair for temporal overlap and emit if warranted.
	 */
	private fun checkTrainPair(
		trainA: String,
		projA: List<ProjectedOccupancy>,
		trainB: String,
		projB: List<ProjectedOccupancy>,
		detectionTime: Double
	) {
		for (occA in projA) {
			if (occA.enterOffsetSeconds > lookaheadWindowSeconds) continue
			for (occB in projB) {
				if (occB.enterOffsetSeconds > lookaheadWindowSeconds) continue
				if (occA.block != occB.block) continue

				// Interval-overlap test: [enterA, exitA) ∩ [enterB, exitB) ≠ ∅
				if (occA.enterOffsetSeconds >= occB.exitOffsetSeconds) continue
				if (occB.enterOffsetSeconds >= occA.exitOffsetSeconds) continue

				// Predicted moment the overlap begins (= max of the two enter times)
				val predictedConflictTime =
					detectionTime + maxOf(occA.enterOffsetSeconds, occB.enterOffsetSeconds)

				emitIfNotDeduplicated(trainA, trainB, occA.block, predictedConflictTime, detectionTime)
			}
		}
	}

	/**
	 * Emit a [TemporalConflictEvent] for the (trainA, trainB, block) triple unless a
	 * warning for the same triple was emitted within [DEDUP_WINDOW_SECONDS].
	 *
	 * The deduplication key is normalised (lexicographically smaller train ID first) so
	 * that (A, B) and (B, A) share the same entry.
	 */
	private fun emitIfNotDeduplicated(
		trainA: String,
		trainB: String,
		block: DynamicTrackBlock,
		predictedConflictTime: Double,
		detectionTime: Double
	) {
		// Normalise so (A,B) and (B,A) share a dedup entry.
		val (first, second) =
			if (trainA <= trainB) Pair(trainA, trainB) else Pair(trainB, trainA)
		val dedupKey = Triple(first, second, block)

		val lastTime = lastConflictTime[dedupKey]
		if (lastTime != null && (detectionTime - lastTime) < DEDUP_WINDOW_SECONDS) {
			logger.debug {
				"evaluateTemporalConflicts: suppressed duplicate for " +
					"($first, $second, ${block.name}) at t=$detectionTime (last at t=$lastTime)"
			}
			return
		}
		lastConflictTime[dedupKey] = detectionTime

		val event =
			TemporalConflictEvent(
				trainId = trainA,
				otherTrainId = trainB,
				conflictBlock = block,
				predictedConflictTime = predictedConflictTime,
				detectionTime = detectionTime
			)
		logger.warn {
			"Temporal conflict detected: $trainA ↔ $trainB on ${block.name} " +
				"at predicted t=$predictedConflictTime (detected at t=$detectionTime)"
		}
		listeners.toList().forEach { listener ->
			try {
				listener(event)
			} catch (e: Throwable) {
				logger.error(e) { "TemporalConflictEvent listener threw; continuing to remaining listeners" }
			}
		}
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Default lookahead window: 30 simulation seconds.
		 * @since Issue #583 (Goal 9 SP2)
		 */
		const val DEFAULT_LOOKAHEAD_WINDOW_SECONDS = 30.0

		/**
		 * Minimum gap (in simulation seconds) between two warnings for the same
		 * (trainA, trainB, block) triple.  Prevents event storms when the same
		 * future conflict is re-detected on every block-reservation tick.
		 * @since Issue #583 (Goal 9 SP2)
		 */
		internal const val DEDUP_WINDOW_SECONDS = 5.0
	}
}
