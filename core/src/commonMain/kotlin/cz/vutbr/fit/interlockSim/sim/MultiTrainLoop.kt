/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Resource
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.collision.TrainSnapshot
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.platformSleep
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * First slice of Goal 1: Multi-Train Simulation.
 *
 * This interlocking process demonstrates multiple trains running on a shared
 * railway network. Unlike [ShuntingLoop], which caps concurrency to the number
 * of parallel tracks in `vyhybna.xml`, [MultiTrainLoop] accepts deterministic
 * train specifications and uses kDisco's [Resource] primitive during path setup.
 *
 * ## kDisco `Resource` mapping
 *
 * Each physical [DynamicTrackBlock] is represented by a capacity-1 kDisco
 * [Resource]. The resource is used as a **setup-time capacity/FIFO gate**:
 * before reserving a full entry-to-exit path via
 * [PathReservationService.reservePath], the dispatcher atomically checks that
 * every required block resource is available, reserves all of them, calls the
 * ownership service, and immediately releases the resources again.
 *
 * - **kDisco [Resource]** = physical-capacity serialization during dispatcher
 *   setup (ensures the dispatcher never holds some resources while waiting
 *   for others, which would deadlock).
 * - **[PathReservationService]** / **[cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry]** =
 *   ownership registry, switch locking and signal configuration that protects
 *   the blocks for the whole train journey.
 *
 * ## Determinism
 *
 * Train injection times come from caller-supplied specs, so runs are repeatable
 * when paired with a fixed kDisco seed. No standalone [cz.ksimulantenbande.kdisco.Random]
 * generator is required.
 *
 * ## Scope limitations (documented)
 *
 * This slice reserves the **full entry-to-exit path** for each train before
 * activation. That guarantees no intra-slice collisions and avoids touching the
 * existing [Train] process, but it serializes trains that share any block.
 * Future slices can move to incremental reservation following the
 * [ShuntingLoop] semaphore-polling pattern to obtain true block-sharing
 * concurrency.
 *
 * @see Resource
 * @see PathReservationService
 * @see ShuntingLoop
 */
