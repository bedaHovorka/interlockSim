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
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
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
 * The gate is **inert today**: resources are acquired and released inside one
 * never-suspending dispatcher section, so every availability check sees them free and
 * the gate never refuses an attempt. It is kept on purpose (owner ruling on Issue #1147);
 * see [BlockResourceRegistry] for details.
 *
 * ## Bounded entry reservation (Issue #1148)
 *
 * Each entry attempt makes **at most one** [PathReservationService.reservePath] call: the
 * result of that call does not depend on any dispatcher-side candidate path, because
 * `reservePath` runs its own candidate search. A train gets at most [maxEntryAttempts]
 * attempts (one per dispatcher tick); when they are used up, or when the entry is
 * statically impossible, the train is retired from the approved list and an
 * [EntryFailure] is recorded (see [getEntryFailures]) instead of retrying forever.
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
	private val pathReservationService: PathReservationService = context.getRoutingServices().getPathReservationService(),
	private val maxEntryAttempts: Int = DEFAULT_MAX_ENTRY_ATTEMPTS
) : Interlocking(context),
	SpeedControllable,
	ApprovesTrains {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default concurrency cap for the first slice. */
		internal const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 10

		/**
		 * Default cap on entry-reservation attempts per train (Issue #1148). One attempt is
		 * made per dispatcher cycle (two simulated seconds: the `hold(1.0)` ending [iteration]
		 * plus the one in [interLoopSleep]), so the default lets a train wait two simulated
		 * hours for its entry route — longer than any existing scenario runs.
		 */
		internal const val DEFAULT_MAX_ENTRY_ATTEMPTS: Int = 3600

		/** Search depth for the topological candidates that feed the resource gate. */
		private const val GATE_SEARCH_MAX_DEPTH: Int = 100

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

	/**
	 * Why an entry reservation was given up (Issue #1148).
	 */
	enum class EntryFailureKind {
		/** The spec names an entry or exit [DynamicInOut] that the context does not contain. */
		UNKNOWN_IN_OUT,

		/** No topological path connects the entry and exit, so no attempt can ever succeed. */
		NO_TOPOLOGICAL_PATH,

		/** Every one of the [maxEntryAttempts] attempts failed. */
		ATTEMPTS_EXHAUSTED
	}

	/**
	 * Structured record of a train whose entry reservation was given up (Issue #1148).
	 *
	 * The train is retired from the approved list and never started, so it frees its
	 * concurrency slot instead of being retried forever.
	 *
	 * @property trainName name of the refused train
	 * @property inName name of the entry [DynamicInOut]
	 * @property outName name of the exit [DynamicInOut]
	 * @property kind why the entry was given up
	 * @property attempts number of entry attempts made, the failing one included
	 * @property lastResult result of the last [PathReservationService.reservePath] call, or
	 *   `null` when the failing attempt made no call
	 * @property simTime simulation time of the failing attempt
	 */
	data class EntryFailure(
		val trainName: String,
		val inName: String,
		val outName: String,
		val kind: EntryFailureKind,
		val attempts: Int,
		val lastResult: PathReservationService.ReservationResult?,
		val simTime: Double
	)

	/** Outcome of one bounded entry-reservation attempt. */
	private sealed class EntryAttemptOutcome {
		/** The path is reserved; the train may start. */
		data object Reserved : EntryAttemptOutcome()

		/** This attempt failed; the train is retried on the next tick. */
		data object Retry : EntryAttemptOutcome()

		/** The entry is given up for good. */
		data class Failed(
			val failure: EntryFailure
		) : EntryAttemptOutcome()
	}

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
		require(maxEntryAttempts > 0) {
			"maxEntryAttempts must be positive, got: $maxEntryAttempts"
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

	/** Trains whose entry path was reserved and whose process was activated. */
	private val startedTrains: MutableSet<Train> = mutableSetOf()

	/** Entry attempts made so far per not-yet-started train (Issue #1148). */
	private val entryAttempts: MutableMap<Train, Int> = mutableMapOf()

	/** Resource-gate blocks per `(entry, exit)` name pair; the topology is static. */
	private val gateBlocksByRoute: MutableMap<Pair<String, String>, List<DynamicTrackBlock>> = mutableMapOf()

	private val entryFailures: MutableList<EntryFailure> = mutableListOf()

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
		if (approvedTrains.none { it.terminated() }) {
			return
		}
		val (terminated, survivors) = approvedTrains.partition { it.terminated() }
		// Copy-on-write: publish the survivors as one new list instead of removing in place,
		// so an off-thread reader never walks a list that is being mutated (Issue #994).
		approvedTrains = survivors
		terminated.forEach { train ->
			trainToSpec.remove(train)
			startedTrains.remove(train)
			entryAttempts.remove(train)
			trainsExitedCount++
			logger.debug { "MultiTrainLoop: ${train.name} completed journey" }
		}
	}

	private fun approveTrains() {
		// Copy-on-write: admit the whole batch first, then publish one replacement. Publishing
		// per admission inside the loop would copy the entire list k times and show k
		// intermediate snapshots to off-thread readers (Issue #994).
		val batch: MutableList<Train> = mutableListOf()
		while (batch.size + approvedTrains.size < maxConcurrentTrains && unapprovedTrains.isNotEmpty()) {
			val train = unapprovedTrains.removeFirst()
			batch.add(train)
			logger.debug { "MultiTrainLoop: approved ${train.name} for dispatch" }
		}
		if (batch.isNotEmpty()) {
			approvedTrains = approvedTrains + batch
		}
	}

	private suspend fun startApprovedTrains() {
		// Iterate the published snapshot: the body suspends (reserveEntryPath, Process.activate),
		// and approvedTrains may be replaced across those suspension points.
		for (train in approvedTrains) {
			if (train in startedTrains || trainHasActivePath(train)) {
				continue
			}
			val spec = trainToSpec[train] ?: continue
			when (val outcome = reserveEntryPath(train, spec)) {
				EntryAttemptOutcome.Reserved -> {
					startedTrains.add(train)
					logger.info { "MultiTrainLoop: starting ${train.name}" }
					Process.activate(train)
				}
				EntryAttemptOutcome.Retry -> Unit
				is EntryAttemptOutcome.Failed -> retireRefusedTrain(train, outcome.failure)
			}
		}
	}

	/**
	 * Removes a train whose entry was given up from the approved list, so it frees its
	 * concurrency slot, and records its [EntryFailure]. The train was never activated.
	 */
	private fun retireRefusedTrain(
		train: Train,
		failure: EntryFailure
	) {
		// Copy-on-write, as everywhere else approvedTrains is replaced (Issue #994).
		approvedTrains = approvedTrains.filterNot { it === train }
		trainToSpec.remove(train)
		entryAttempts.remove(train)
		entryFailures.add(failure)
		logger.error {
			"MultiTrainLoop: giving up entry of ${failure.trainName} " +
				"(${failure.inName} -> ${failure.outName}) after ${failure.attempts} attempt(s): " +
				"${failure.kind}, last result ${failure.lastResult}"
		}
	}

	private fun trainHasActivePath(train: Train): Boolean =
		pathReservationService.getReservedBlocks(train.name).isNotEmpty()

	/**
	 * One bounded entry-reservation attempt for [train] (Issue #1148).
	 *
	 * The attempt makes **at most one** [PathReservationService.reservePath] call. Its result
	 * does not depend on any dispatcher-side candidate path, because `reservePath` runs its own
	 * candidate search; calling it once per topological candidate (the pre-#1148 loop) repeated
	 * the identical, expensive search up to several hundred times inside one kDisco event and
	 * froze the simulation clock.
	 *
	 * Before the call the [BlockResourceRegistry] gate is consulted: the capacity-1 resource of
	 * every block any topological candidate may use is checked non-blocking (`isAvailable`) and
	 * acquired only when all of them can be. Resources are released immediately afterwards,
	 * before the train starts; the journey-time exclusivity is enforced by
	 * [PathReservationService]. The gate is inert today (see [BlockResourceRegistry]).
	 *
	 * @return [EntryAttemptOutcome.Reserved] on success; [EntryAttemptOutcome.Retry] when this
	 *   attempt failed and attempts remain; [EntryAttemptOutcome.Failed] when the entry is
	 *   statically impossible or this was attempt number [maxEntryAttempts].
	 */
	private suspend fun reserveEntryPath(
		train: Train,
		spec: TrainSpec
	): EntryAttemptOutcome {
		val attempt = (entryAttempts[train] ?: 0) + 1
		entryAttempts[train] = attempt

		val inOuts = env.getInOuts().toList()
		val inIo = inOuts.find { it.name == spec.inName }
		val outIo = inOuts.find { it.name == spec.outName }
		if (inIo == null || outIo == null) {
			return failedEntry(train, spec, EntryFailureKind.UNKNOWN_IN_OUT, attempt, null)
		}

		val gateBlocks =
			gateBlocksByRoute.getOrPut(spec.inName to spec.outName) {
				topologyNavigator
					.findAllTopologicalPaths(inIo, outIo, maxDepth = GATE_SEARCH_MAX_DEPTH)
					.flatMap { extractUniqueBlocks(it) }
					.distinct()
			}
		if (gateBlocks.isEmpty()) {
			return failedEntry(train, spec, EntryFailureKind.NO_TOPOLOGICAL_PATH, attempt, null)
		}

		val result = reserveThroughGate(train, gateBlocks, inIo, outIo)
		if (result is PathReservationService.ReservationResult.Success) {
			entryAttempts.remove(train)
			logger.debug {
				"MultiTrainLoop: reserved ${result.reservedBlocks.size} block(s) for ${train.name} " +
					"on attempt $attempt"
			}
			return EntryAttemptOutcome.Reserved
		}
		if (attempt >= maxEntryAttempts) {
			return failedEntry(train, spec, EntryFailureKind.ATTEMPTS_EXHAUSTED, attempt, result)
		}
		return EntryAttemptOutcome.Retry
	}

	/**
	 * Acquires the gate resources for [gateBlocks], makes the single
	 * [PathReservationService.reservePath] call of this attempt, and releases the resources.
	 *
	 * @return the `reservePath` result, or `null` when the gate refused the attempt (never
	 *   today, the gate is inert) and no call was made
	 */
	private suspend fun reserveThroughGate(
		train: Train,
		gateBlocks: List<DynamicTrackBlock>,
		inIo: DynamicInOut,
		outIo: DynamicInOut
	): PathReservationService.ReservationResult? {
		if (!blockResources.areAllAvailable(gateBlocks)) {
			logger.debug {
				"MultiTrainLoop: not all blocks available for ${train.name}, will retry"
			}
			return null
		}

		val acquired = mutableListOf<Resource>()
		try {
			for (block in gateBlocks) {
				blockResources.resourceFor(block).reserve(1)
				acquired.add(blockResources.resourceFor(block))
			}
			val result = pathReservationService.reservePath(train.name, inIo, outIo)
			logReservationFailure(train, inIo, outIo, result)
			return result
		} finally {
			for (resource in acquired) {
				releaseIfOccupied(resource)
			}
		}
	}

	private fun logReservationFailure(
		train: Train,
		inIo: DynamicInOut,
		outIo: DynamicInOut,
		result: PathReservationService.ReservationResult
	) {
		when (result) {
			is PathReservationService.ReservationResult.Success -> Unit
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.warn { "MultiTrainLoop: no path ${inIo.name} -> ${outIo.name} for ${train.name}" }
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
				// vacuously. Logged at WARN rather than retried silently.
				logger.warn {
					"MultiTrainLoop: non-contiguous origin for ${train.name}: ${result.reason}"
				}
			}
			is PathReservationService.ReservationResult.GeometricallyImpossible -> {
				// Issue #903: a permanent impossibility (rear-facing START or
				// unconfigurable switch), not ordinary contention. Logged at WARN; the
				// attempt counts toward maxEntryAttempts.
				logger.warn {
					"MultiTrainLoop: geometrically impossible route for ${train.name}: ${result.reason}"
				}
			}
			is PathReservationService.ReservationResult.DivergesFromHeldRoute -> {
				// Issue #1066: not expected here -- the train has no stored route yet, so
				// nothing can diverge. Logged at WARN; the attempt counts toward maxEntryAttempts.
				logger.warn {
					"MultiTrainLoop: route diverges from the held route for ${train.name}: ${result.reason}"
				}
			}
		}
	}

	private fun failedEntry(
		train: Train,
		spec: TrainSpec,
		kind: EntryFailureKind,
		attempts: Int,
		lastResult: PathReservationService.ReservationResult?
	): EntryAttemptOutcome.Failed =
		EntryAttemptOutcome.Failed(
			EntryFailure(
				trainName = train.name,
				inName = spec.inName,
				outName = spec.outName,
				kind = kind,
				attempts = attempts,
				lastResult = lastResult,
				simTime = time()
			)
		)

	private fun releaseIfOccupied(resource: Resource) {
		if (resource.occupied > 0) {
			resource.release(1)
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
	 * Test-observability: every entry reservation given up so far, in order (Issue #1148).
	 * Empty when every generated train obtained its entry path.
	 */
	fun getEntryFailures(): List<EntryFailure> = entryFailures.toList()

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
	 * stale by the time the caller reads it — the simulation thread publishes a replacement
	 * whenever trains are admitted or retired.
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
	 * **Threading (Issue #1028):** in production this runs on the kDisco simulation thread. Its
	 * only production caller is `DefaultCollisionDetectionService.evaluatePredictiveTtc`, reached
	 * from `handleBlockEvent` for [cz.vutbr.fit.interlockSim.sim.events.BlockEvent.BlockReserved]
	 * and `BlockReleased` — block events emitted through kDisco `emitCustom` while a simulation
	 * process runs. There it reads the train's live values between events, consistently.
	 *
	 * Safe to call from any other thread too (only tests do so, e.g.
	 * `ExampleRegistryCollisionWiringTest` and `MultiTrainLoopSnapshotRaceTest`): the lookup walks
	 * the immutable copy-on-write snapshot described on [approvedTrains], and the returned
	 * [TrainSnapshot] is already an immutable value. Its velocity is a continuous value read live.
	 * Its totalDistance adds the traversed-blocks length (a discrete field) to the live integrated
	 * position, so an off-thread caller can catch that pair torn — off by up to one section
	 * length — not only stale: a point-in-time estimate, not a value consistent with any one
	 * simulation instant.
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
