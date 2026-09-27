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

/**
 * Immutable snapshot of all observable simulation state at a single control step.
 *
 * This is the **"sense"** value type for the agent control loop (SP0.4, Issue #543):
 * a dispatcher (or any other agent) calls [NetworkPerceptionPort.snapshot] at the
 * start of a tick to obtain a consistent, frozen picture of the network before
 * deciding what to do.  Because every field is an immutable `data class` or a
 * primitive, the snapshot is safe to pass across thread boundaries and to hold
 * after the tick ends.
 *
 * ## Contents
 *
 * | Field | Source | Agent use |
 * |-------|--------|-----------|
 * | [simTime]       | kDisco `Process.time()` | Timestamp this snapshot was taken |
 * | [semaphores]    | [NetworkPerceptionPort.allSignalAspects] | Current signal aspects for every semaphore |
 * | [blocks]        | [NetworkPerceptionPort.allBlockOccupancies] | Current state of every track block |
 * | [trainPositions]| [NetworkPerceptionPort.allTrainPositions] | Position and velocity of every active train |
 * | [timetables]    | [NetworkPerceptionPort.allTrainTimetables] | Route and schedule of every active train |
 * | [trainPerceptions] | [NetworkPerceptionPort.allTrainPerceptions] | First-person perception of every active train |
 *
 * ## Design note
 *
 * The snapshot is produced by [NetworkPerceptionPort.snapshot] by calling each
 * `allXxx()` bulk query in sequence.  Because kDisco runs on a single dispatcher
 * thread, the snapshot reflects a consistent point in time (no interleaved
 * simulation events between the individual query calls).  Callers outside the
 * kDisco thread — or code that is called after `hold()` resumes — must be aware
 * that the snapshot may be stale by the time it is consumed; it is a record of
 * the instant it was taken, not a live view.
 *
 * ## Relation to live queries
 *
 * For one-off queries (e.g. "is block k1 free right now?") prefer the individual
 * methods on [NetworkPerceptionPort] directly.  Use [snapshot] when you need the
 * complete picture at one moment — for example, to hand it to an LLM dispatcher or
 * to log the full network state for debugging.
 *
 * @property simTime     Simulation time (seconds) at which this snapshot was taken.
 *   `0.0` when called outside an active kDisco simulation.
 * @property semaphores  Signal aspects of every semaphore in the network.  Order is
 *   deterministic within a simulation run; the list is never `null` but may be empty
 *   for a network with no semaphores.
 * @property blocks      Occupancy state of every track block in the network.  Mirrors
 *   [NetworkPerceptionPort.allBlockOccupancies].
 * @property trainPositions Position and motion of every currently active (approved)
 *   train.  Trains in the unapproved queue are absent.
 * @property timetables  Timetable of every currently active (approved) train.
 * @property trainPerceptions  First-person perception reading of every currently active
 *   train (SP2a.1 Issue #552). Includes signal ahead, speed limit, kinematics, timetable
 *   event, and dwell state. Defaults to [emptyList] for backwards compatibility with
 *   snapshots captured before SP2a.1.
 *
 * @since Issue #543 (SP0.4 — Goal 10 observable simulation state)
 */
data class SimulationSnapshot(
	val simTime: Double,
	val semaphores: List<SemaphoreReading>,
	val blocks: List<BlockOccupancyReading>,
	val trainPositions: List<TrainPositionReading>,
	val timetables: List<TimetableReading>,
	val trainPerceptions: List<TrainPerceptionReading> = emptyList()
) {
	// ── Map-backed lookups (#967) ──────────────────────────────────────────
	//
	// These lazy maps are deliberately declared in the class body, not as primary-constructor
	// properties: only primary-constructor properties participate in a data class's generated
	// equals()/hashCode()/toString()/copy()/componentN(), so keeping the maps here means they
	// never affect structural equality or the string form of a snapshot, and are never touched
	// by copy(). They are built once per snapshot instance, on first lookup, and reused by every
	// subsequent lookup against that same instance.
	//
	// associateBy() keeps the LAST entry on a duplicate key; the bulk queries this snapshot is
	// built from (NetworkPerceptionPort.allXxx()) are documented to return one reading per
	// semaphore/block/train, so duplicate names are not expected in practice. To preserve the
	// old firstOrNull-scan semantics exactly regardless of that assumption, the source list is
	// reversed before associating: the first occurrence then ends up last in iteration order and
	// wins the overwrite.

	private val semaphoreByName: Map<String, SemaphoreReading> by lazy {
		semaphores.asReversed().associateBy { it.name }
	}

	private val blockByBlockId: Map<String, BlockOccupancyReading> by lazy {
		blocks.asReversed().associateBy { it.blockId }
	}

	private val trainPositionByTrainId: Map<String, TrainPositionReading> by lazy {
		trainPositions.asReversed().associateBy { it.trainId }
	}

	private val timetableByTrainId: Map<String, TimetableReading> by lazy {
		timetables.asReversed().associateBy { it.trainId }
	}

	private val trainPerceptionByTrainId: Map<String, TrainPerceptionReading> by lazy {
		trainPerceptions.asReversed().associateBy { it.trainId }
	}

	/**
	 * Returns the [SemaphoreReading] whose [SemaphoreReading.name] matches [semaphoreName],
	 * or `null` if this snapshot contains no such semaphore. O(1) after the first call.
	 */
	fun signalAspect(semaphoreName: String): SemaphoreReading? = semaphoreByName[semaphoreName]

	/**
	 * Returns the [BlockOccupancyReading] whose [BlockOccupancyReading.blockId] matches
	 * [blockId], or `null` if this snapshot contains no such block. O(1) after the first call.
	 */
	fun blockOccupancy(blockId: String): BlockOccupancyReading? = blockByBlockId[blockId]

	/**
	 * Returns the [TrainPositionReading] whose [TrainPositionReading.trainId] matches [trainId],
	 * or `null` if no such active train appears in this snapshot. O(1) after the first call.
	 */
	fun trainPosition(trainId: String): TrainPositionReading? = trainPositionByTrainId[trainId]

	/**
	 * Returns the [TimetableReading] whose [TimetableReading.trainId] matches [trainId], or
	 * `null` if no such active train appears in this snapshot. O(1) after the first call.
	 */
	fun trainTimetable(trainId: String): TimetableReading? = timetableByTrainId[trainId]

	/**
	 * Returns the [TrainPerceptionReading] whose [TrainPerceptionReading.trainId] matches
	 * [trainId], or `null` if no such active train appears in this snapshot. O(1) after the
	 * first call.
	 */
	fun trainPerception(trainId: String): TrainPerceptionReading? = trainPerceptionByTrainId[trainId]

	companion object {
		/**
		 * Empty snapshot returned by [NetworkPerceptionPort.snapshot] before the first
		 * on-thread [NetworkPerceptionPort.captureSnapshot] has published a real one.
		 *
		 * Safe to hand to a dispatcher from any thread: it carries no live state and
		 * carries no active trains or signal changes, so a well-behaved dispatcher
		 * responds with a no-op decision.
		 */
		val EMPTY: SimulationSnapshot =
			SimulationSnapshot(
				simTime = 0.0,
				semaphores = emptyList(),
				blocks = emptyList(),
				trainPositions = emptyList(),
				timetables = emptyList()
			)
	}
}