open class MultiTrainLoop(
	context: SimulationContext,
	private val endTime: Long,
	private val trainSpecs: List<TrainSpec> = emptyList(),
	private val enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0,
	private val maxConcurrentTrains: Int = DEFAULT_MAX_CONCURRENT_TRAINS,
	private val pathReservationService: PathReservationService = context.getRoutingServices().getPathReservationService()
) : Interlocking(context),
	SpeedControllable,
	ApprovesTrains {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default concurrency cap for the first slice. */
		internal const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 10

		/** Report types enabled by the multi-train scenario. */
		internal val ENABLED_REPORT_TYPES =
			arrayOf(
				ReportType.TRAIN_APPROVED,
				ReportType.TRAIN_EVENTS,
				ReportType.TRAIN_CONTINUOUS,
				ReportType.NODE_EVENTS
			)
	}

	/**
	 * Specification of a single train to inject into the simulation.
	 *
	 * @property inName name of the entry [DynamicInOut]
	 * @property outName name of the exit [DynamicInOut]
	 * @property inTime simulation time at which the train becomes available
	 * @property length train length in meters
	 */
	data class TrainSpec(
		val inName: String,
		val outName: String,
		val inTime: Double,
		val length: Double = 40.0
	)

	@kotlin.concurrent.Volatile
	override var speedMultiplier: Double = initialSpeedMultiplier
		set(value) {
			require(value > 0.0) { "Speed multiplier must be positive, got: $value" }
			field = value
		}

	init {
		require(initialSpeedMultiplier > 0.0) {
			"Speed multiplier must be positive, got: $initialSpeedMultiplier"
		}
		require(maxConcurrentTrains > 0) {
			"maxConcurrentTrains must be positive, got: $maxConcurrentTrains"
		}
	}

	private val topologyNavigator: TopologyNavigator = context.getRoutingServices().getTopologyNavigator()
	private val blockResources: BlockResourceRegistry = BlockResourceRegistry(context)

	private val pendingSpecs: ArrayDeque<TrainSpec> = ArrayDeque(trainSpecs.sortedBy { it.inTime })
	private val unapprovedTrains: ArrayDeque<Train> = ArrayDeque()

	/**
	 * Trains admitted for dispatch, held as an immutable copy-on-write list (Issue #994).
	 *
	 * **Threading contract:** written only on the kDisco simulation thread, one whole-list
	 * replacement at a time; read from any thread. `@Volatile` publishes each replacement, so a
	 * reader always walks a frozen list and can never observe a half-applied mutation. This is what
	 * makes [getApprovedTrains] and [getTrainSnapshot] safe off the simulation thread — before the
	 * copy-on-write conversion both walked the live mutable list and could raise
	 * `ConcurrentModificationException` (or read a nulled-out slot) out of the collision-detection
	 * path while the simulation admitted or retired a train.
	 *
	 * A lock was deliberately not used: [getTrainSnapshot] can be re-entered on the simulation
	 * thread itself from inside the collision-warning emit path, and the non-reentrant
	 * `kotlinx.atomicfu.locks.SynchronizedObject` available in `commonMain` (see
	 * [DispatcherModeState]) would deadlock there. `Collections.synchronizedList` and
	 * `CopyOnWriteArrayList` are JVM-only and are rejected by the `commonMain` purity gate.
	 */
	@kotlin.concurrent.Volatile
	private var approvedTrains: List<Train> = emptyList()
	private val trainToSpec: MutableMap<Train, TrainSpec> = mutableMapOf()
	private val generator: DeterministicGenerator = DeterministicGenerator(context)

	// Test-observability counters (#365 pattern).
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0

	private inner class RealTimeSynch : LoopProcess() {
		private var beginTime: Long = 0

		override suspend fun startAction() {
			interLoopSleep()
		}

		override suspend fun iteration() {
			val iterationEndTime = currentTimeMillisKMP()
			val targetInterval = (1000.0 / speedMultiplier).toLong()
			val sleepTime: Long = targetInterval - (iterationEndTime - beginTime)
			if (sleepTime > 10) {
				// platformSleep restores the interrupt flag on InterruptedException (JVM only).
				// Simulation termination is handled by LoopProcess.terminate() setting the flag
				// which is checked between iterations.
				platformSleep(sleepTime)
			}
		}

		override suspend fun interLoopSleep() {
			beginTime = currentTimeMillisKMP()
			hold(1.0)
		}
	}

	private inner class DeterministicGenerator(
		context: SimulationEnvironment
	) : Generator(context, shuffleInOuts = false) {
		override suspend fun iteration() {
			if (pendingSpecs.isEmpty()) {
				terminate()
				return
			}
			val spec = pendingSpecs.first()
			val now = time()
			if (now < spec.inTime) {
				return
			}
			pendingSpecs.removeFirst()

			val inOuts = env.getInOuts().toList()
			val inIo =
				requireSimulationNotNull(inOuts.find { it.name == spec.inName }) {
					"No DynamicInOut named '${spec.inName}' in context"
				}
			val outIo =
				requireSimulationNotNull(inOuts.find { it.name == spec.outName }) {
					"No DynamicInOut named '${spec.outName}' in context"
				}
			val timetable =
				Timetable(
					inIo,
					outIo,
					Time(spec.inTime),
					Time(spec.inTime + 1000.0),
					spec.length
				)
			val train = Train(env, timetable)
			// Wire the auto-halt callback so DefaultCollisionDetectionService.autoHaltTrainOnViolation
			// can actually stop this train on a BlockEntryViolation — see Train.requestHalt() KDoc.
			env.getCollisionServices().registerHaltCallback(train.name, train::requestHalt)
			trainToSpec[train] = spec
			logger.debug {
				"MultiTrainLoop: generated ${train.name} (${spec.inName} -> ${spec.outName}) at t=$now"
			}
			placeTrain(train)
			trains.add(train)
		}

		override fun placeTrain(train: Train) {
			unapprovedTrains.addLast(train)
			trainsEnteredCount++
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	override suspend fun startAction() {
		env.addReportTypes(*ENABLED_REPORT_TYPES)
		if (enableRealTimeSync) {
			activate(RealTimeSynch())
		}
		Process.activate(generator)
	}

	override suspend fun iteration() {
		cleanupTerminatedTrains()
		approveTrains()
		if (approvedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approvedTrains.size
		}
		startApprovedTrains()
		blockResources.releaseFreeResources()
		hold(1.0)
	}

	private fun cleanupTerminatedTrains() {
		val terminated = approvedTrains.filter { it.terminated() }
		if (terminated.isEmpty()) {
			return
		}
		// Copy-on-write: publish the survivors as one new list instead of removing in place,
		// so an off-thread reader never walks a list that is being mutated (Issue #994).
		approvedTrains = approvedTrains - terminated.toSet()
		terminated.forEach { train ->
			trainToSpec.remove(train)
			trainsExitedCount++
			logger.debug { "MultiTrainLoop: ${train.name} completed journey" }
		}
	}

	private fun approveTrains() {
		while (approvedTrains.size < maxConcurrentTrains && unapprovedTrains.isNotEmpty()) {
			val train = unapprovedTrains.removeFirst()
			approvedTrains = approvedTrains + train
			logger.debug { "MultiTrainLoop: approved ${train.name} for dispatch" }
		}
	}

	private suspend fun startApprovedTrains() {
		// Iterate the published snapshot: the body suspends (reserveEntryPath, Process.activate),
		// and approvedTrains may be replaced across those suspension points.
		for (train in approvedTrains) {
			if (trainHasActivePath(train)) {
				continue
			}
			val spec = trainToSpec[train] ?: continue
			val reserved = reserveEntryPath(train, spec)
			if (reserved) {
				logger.info { "MultiTrainLoop: starting ${train.name}" }
				Process.activate(train)
			}
		}
	}

	private fun trainHasActivePath(train: Train): Boolean =
		pathReservationService.getReservedBlocks(train.name).isNotEmpty()

	/**
	 * Atomically reserve the physical resources for every block on an
	 * entry-to-exit path, then register the path via [PathReservationService].
	 *
	 * The resource reservations are checked non-blocking (`isAvailable`) and only
	 * acquired when all of them can be. Once acquired, [reservePath] should
	 * succeed because no other dispatcher attempt can hold those resources.
	 * Resources are released immediately afterwards, before the train starts:
	 * the actual journey-time exclusivity is enforced by
	 * [PathReservationService].
	 */
	private suspend fun reserveEntryPath(
		train: Train,
		spec: TrainSpec
	): Boolean {
		val inOuts = env.getInOuts().toList()
		val inIo = inOuts.find { it.name == spec.inName } ?: return false
		val outIo = inOuts.find { it.name == spec.outName } ?: return false

		val candidatePaths = topologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)
		if (candidatePaths.isEmpty()) {
			logger.error { "MultiTrainLoop: no topological path ${spec.inName} -> ${spec.outName} for ${train.name}" }
			return false
		}

		for (path in candidatePaths) {
			val blocks = extractUniqueBlocks(path)
			if (blocks.isEmpty()) {
				continue
			}

			if (!blockResources.areAllAvailable(blocks)) {
				logger.debug {
					"MultiTrainLoop: not all blocks available for ${train.name}, will retry"
				}
				continue
			}

			val acquired = mutableListOf<Resource>()
			try {
				for (block in blocks) {
					blockResources.resourceFor(block).reserve(1)
					acquired.add(blockResources.resourceFor(block))
				}

				when (val result = pathReservationService.reservePath(train.name, inIo, outIo)) {
					is PathReservationService.ReservationResult.Success -> {
						logger.debug {
							"MultiTrainLoop: reserved ${blocks.size} block(s) for ${train.name}"
						}
						return true
					}
					is PathReservationService.ReservationResult.NoPathExists -> {
						logger.warn { "MultiTrainLoop: no path ${spec.inName} -> ${spec.outName} for ${train.name}" }
					}
					is PathReservationService.ReservationResult.AllPathsBlocked -> {
						logger.debug {
							"MultiTrainLoop: all paths blocked for ${train.name}, will retry"
						}
					}
					is PathReservationService.ReservationResult.Conflict -> {
						logger.warn {
							"MultiTrainLoop: conflict for ${train.name} on " +
								"${result.conflictingBlock.name ?: "unnamed"} owned by ${result.existingOwner}"
						}
					}
					is PathReservationService.ReservationResult.NonContiguousStart -> {
						// Issue #893: not expected here -- this call reserves from the train's own
						// entry InOut while it still has no footprint, so the check passes
						// vacuously. Logged at WARN rather than retried silently: retrying an
						// origin the train can never use would spin for the rest of the run.
						logger.warn {
							"MultiTrainLoop: non-contiguous origin for ${train.name}: ${result.reason}"
						}
					}
					is PathReservationService.ReservationResult.GeometricallyImpossible -> {
						// Issue #903: a permanent impossibility (rear-facing START or
						// unconfigurable switch) for this candidate, not ordinary contention.
						// Logged at WARN and the outer loop moves on to the next candidate path.
						logger.warn {
							"MultiTrainLoop: geometrically impossible route for ${train.name}: ${result.reason}"
						}
					}
				}
			} finally {
				for (resource in acquired) {
					releaseIfOccupied(resource)
				}
			}
		}
		return false
	}

	private fun releaseIfOccupied(resource: Resource) {
		if (resource.occupied > 0) {
			resource.release(1)
		}
	}

	private fun extractUniqueBlocks(path: List<TrackSection>): List<DynamicTrackBlock> {
		val seen = mutableSetOf<DynamicTrackBlock>()
		return path.mapNotNull { section ->
			val block = section.getTrackBlock()
			if (block is DynamicTrackBlock && seen.add(block)) block else null
		}
	}

	override suspend fun interLoopSleep() {
		if (time() >= endTime) {
			generator.terminate()
			env.stop()
			return
		}
		hold(1.0)
	}

	/** Test-observability: number of trains that entered the pending queue. */
	fun getTrainsEntered(): Int = trainsEnteredCount

	/** Test-observability: number of trains that completed their journey. */
	fun getTrainsExited(): Int = trainsExitedCount

	/** Test-observability: peak number of concurrently approved trains. */
	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	/** Test-observability: number of block resources currently occupied. */
	fun getOccupiedResourceCount(): Int = blockResources.occupiedCount()

	/**
	 * Snapshot of the currently approved trains.
	 *
	 * Returns the trains that have been dispatched and are currently running (or finishing).
	 * Used by tests that inspect per-train invariants (e.g. animation `trainEntrySeparator`
	 * consistency) during a running simulation, and by the animation and dispatcher perception
	 * ports.
	 *
	 * Safe to call from any thread: the returned list is the immutable copy-on-write snapshot
	 * described on [approvedTrains], so it never changes under the caller. It may already be
	 * stale by the time the caller reads it — the simulation thread publishes a replacement on
	 * every admission and retirement.
	 *
	 * @since PR #633 — animation entrySeparator race regression test
	 * @since Issue #994 — returns the published snapshot instead of copying a live mutable list
	 */
	override fun getApprovedTrains(): List<Train> = approvedTrains

	/**
	 * Return a [TrainSnapshot] for the approved train with the given [trainId], or `null`
	 * if no such train is currently in [approvedTrains].
	 *
	 * Intended for use by [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * to evaluate predictive time-to-collision warnings after each block-reservation event.
	 * Register this method as the snapshot provider via
	 * `collisionDetectionService.registerTrainSnapshotProvider(multiTrainLoop::getTrainSnapshot)`.
	 *
	 * Safe to call from any thread: the lookup walks the immutable copy-on-write snapshot
	 * described on [approvedTrains]. The per-train values it reads (velocity, distance) are
	 * still sampled while the simulation runs, so the snapshot is a point-in-time estimate.
	 *
	 * @param trainId The identifier of the queried train (matches [Train.name]).
	 * @return A [TrainSnapshot] capturing the train's current velocity, position, and length;
	 *   `null` when the train is not (yet) in [approvedTrains].
	 * @since Issue #614 (Goal 3 SP4)
	 * @since Issue #994 — reads the published snapshot instead of the live mutable list
	 */
	fun getTrainSnapshot(trainId: String): TrainSnapshot? =
		approvedTrains.find { it.name == trainId }?.let { train ->
			TrainSnapshot(
				trainId = trainId,
				velocity = train.getVelocity(),
				totalDistance = train.totalDistance,
				length = train.trainLength
			)
		}
}
