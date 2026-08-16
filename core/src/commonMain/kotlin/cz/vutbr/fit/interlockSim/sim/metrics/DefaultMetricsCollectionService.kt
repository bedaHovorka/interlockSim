/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [MetricsCollectionService].
 *
 * Subscribes to [SimulationEnvironment.onBlockEvent] in its [init] block and derives
 * all KPIs from the domain-level [BlockEvent]s without reaching into kDisco internals.
 *
 * ## KPI Derivation
 *
 * | KPI | Source events |
 * |-----|---------------|
 * | Conflicts | [BlockEvent.ReservationConflictDetected] — each event is one conflict |
 * | Delay | [BlockEvent.BlockReserved] sets a timer; [BlockEvent.OccupancySet] closes it |
 * | Throughput | [BlockEvent.BlockReleased] when a train's reservation count → 0, **and** the train has had at least one [BlockEvent.OccupancySet] (physically moved) |
 * | Utilization | [BlockEvent.OccupancySet] / [BlockEvent.OccupancyCleared] vs total blocks |
 *
 * ## Listener contract
 *
 * [onSnapshot] listeners are called after every state-modifying [BlockEvent].
 * Exceptions thrown by a listener are caught and logged; remaining listeners
 * and the service's own state update are unaffected.
 *
 * ## Logging
 *
 * The service logs a summary at INFO level whenever a train completes its
 * journey ([completedTrains] increments) and at DEBUG level for every other
 * state change.  The current snapshot is always visible in logs, satisfying the
 * Goal 10 requirement that metrics are observable in stdout/log without a GUI.
 *
 * ## Thread safety
 *
 * This class is **not thread-safe**. It is intended to be driven solely by the
 * kDisco event stream — i.e. the single-threaded `emitCustom` callbacks that
 * fire inside `runBlocking { context.run() }`. [getSnapshot] is meant to be
 * polled after `context.run()` completes, not concurrently from another thread
 * while events are being processed.
 *
 * ## Constructor
 *
 * @param env The simulation environment used to subscribe to block events.
 *   Pass `null` only in unit tests that drive [handleBlockEvent] directly.
 *
 * @since Issue #672 (Goal 6 SP1)
 */
