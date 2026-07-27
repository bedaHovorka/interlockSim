/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.ksimulantenbande.kdisco.DiscoException
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.sim.separatorAspect
import cz.vutbr.fit.interlockSim.sim.separatorName
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.cellsOfType

/**
 * Simulation-backed implementation of [NetworkPerceptionPort].
 *
 * Reads the current state of the railway network from the provided
 * [SimulationEnvironment] and a live list of active [Train] objects, and returns
 * immutable snapshots of the observed state.
 *
 * ## Data sources
 *
 * - **Signal aspects** — derived by scanning the [SimulationEnvironment]'s grid for
 *   [DynamicRailSemaphore] cells (same O(cols×rows) scan the animation controller
 *   performs once at startup; `defaultSemaphores` is a cached list built at
 *   construction time).
 * - **Block occupancies** — derived by iterating the graph edges (all
 *   [DynamicTrackBlock] instances) from the simulation environment's graph.
 * - **Train positions and timetables** — derived from the [activeTrains] supplier,
 *   which must provide the list of currently approved, running trains at the moment
 *   of each query.  Trains in the unapproved queue are intentionally excluded
 *   (they have no position/timetable information yet).
 *
 * ## Usage in dispatcher loop
 *
 * The standard wiring pattern (see SP0.4 Issue #543) is:
 * ```kotlin
 * val perceptionPort = DefaultNetworkPerceptionPort(
 *     env = simulationEnvironment,
 *     activeTrains = { approvedTrains }  // lambda captures the live list
 * )
 * ```
 *
 * ## Thread-safety
 *
 * The kDisco simulation kernel runs on its own single thread; both the
 * [SimulationEnvironment] state and the [activeTrains] list are mutated by that
 * thread without synchronisation. [captureSnapshot] and the `allXxx()` /
 * single-query methods read that live state and **must** be called on the kDisco
 * thread.
 *
 * [snapshot] is the exception: it reads only a `@Volatile` reference to the most
 * recent snapshot published by [captureSnapshot], so it is safe to call from any
 * thread (including the SP0.10 drive-loop driver) — it never blocks or interrupts
 * the kDisco kernel and never throws. Before the first [captureSnapshot] it returns
 * [SimulationSnapshot.EMPTY].
 *
 * @param env The simulation environment to read semaphore and block state from.
 * @param activeTrains A supplier that returns the list of currently active (approved
 *   and running) trains.  Called on every query that needs train data.
 *
 * @see NetworkPerceptionPort
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
class DefaultNetworkPerceptionPort(
	private val env: SimulationEnvironment,
	private val activeTrains: () -> List<Train>
) : NetworkPerceptionPort {
	// ── Semaphore cache built once at construction ────────────────────────

	/**
	 * Ordered snapshot of all [DynamicRailSemaphore] cells in the network.
	 *
	 * Built once during construction by scanning the grid — the same O(cols×rows)
	 * scan used by the animation controller.  Semaphores are stable for the lifetime
	 * of a simulation context (no cells are added or removed at runtime).
	 */
	private val semaphoreCache: List<DynamicRailSemaphore> = buildSemaphoreCache()

	/**
	 * Index from semaphore name → [DynamicRailSemaphore] for O(1) name-based lookup.
	 * Built from [semaphoreCache]; unnamed semaphores are excluded from the index.
	 */
	private val semaphoreByName: Map<String, DynamicRailSemaphore> =
		semaphoreCache.filter { it.name.isNotBlank() }.associateBy { it.name }

	// ── Block cache built once at construction ─────────────────────────────

	/**
	 * All [DynamicTrackBlock] edges in the network, read once from the simulation
	 * graph during construction.  The graph edges are stable for the lifetime of a
	 * simulation context — blocks are not added or removed at runtime — so, like
	 * [semaphoreCache], this list is built once and never invalidated.  Live state
	 * (occupancy, train id) is read on cached references at query time in
	 * [DynamicTrackBlock.toReading].
	 *
	 * Note: [blockById] is keyed by [blockId], which falls back to `"unknown"` for
	 * blocks that have neither an explicit name nor named endpoint separators.
	 * Two such blocks would collide on the same id and one would shadow the other
	 * in [blockById].  This is an edge case that the bundled `vyhybna.xml` fixture
	 * does not exercise (all blocks there are named); callers needing unambiguous
	 * lookup should rely on explicitly named blocks.
	 */
	private val blockCache: List<DynamicTrackBlock> =
		env
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.toList()

	/**
	 * Index from [blockId] → [DynamicTrackBlock] for O(1) name-based lookup, built
	 * from [blockCache].  Mirrors [semaphoreByName].
	 */
	private val blockById: Map<String, DynamicTrackBlock> =
		blockCache.associateBy { blockId(it) }

	// ── Block helper ──────────────────────────────────────────────────────

	/**
	 * All dynamic track blocks in the network, served from the construction-time
	 * [blockCache] (see its KDoc for the stability rationale).
	 */
	private fun allBlocks(): Collection<DynamicTrackBlock> = blockCache

	/**
	 * Derives a stable, human-readable identifier for a [DynamicTrackBlock].
	 *
	 * Prefers the block's explicit name (set in XML or by [ShuntingLoop]).
	 * Falls back to a `"sep1-sep2"` label built from the sorted names of the
	 * block's two endpoint separators.  Returns `"unknown"` only when no name
	 * is available from any source.
	 */
	private fun blockId(block: DynamicTrackBlock): String = BlockIdentity.stableBlockId(block)

	// ── Train helper ──────────────────────────────────────────────────────

	/**
	 * Name of the track section containing the train's front, or `null` if the
	 * train's front has not yet entered a section.  Uses the block's name field with
	 * the same fallback label logic as [blockId].
	 */
	private fun frontSectionName(train: Train): String? {
		val section = train.frontSection ?: return null
		val block = section.getTrackBlock() as? DynamicTrackBlock ?: return null
		return blockId(block)
	}

	// ── NetworkPerceptionPort — signal aspects ────────────────────────────

	override fun signalAspect(semaphoreName: String): SemaphoreReading? {
		val sem = semaphoreByName[semaphoreName] ?: return null
		return sem.toReading()
	}

	override fun allSignalAspects(): List<SemaphoreReading> = semaphoreCache.map { sem -> sem.toReading() }

	/**
	 * Converts a [DynamicRailSemaphore] to an immutable [SemaphoreReading] snapshot.
	 *
	 * Populates [SemaphoreReading.authorizedFrom] and [SemaphoreReading.authorizedTo] with the
	 * canonical forward-direction segment names when the signal is allowing, so consumers (in
	 * particular the LLM dispatcher's `all_signal_aspects` tool) can distinguish a proceed aspect
	 * that is authorized for the forward direction from one that would be misleading for the
	 * opposite direction.
	 *
	 * Uses null-safe direction lookup: if [DynamicRailSemaphore.direction] returns `null`
	 * (e.g. for relaxed mocks in unit tests), the direction fields are omitted (`null`).
	 *
	 * @since Issue #812 (direction-aware signal display)
	 */
	private fun DynamicRailSemaphore.toReading(): SemaphoreReading {
		val dir = if (signal.isAllowing()) direction() else null
		return SemaphoreReading(
			name = name,
			signal = signal,
			authorizedFrom = dir?.let { anti(it).name },
			authorizedTo = dir?.name
		)
	}

	// ── NetworkPerceptionPort — block occupancies ─────────────────────────

	override fun blockOccupancy(blockId: String): BlockOccupancyReading? {
		val block = blockById[blockId] ?: return null
		return block.toReading()
	}

	override fun allBlockOccupancies(): List<BlockOccupancyReading> = allBlocks().map { it.toReading() }

	private fun DynamicTrackBlock.toReading(): BlockOccupancyReading =
		BlockOccupancyReading(
			blockId = blockId(this),
			state = getState(),
			trainId =
				when (getState()) {
					TrackFacility.State.FREE -> null
					TrackFacility.State.RESERVED -> trainName
					TrackFacility.State.OCCUPIED -> occupant?.name ?: trainName
				}
		)

	// ── NetworkPerceptionPort — train positions ───────────────────────────

	override fun trainPosition(trainId: String): TrainPositionReading? {
		val train = activeTrains().firstOrNull { it.name == trainId } ?: return null
		return train.toPositionReading()
	}

	override fun allTrainPositions(): List<TrainPositionReading> = activeTrains().map { it.toPositionReading() }

	private fun Train.toPositionReading(): TrainPositionReading =
		TrainPositionReading(
			trainId = name,
			velocity = getVelocity(),
			acceleration = getAcceleration(),
			totalDistance = totalDistance,
			frontSectionName = frontSectionName(this)
		)

	// ── NetworkPerceptionPort — train timetables ──────────────────────────

	override fun trainTimetable(trainId: String): TimetableReading? {
		val train = activeTrains().firstOrNull { it.name == trainId } ?: return null
		return train.toTimetableReading()
	}

	override fun allTrainTimetables(): List<TimetableReading> = activeTrains().map { it.toTimetableReading() }

	private fun Train.toTimetableReading(): TimetableReading =
		TimetableReading(
			trainId = name,
			originInOutName = timetableOriginName,
			destinationInOutName = timetableDestinationName,
			scheduledDepartureTime = scheduledDepartureTime,
			scheduledArrivalTime = scheduledArrivalTime
		)

	// ── NetworkPerceptionPort — train perception (SP2a.1) ─────────────────

	override fun trainPerception(trainId: String): TrainPerceptionReading? {
		val train = activeTrains().firstOrNull { it.name == trainId } ?: return null
		return train.toPerceptionReading()
	}

	override fun allTrainPerceptions(): List<TrainPerceptionReading> = activeTrains().map { it.toPerceptionReading() }

	private fun Train.toPerceptionReading(): TrainPerceptionReading {
		// Read the immediate next semaphore once (M1) and derive the second signal from it, so
		// the shared separatorName/separatorAspect mapping runs on at most one nextSemaphore()
		// call per capture instead of once per facet.
		val firstSep = nextSemaphore()
		val secondSep = secondSemaphoreAhead(firstSep)
		return TrainPerceptionReading(
			trainId = name,
			signalAheadName = separatorName(firstSep),
			signalAheadAspect = separatorAspect(firstSep),
			distanceToSignalAheadMetres = distanceToSemaphore(),
			currentSpeedLimitMps = currentSpeedLimitMps,
			velocity = getVelocity(),
			acceleration = getAcceleration(),
			totalDistance = totalDistance,
			frontSectionName = frontSectionName(this),
			destinationInOutName = timetableDestinationName,
			scheduledArrivalTime = scheduledArrivalTime,
			isDwelling = isDwelling,
			nextSignalAheadName = separatorName(secondSep),
			nextSignalAheadAspect = separatorAspect(secondSep),
			isStationDwelling = isStationDwelling
		)
	}

	// ── Full snapshot ─────────────────────────────────────────────────────

	/**
	 * The most recent snapshot published by [captureSnapshot], or `null` before the
	 * first on-thread capture.
	 *
	 * `@Volatile` so an off-thread [snapshot] caller (the SP0.10 drive-loop driver)
	 * observes the latest published reference without locking; the published
	 * [SimulationSnapshot] is itself immutable, so once a reference is seen it is safe
	 * to read concurrently. Written only from [captureSnapshot] on the kDisco thread.
	 */
	@kotlin.concurrent.Volatile
	private var latestCaptured: SimulationSnapshot? = null

	/**
	 * Returns the most recent snapshot captured on the kDisco thread, safe to call
	 * from any thread (see [NetworkPerceptionPort.snapshot]).
	 *
	 * This is the **off-thread-safe** accessor: it reads only [latestCaptured] and
	 * never touches live simulation state, so it cannot block or interrupt the kDisco
	 * kernel and cannot throw. Before the first [captureSnapshot] it returns
	 * [SimulationSnapshot.EMPTY].
	 */
	override fun snapshot(): SimulationSnapshot = latestCaptured ?: SimulationSnapshot.EMPTY

	/**
	 * Captures a fresh, consistent [SimulationSnapshot] of the complete observable
	 * network state on the kDisco thread and publishes it to [latestCaptured].
	 *
	 * Calls each `allXxx()` bulk query in sequence.  Because kDisco runs on a single
	 * simulation thread, all four calls observe the same simulation state — no events
	 * interleave between them during a normal tick. The result is published via
	 * [latestCaptured] so off-thread [snapshot] callers see a consistent picture.
	 *
	 * [SimulationSnapshot.simTime] is set from [Process.time]; if called outside an
	 * active kDisco simulation (e.g. in unit tests), the resulting [DiscoException]
	 * is caught and `simTime` falls back to `0.0`. Any other exception propagates.
	 */
	override fun captureSnapshot(): SimulationSnapshot {
		val captured =
			SimulationSnapshot(
				simTime =
					try {
						Process.time()
					} catch (_: DiscoException) {
						0.0
					},
				semaphores = allSignalAspects(),
				blocks = allBlockOccupancies(),
				trainPositions = allTrainPositions(),
				timetables = allTrainTimetables(),
				trainPerceptions = allTrainPerceptions()
			)
		latestCaptured = captured
		return captured
	}

	// ── Grid scan ─────────────────────────────────────────────────────────

	/**
	 * Scans the grid once at construction time to build the semaphore list.
	 * Matches the strategy used by the animation controller.
	 */
	private fun buildSemaphoreCache(): List<DynamicRailSemaphore> = env.getRailWayNetGrid().cellsOfType()
}