class DefaultMetricsCollectionService(
	env: SimulationEnvironment? = null
) : MetricsCollectionService {
	// ── KPI accumulators ─────────────────────────────────────────────────────

	/** Total reservation conflicts detected since start. */
	private var conflictCount: Int = 0

	/** Trains that have fully released all their reserved blocks. */
	private var completedTrains: Int = 0

	/** Cumulative block-reservation wait time (seconds). */
	private var totalWaitSeconds: Double = 0.0

	/** Number of occupancy events (BlockReserved → OccupancySet pairs). */
	private var occupancyEventCount: Int = 0

	/** Blocks currently physically occupied (OccupancySet − OccupancyCleared). */
	private val currentlyOccupied: MutableSet<DynamicTrackBlock> = mutableSetOf()

	/** Simulation time of the most-recently processed event. */
	private var lastEventTime: Double = 0.0

	// ── Per-train / per-block bookkeeping ────────────────────────────────────

	/**
	 * Simulation time at which each block was last reserved, keyed by block.
	 * Used to compute reservation wait time when [BlockEvent.OccupancySet] arrives.
	 */
	private val blockReservedAt: MutableMap<DynamicTrackBlock, Double> = mutableMapOf()

	/**
	 * Number of currently-active reservations per train ID.
	 * Incremented by [BlockEvent.BlockReserved], decremented by [BlockEvent.BlockReleased].
	 * When a train's count drops to zero (and it was previously non-zero), the train
	 * has completed its journey.
	 */
	private val activeReservationCount: MutableMap<String, Int> = mutableMapOf()

	/**
	 * Train IDs that have had at least one [BlockEvent.BlockReserved] event.
	 * Used to distinguish "train completed" (count → 0 after being >0) from
	 * "train never started" (count stays 0).
	 *
	 * Entries are removed when a train is counted as completed (reservation count
	 * drops to zero while the train has physically moved), so the set is bounded
	 * by the number of trains that are active-but-not-yet-finished at any moment.
	 */
	private val knownTrains: MutableSet<String> = mutableSetOf()

	/**
	 * Train IDs that have had at least one [BlockEvent.OccupancySet] event,
	 * i.e. trains that have physically entered at least one block.
	 *
	 * A train whose reserved blocks are reclaimed by the [OrphanReservationSweeper]
	 * without ever moving will never appear here, so it will not be credited as a
	 * completed journey in [onBlockReleased].
	 *
	 * Entries are removed when a train is counted as completed, resetting the
	 * "has moved" flag for any subsequent journey the same train-ID makes.
	 */
	private val movedTrains: MutableSet<String> = mutableSetOf()

	// ── Total block count ─────────────────────────────────────────────────────

	/**
	 * Total track blocks in the railway network, fetched lazily from [env] graph.
	 * Zero until the first call to [getSnapshot] (or until the first event if [env]
	 * is non-null and graph is accessible).
	 */
	private val totalBlocks: Int by lazy {
		env?.getGraph()?.size() ?: 0
	}

	// ── Listeners ────────────────────────────────────────────────────────────

	private val listeners: MutableList<(MetricsSnapshot) -> Unit> = mutableListOf()

	// ── Subscription ─────────────────────────────────────────────────────────

	init {
		env?.onBlockEvent { event -> handleBlockEvent(event) }
	}

	// ── Interface implementation ──────────────────────────────────────────────

	override fun getSnapshot(): MetricsSnapshot {
		val t = lastEventTime
		val completed = completedTrains
		val throughput = if (t > 0.0) completed / t else 0.0
		val occupied = currentlyOccupied.size
		val total = totalBlocks
		val utilization = if (total > 0) occupied.toDouble() / total else 0.0
		val avgWait = if (occupancyEventCount > 0) totalWaitSeconds / occupancyEventCount else 0.0

		val snapshot =
			MetricsSnapshot(
				time = t,
				conflictCount = conflictCount,
				completedTrains = completed,
				throughput = throughput,
				totalWaitSeconds = totalWaitSeconds,
				averageWaitSeconds = avgWait,
				occupiedBlocks = occupied,
				totalBlocks = total,
				utilization = utilization,
				trainsHoldingReservations = activeReservationCount.size,
				heldBlocks = blockReservedAt.size
			)
		logger.debug { "Metrics snapshot queried: $snapshot" }
		return snapshot
	}

	override fun reportUnreleasedReservations(): Set<String> {
		val leaked = activeReservationCount.keys.toSet()
		if (leaked.isNotEmpty()) {
			logger.warn {
				"Unreleased block reservations at run end: ${leaked.size} train(s) still hold " +
					"$blockReservedAtSize reservation(s) — ${leaked.sorted().joinToString(", ")}. " +
					"Their journeys were never credited and those blocks were never returned to the " +
					"network (see Issue #936)."
			}
		}
		return leaked
	}

	/** Outstanding reservation count, named so the WARN above stays inside the line-length limit. */
	private val blockReservedAtSize: Int get() = blockReservedAt.size

	override fun onSnapshot(listener: (MetricsSnapshot) -> Unit) {
		listeners += listener
	}

	override fun removeSnapshotListener(listener: (MetricsSnapshot) -> Unit) {
		listeners -= listener
	}

	// ── Internal event handler (internal for test access) ────────────────────

	/**
	 * Process a [BlockEvent] and update the running KPI counters.
	 *
	 * Exposed as `internal` so that unit tests can drive it directly with
	 * synthetic events without needing a full simulation context, mirroring
	 * the [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * precedent.
	 */
	internal fun handleBlockEvent(event: BlockEvent) {
		lastEventTime = event.time
		when (event) {
			is BlockEvent.BlockReserved -> onBlockReserved(event)
			is BlockEvent.BlockReleased -> onBlockReleased(event)
			is BlockEvent.OccupancySet -> onOccupancySet(event)
			is BlockEvent.OccupancyCleared -> onOccupancyCleared(event)
			is BlockEvent.ReservationConflictDetected -> onReservationConflict(event)
		}
		notifyListeners()
	}

	// ── Private event handlers ────────────────────────────────────────────────

	private fun onBlockReserved(event: BlockEvent.BlockReserved) {
		knownTrains += event.trainId
		activeReservationCount[event.trainId] = (activeReservationCount[event.trainId] ?: 0) + 1
		blockReservedAt[event.block] = event.time
		logger.debug { "Block reserved: trainId=${event.trainId} block=${event.block} t=${event.time}" }
	}

	private fun onBlockReleased(event: BlockEvent.BlockReleased) {
		val newCount = (activeReservationCount[event.trainId] ?: 1) - 1
		if (newCount <= 0) {
			activeReservationCount.remove(event.trainId)
			// Only credit a completed journey when the train is known (reserved at
			// least one block) AND has physically moved (entered at least one block).
			// This prevents routes reclaimed by the OrphanReservationSweeper — or any
			// other release that fires before the train occupies a block — from being
			// mis-credited as finished journeys.
			if (event.trainId in knownTrains && event.trainId in movedTrains) {
				completedTrains++
				logger.info {
					"Train completed journey: trainId=${event.trainId} t=${event.time} " +
						"totalCompleted=$completedTrains — ${buildSummary()}"
				}
				// Prune both sets so the same event cannot be double-counted on a
				// subsequent orphan-release for the same train ID, and so the sets
				// remain bounded by the number of active-but-unfinished trains.
				knownTrains -= event.trainId
				movedTrains -= event.trainId
			} else if (event.trainId in knownTrains) {
				// The reservation count reached zero but the train never physically
				// moved — stale route reclaimed by sweeper or similar.  Prune the
				// known-trains entry so a future journey for the same ID starts clean.
				knownTrains -= event.trainId
				logger.debug {
					"Orphan route released for non-moving train: trainId=${event.trainId} " +
						"t=${event.time} — not counted as completed journey"
				}
			}
		} else {
			activeReservationCount[event.trainId] = newCount
		}
		blockReservedAt.remove(event.block)
	}

	private fun onOccupancySet(event: BlockEvent.OccupancySet) {
		currentlyOccupied += event.block
		// event.occupant.name is Train.name — the same string used as trainId in
		// BlockReserved / BlockReleased events for the same physical train.
		// Registering it here as a "moved" signal prevents counting orphan routes
		// (reclaimed by OrphanReservationSweeper) that never had OccupancySet events.
		movedTrains += event.occupant.name
		val reservedAt = blockReservedAt[event.block]
		if (reservedAt != null) {
			val wait = (event.time - reservedAt).coerceAtLeast(0.0)
			totalWaitSeconds += wait
			occupancyEventCount++
			logger.debug { "Block occupied: wait=${wait}s t=${event.time}" }
		}
	}

	private fun onOccupancyCleared(event: BlockEvent.OccupancyCleared) {
		currentlyOccupied -= event.block
	}

	private fun onReservationConflict(event: BlockEvent.ReservationConflictDetected) {
		conflictCount++
		logger.warn {
			"Reservation conflict recorded: block=${event.block} " +
				"trainId=${event.trainId} conflictingTrain=${event.conflictingTrainId} " +
				"t=${event.time} totalConflicts=$conflictCount"
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun buildSummary(): String {
		val t = lastEventTime
		val throughput = if (t > 0.0) completedTrains / t else 0.0
		val avgWait = if (occupancyEventCount > 0) totalWaitSeconds / occupancyEventCount else 0.0
		return "conflicts=$conflictCount throughput=${roundTo4dp(throughput)}/s " +
			"avgWait=${roundTo2dp(avgWait)}s " +
			"occupied=${currentlyOccupied.size}/$totalBlocks"
	}

	private fun roundTo4dp(value: Double): String {
		val scaled = kotlin.math.round(value * 10000)
		val whole = scaled / 10000
		val frac = (scaled % 10000).toString().padStart(4, '0')
		return "$whole.$frac"
	}

	private fun roundTo2dp(value: Double): String {
		val scaled = kotlin.math.round(value * 100)
		val whole = scaled / 100
		val frac = (scaled % 100).toString().padStart(2, '0')
		return "$whole.$frac"
	}

	private fun notifyListeners() {
		if (listeners.isEmpty()) return
		val snapshot = getSnapshot()
		listeners.toList().forEach { listener ->
			try {
				listener(snapshot)
			} catch (e: Throwable) {
				logger.error(e) { "MetricsCollectionService snapshot listener threw; continuing to next listener" }
			}
		}
	}

	private companion object {
		private val logger = KotlinLogging.logger {}
	}
}
