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
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onSwitchClaimsReleased
import cz.vutbr.fit.interlockSim.exceptions.SimulationStalled
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
 * railway network. Unlike [ShuntingLoop], which caps concurrency at
 * [ShuntingLoop.stationCapacity] (derived from the topology, Issue #1162), [MultiTrainLoop]
 * accepts deterministic train specifications and uses kDisco's [Resource] primitive during
 * path setup.
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
 * attempts; when they are used up, or when the entry is permanently impossible (no route, a
 * geometrically impossible route, an unknown InOut name), the train is retired from the approved
 * list (or never created) and an [EntryFailure] is recorded (see [getEntryFailures]) instead of
 * retrying forever.
 *
 * ## Event-driven retry (Issue #1154)
 *
 * The first attempt is made on the dispatcher tick that first sees the train. A train refused on
 * a retryable result waits in a FIFO queue and is **not** polled: it is attempted again only when
 * a reservation is released -- a [BlockEvent.BlockReleased], a
 * [cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired], a train-less
 * [BlockEvent.OccupancyCleared], or the internal
 * [cz.vutbr.fit.interlockSim.context.navigation.SwitchClaimsReleased] a teardown emits. Waiters are
 * attempted before the fresh trains of the same tick: every release feeds one admission policy --
 * the pending wake drains synchronously at the top of the tick, before [startApprovedTrains].
 * Every release wakes the queue; no relevance model sits between a release and the wake, and
 * releases that queue before the wake are coalesced (a release arriving after the wake
 * re-wakes the retry at the same instant, counted once per instant). A release caused by an
 * attempt's own rollback is ignored for the events that carry a train id, otherwise a refused
 * attempt would wake itself; the train-less [BlockEvent.OccupancyCleared] is not dropped by that
 * filter.
 *
 * Consequently an attempt is no longer "one per dispatcher tick": [maxEntryAttempts] counts the
 * first attempt plus the retries that releases trigger. A blocked train that no release ever
 * wakes (no release at all, or releases of blocks outside its candidate routes) stays queued
 * until the run ends: it is not retried, retired, or recorded as an entry failure, and its
 * concurrency slot stays held; [reportRunEndResidue] names such trains in the run-end log. That
 * case is deliberately *not* the deadlock watchdog's.
 *
 * ## Deadlock watchdog (Issue #1154)
 *
 * On each tick the loop builds a wait-for graph and aborts the run with [SimulationStalled] when
 * a deadlock persists while the clock advances. A waiting train points at the trains that
 * refused its last attempt (Issue #1269): the current owner
 * ([PathReservationService.getBlockOwner]) of each block its last
 * [PathReservationService.ReservationResult.AllPathsBlocked] named, and the holder of each switch
 * claim it named. Holders need not be waiting, or even be trains of this loop: a started train
 * that keeps a waiter out is an edge. The blockers are kept per refused candidate route, and a
 * deadlock is a knot (Issue #1270): a set of waiters in which every route of every member is
 * blocked by a member. A waiter needs only one free route, so one route blocked by nobody, or only
 * by trains outside the set, rules it out. The persistence rule, on the cycle the knot contains:
 * the same cycle for [stallPersistTicks] ticks, or any cycle for ten times [stallPersistTicks] ticks
 * (the churn bound). A frozen clock never reaches a tick, so it is bounded by the route-search node
 * budget instead (Issue #1151).
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
	/**
	 * Whether [startAction] activates [RealTimeSynch], pacing simulated time to the wall clock (GUI
	 * examples pass `true`; headless runs keep the default `false`). Read-only, so a caller can
	 * verify an example's real-time wiring, as on [ShuntingLoop.enableRealTimeSync] (Issue #1297).
	 */
	val enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0,
	private val maxConcurrentTrains: Int = DEFAULT_MAX_CONCURRENT_TRAINS,
	private val pathReservationService: PathReservationService = context.getRoutingServices().getPathReservationService(),
	private val maxEntryAttempts: Int = DEFAULT_MAX_ENTRY_ATTEMPTS,
	private val stallPersistTicks: Int = DEFAULT_STALL_PERSIST_TICKS
) : Interlocking(context),
	SpeedControllable,
	ApprovesTrains {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default concurrency cap for the first slice. */
		internal const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 10

		/**
		 * Default cap on entry-reservation attempts per train (Issue #1148): the first attempt plus
		 * the retries that releases trigger (Issue #1154; before it, one attempt per dispatcher
		 * cycle). A release frees at most a handful of blocks, so 3600 attempts outlast any
		 * existing scenario.
		 */
		internal const val DEFAULT_MAX_ENTRY_ATTEMPTS: Int = 3600

		/**
		 * Default number of successive dispatcher ticks (two simulated seconds each) a deadlock must
		 * persist before the watchdog aborts the run (Issue #1154): the second consecutive sighting
		 * fires, two simulated seconds after the first (ticks are two seconds apart). It was five ticks
		 * while the watchdog read an AND graph, whose cycles were not always deadlocks; the knot
		 * search made the check exact for OR waits (Issue #1270), so the guard covers only transient
		 * state: a switch holder taken from a refusal that the retry, woken at the same instant,
		 * has not replaced yet. Two ticks at strictly later clock values outlast that. The churn
		 * bound (`persistTicks * 10`) drops from 50 to 20 ticks with this default; that is harmless
		 * because knots are exact now.
		 */
		internal const val DEFAULT_STALL_PERSIST_TICKS: Int = 2

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
		/**
		 * The spec names an entry or exit [DynamicInOut] that the context does not contain. No
		 * [Train] is created for such a spec, so the failure has no train name and no attempts.
		 */
		UNKNOWN_IN_OUT,

		/**
		 * No route connects the entry and exit: the route search found none, or `reservePath`
		 * answered [PathReservationService.ReservationResult.NoPathExists]. Permanent, so it is
		 * not retried.
		 */
		NO_ROUTE,

		/**
		 * `reservePath` answered [PathReservationService.ReservationResult.GeometricallyImpossible]
		 * (Issue #903). Permanent for the same request, so it is not retried.
		 */
		GEOMETRICALLY_IMPOSSIBLE,

		/** Every one of the [maxEntryAttempts] attempts failed on a retryable result. */
		ATTEMPTS_EXHAUSTED
	}

	/**
	 * Structured record of a train whose entry reservation was given up (Issue #1148).
	 *
	 * The train is retired from the approved list and never started, so it frees its
	 * concurrency slot instead of being retried forever.
	 *
	 * @property trainName name of the refused train, or `null` for [EntryFailureKind.UNKNOWN_IN_OUT],
	 *   where no train is created
	 * @property inName name of the entry [DynamicInOut]
	 * @property outName name of the exit [DynamicInOut]
	 * @property kind why the entry was given up
	 * @property attempts number of entry attempts made, the failing one included; `0` for
	 *   [EntryFailureKind.UNKNOWN_IN_OUT]
	 * @property lastResult result of the last [PathReservationService.reservePath] call, or
	 *   `null` when the failing attempt made no call
	 * @property simTime simulation time of the failing attempt
	 */
	data class EntryFailure(
		val trainName: String?,
		val inName: String,
		val outName: String,
		val kind: EntryFailureKind,
		val attempts: Int,
		val lastResult: PathReservationService.ReservationResult?,
		val simTime: Double
	)

	/** A generated train's spec with its resolved entry and exit. */
	private data class TrainEntry(
		val spec: TrainSpec,
		val inIo: DynamicInOut,
		val outIo: DynamicInOut
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
		require(stallPersistTicks > 0) {
			"stallPersistTicks must be positive, got: $stallPersistTicks"
		}
	}

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
	private val trainEntries: MutableMap<Train, TrainEntry> = mutableMapOf()
	private val generator: DeterministicGenerator = DeterministicGenerator(context)

	/** Trains whose entry path was reserved and whose process was activated. */
	private val startedTrains: MutableSet<Train> = mutableSetOf()

	/** Entry attempts made so far per not-yet-started train (Issue #1148). */
	private val entryAttempts: MutableMap<Train, Int> = mutableMapOf()

	/**
	 * What refused the last attempt of each waiting train, per candidate route (Issue #1269): the
	 * [PathReservationService.ReservationResult.AllPathsBlocked.blockedBy] of that attempt. Every
	 * release wakes a new attempt, which replaces the entry, so the record is never older than the
	 * last release. Kept per candidate so a per-route (OR) view of the wait stays possible.
	 *
	 * Precondition for the knot search: the list must hold ALL of the waiter's alternatives, since a
	 * partial list over-reports. That holds today because only `reservePath` refusals are recorded
	 * (its list is complete for contention). Hazard: `reservePathToAny` carries the last target's
	 * list only, so recording its refusals here could fabricate a knot.
	 */
	private val lastBlockedBy: MutableMap<Train, List<PathReservationService.BlockedCandidate>> = mutableMapOf()

	/** Resource-gate blocks per `(entry, exit)` name pair; the topology is static. */
	private val gateBlocksByRoute: MutableMap<Pair<String, String>, List<DynamicTrackBlock>> = mutableMapOf()

	private val entryFailures: MutableList<EntryFailure> = mutableListOf()

	/**
	 * Trains refused on a retryable result, in the order of their refusal (FIFO). A train waits here
	 * until a release wakes the [retryProcess]; the loop's own deque is used on purpose, not a kDisco
	 * `Resource` queue, whose re-entry puts a waiter at the tail (SP9 finding F8).
	 */
	private val waitingTrains: ArrayDeque<Train> = ArrayDeque()

	private val retryProcess: RetryProcess = RetryProcess()

	/** `true` while [retryProcess] is passivated and can be reactivated. */
	private var retryParked: Boolean = false

	/** A release arrived that the [retryProcess] has not answered yet. */
	private var retryRequested: Boolean = false

	/** Name of the train whose entry attempt is running, so that its own rollback is not a release. */
	private var attemptingTrainName: String? = null

	private val retryActivationTimes: MutableList<Double> = mutableListOf()

	private val watchdog: DeadlockWatchdog = DeadlockWatchdog(stallPersistTicks)

	/**
	 * The report of the run aborted by the deadlock watchdog, or `null` (Issue #1154). Written only
	 * by the tick that raised the report.
	 */
	var stall: SimulationStalled? = null
		private set

	init {
		context.onBlockEvent { event ->
			when (event) {
				is BlockEvent.BlockReleased -> onReleaseSignal(event.trainId)
				// Issue #1271: a block a train stands on without a registry entry (the #893 shape)
				// becomes FREE with no BlockReleased; its leave() announces it. For an owned block
				// the BlockReleased of the same Tail step follows, and the two wakes coalesce.
				is BlockEvent.OccupancyCleared -> onReleaseSignal(null)
				else -> Unit
			}
		}
		context.onReservationExpired { onReleaseSignal(it.trainId) }
		// Issue #1271: a flank switch claim can outlive the train's last block (no BlockReleased).
		context.onSwitchClaimsReleased { onReleaseSignal(it.trainId) }
	}

	// Test-observability counters (#365 pattern).
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0

	/**
	 * Answers releases: sleeps until [onReleaseSignal] reactivates it, then attempts the waiting
	 * trains once in queue order (Issue #1154).
	 */
	private inner class RetryProcess : Process() {
		override suspend fun actions() {
			while (true) {
				while (!retryRequested) {
					retryParked = true
					passivate()
				}
				retryParked = false
				drainWaitingTrains()
			}
		}
	}

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
			val inIo = inOuts.find { it.name == spec.inName }
			val outIo = inOuts.find { it.name == spec.outName }
			if (inIo == null || outIo == null) {
				recordUnknownInOut(spec, now)
				return
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
			trainEntries[train] = TrainEntry(spec, inIo, outIo)
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
		Process.activate(retryProcess)
	}

	override suspend fun iteration() {
		cleanupTerminatedTrains()
		approveTrains()
		if (approvedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approvedTrains.size
		}
		// One admission policy (Issue #1154): the loop-level expiry releases stale holdings at the
		// top of the tick, so no entry attempt can release-and-take them mid-attempt ahead of an
		// older waiter; and the pending wake drains synchronously before fresh trains, so a release
		// that queued before this tick cannot outrun the waiters either. startApprovedTrains then
		// attempts only fresh trains.
		pathReservationService.expireStaleReservations() // Issue #1174
		drainWaitingTrains()
		startApprovedTrains()
		checkForStall()
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
			trainEntries.remove(train)
			startedTrains.remove(train)
			entryAttempts.remove(train)
			lastBlockedBy.remove(train)
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
			if (train in startedTrains || train in waitingTrains || trainHasActivePath(train)) {
				continue
			}
			val entry = trainEntries[train] ?: continue
			attemptEntry(train, entry)
		}
	}

	/**
	 * One entry attempt for [train], and what follows from it: start the train, queue it behind the
	 * trains already waiting (a retryable refusal keeps a waiting train at its place), or retire it.
	 */
	private suspend fun attemptEntry(
		train: Train,
		entry: TrainEntry
	) {
		attemptingTrainName = train.name
		val outcome =
			try {
				reserveEntryPath(train, entry)
			} finally {
				attemptingTrainName = null
			}
		when (outcome) {
			EntryAttemptOutcome.Reserved -> {
				waitingTrains.remove(train)
				startedTrains.add(train)
				logger.info { "MultiTrainLoop: starting ${train.name}" }
				Process.activate(train)
			}
			EntryAttemptOutcome.Retry -> if (train !in waitingTrains) waitingTrains.addLast(train)
			is EntryAttemptOutcome.Failed -> {
				waitingTrains.remove(train)
				retireRefusedTrain(train, outcome.failure)
			}
		}
	}

	/**
	 * Called synchronously from inside the release (a block event or a reservation expiry), so it
	 * only records and schedules. A release by the train whose attempt is running is that attempt's
	 * own rollback and is ignored; **every other release wakes the retry**. No relevance model sits
	 * between a release and the wake: the state change a waiter waits for can ride on the release of
	 * a block outside every waiter's candidate gate, because the releasing train's switch position
	 * and occupancy free together with its last blocks -- a gate-block filter drops exactly that
	 * wake and the waiter stays queued forever (proven by the two-trains-on-the-y-junction
	 * regression of `MultiTrainLoopTest` in the Issue #1154 review round). A wake is one cheap pass
	 * over the queue; irrelevant wakes burn attempts the [maxEntryAttempts] cap already absorbs.
	 */
	private fun onReleaseSignal(releasingTrainName: String?) {
		// Issue #1271: an attempt never suspends and never physically clears occupancy inside the
		// call, so a train-less (`null`) signal cannot be that attempt's own rollback.
		if (releasingTrainName != null && releasingTrainName == attemptingTrainName) {
			return
		}
		if (waitingTrains.isEmpty()) {
			return
		}
		retryRequested = true
		if (retryParked) {
			retryParked = false
			Process.reactivate(retryProcess)
		}
	}

	/**
	 * Answers a pending wake: attempts every waiting train once, in queue order, and repeats when a
	 * release arrived meanwhile. This is the one admission policy shared by both wake drivers (the
	 * reactive [RetryProcess] and the tick's synchronous pass), so a fresh train can never outrun
	 * the waiters of its tick. The wake is recorded once per instant, and one failing attempt
	 * neither stops future retries nor starves the waiters behind it.
	 */
	private suspend fun drainWaitingTrains() {
		if (!retryRequested) {
			// Without a pending wake the drain must not run: that would be polling (Issue #1154).
			return
		}
		val now = time()
		if (now != retryActivationTimes.lastOrNull()) {
			retryActivationTimes.add(now)
		}
		do {
			retryRequested = false
			for (train in waitingTrains.toList()) {
				val entry = trainEntries[train]
				if (entry == null || train in startedTrains || trainHasActivePath(train)) {
					continue
				}
				try {
					attemptEntry(train, entry)
				} catch (e: Exception) {
					// A service failure inside one attempt must not kill the retry process silently
					// (kDisco's supervisor would leave it dead forever) nor starve the waiters
					// behind the failing one, whose throw would recur deterministically.
					logger.error(e) {
						"MultiTrainLoop: an entry attempt failed inside the retry drain; remaining " +
							"waiters stay attempted and later releases still wake the retry"
					}
				}
			}
		} while (retryRequested)
	}

	/**
	 * Watchdog tick (Issue #1154): builds the wait-for graph and aborts the run when a deadlock has
	 * persisted. A train waits for the trains that refused its last attempt (see [buildWaitForGraph]);
	 * the deadlock is the knot of that graph (Issue #1270), and the persistence rule follows the cycle
	 * inside the knot. Only two or more unstarted trains can form a knot: a started train waits for
	 * nobody here (its whole path was reserved before it started), so it can block a route but never
	 * be a member.
	 */
	private fun checkForStall() {
		if (stall != null) {
			return
		}
		val waiters = unstartedTrains()
		val knot = if (waiters.size < 2) null else buildWaitForGraph(waiters).findKnot()
		val persisted = watchdog.observe(time(), knot?.cycle) ?: return
		val report = SimulationStalled(persisted, watchdog.reportedTicks(), time(), knot?.members ?: persisted.toSet())
		stall = report
		logger.error { "MultiTrainLoop: ${report.message}" }
		env.errorStop(report)
	}

	/**
	 * The edges of the wait-for graph as the watchdog builds it at this instant (Issue #1269): each
	 * waiting train with the trains it waits for. Built on demand, so a test can read it on any tick.
	 */
	internal fun waitForEdges(): Map<String, Set<String>> = buildWaitForGraph(unstartedTrains()).edges()

	/** The approved trains not started yet: the only ones that can wait here. */
	private fun unstartedTrains(): List<Train> = approvedTrains.filter { it !in startedTrains && trainEntries[it] != null }

	/**
	 * The wait-for graph (Issue #1269): each of [waiters] points at what refused its last attempt --
	 * the owner [PathReservationService.getBlockOwner] names NOW for each block that refusal named
	 * (a block freed since has no owner and adds no edge; the same holds for a block that is still
	 * busy but has no train attributed, so a route held only by such an occupancy looks free and its
	 * waiter is never a knot member: the safe no-abort choice, which can hide a real deadlock until
	 * the end time), and the holder of each switch claim it named. Each refused candidate is one
	 * route of the waiter (Issue #1270), so the knot search can
	 * treat the wait as OR over routes; the edges are the union over the routes. A waiter whose last
	 * attempt was not an [PathReservationService.ReservationResult.AllPathsBlocked] has no route and
	 * no edge.
	 */
	private fun buildWaitForGraph(waiters: List<Train>): WaitForGraph {
		val graph = WaitForGraph()
		for (waiter in waiters) {
			for (candidate in lastBlockedBy[waiter].orEmpty()) {
				val blockers =
					candidate.blockedBlocks.keys.mapNotNull { pathReservationService.getBlockOwner(it) } +
						candidate.lockedSwitches.values
				graph.addRoute(waiter.name, blockers)
			}
		}
		return graph
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
		waitingTrains.remove(train)
		trainEntries.remove(train)
		entryAttempts.remove(train)
		lastBlockedBy.remove(train)
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
	 * candidate search.
	 *
	 * The call goes through the [BlockResourceRegistry] gate (see [reserveThroughGate]), which is
	 * inert today; journey-time exclusivity is enforced by [PathReservationService].
	 *
	 * @return [EntryAttemptOutcome.Reserved] on success; [EntryAttemptOutcome.Retry] when this
	 *   attempt failed on a retryable result and attempts remain; [EntryAttemptOutcome.Failed]
	 *   when the entry is permanently impossible or this was attempt number [maxEntryAttempts].
	 */
	private suspend fun reserveEntryPath(
		train: Train,
		entry: TrainEntry
	): EntryAttemptOutcome {
		val attempt = (entryAttempts[train] ?: 0) + 1
		entryAttempts[train] = attempt

		val gateBlocks = gateBlocksFor(entry)
		if (gateBlocks.isEmpty()) {
			return failedEntry(train, entry.spec, EntryFailureKind.NO_ROUTE, attempt, null)
		}

		val result = reserveThroughGate(train, gateBlocks, entry.inIo, entry.outIo)
		if (result is PathReservationService.ReservationResult.AllPathsBlocked) {
			lastBlockedBy[train] = result.blockedBy
		} else {
			lastBlockedBy.remove(train)
		}
		val permanentKind =
			when (result) {
				is PathReservationService.ReservationResult.Success -> {
					entryAttempts.remove(train)
					logger.debug {
						"MultiTrainLoop: reserved ${result.reservedBlocks.size} block(s) for ${train.name} " +
							"on attempt $attempt"
					}
					return EntryAttemptOutcome.Reserved
				}
				is PathReservationService.ReservationResult.NoPathExists -> EntryFailureKind.NO_ROUTE
				is PathReservationService.ReservationResult.GeometricallyImpossible ->
					EntryFailureKind.GEOMETRICALLY_IMPOSSIBLE
				else -> null
			}
		return when {
			permanentKind != null -> failedEntry(train, entry.spec, permanentKind, attempt, result)
			attempt >= maxEntryAttempts ->
				failedEntry(train, entry.spec, EntryFailureKind.ATTEMPTS_EXHAUSTED, attempt, result)
			else -> EntryAttemptOutcome.Retry
		}
	}

	/**
	 * The gate blocks of [entry]: every block of the candidate routes [RouteFinder] gives for
	 * the `(entry, exit)` pair, the same candidates `reservePath` tries for an InOut-to-InOut
	 * request. Cached per pair, because the topology is static.
	 *
	 * Before Issue #1148 the gate enumerated every topological path instead (up to 912 on Praha,
	 * seconds per pair on the JVM and minutes on native); the route search answers in
	 * milliseconds.
	 */
	private fun gateBlocksFor(entry: TrainEntry): List<DynamicTrackBlock> =
		gateBlocksByRoute.getOrPut(entry.spec.inName to entry.spec.outName) {
			env
				.getRouteFinder()
				.findRoutes(entry.inIo.staticRef, entry.outIo.staticRef, env)
				.flatMap { extractUniqueBlocks(it.segments) }
				.distinct()
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
				// Issue #903: a permanent impossibility, not ordinary contention (classes: see
				// ReservationResult.GeometricallyImpossible; the non-oriented endpoint classes cannot
				// arise here, both endpoints are InOuts). Logged at WARN; the entry fails at once with
				// GEOMETRICALLY_IMPOSSIBLE and is not retried.
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

	/**
	 * Records an [EntryFailureKind.UNKNOWN_IN_OUT] failure for [spec]. No [Train] is created,
	 * so the spec takes no concurrency slot and is not counted as entered.
	 */
	private fun recordUnknownInOut(
		spec: TrainSpec,
		now: Double
	) {
		val failure =
			EntryFailure(
				trainName = null,
				inName = spec.inName,
				outName = spec.outName,
				kind = EntryFailureKind.UNKNOWN_IN_OUT,
				attempts = 0,
				lastResult = null,
				simTime = now
			)
		entryFailures.add(failure)
		logger.error {
			"MultiTrainLoop: no train generated for ${spec.inName} -> ${spec.outName}: " +
				"the context has no DynamicInOut with one of these names"
		}
	}

	private fun releaseIfOccupied(resource: Resource) {
		if (resource.occupied > 0) {
			resource.release(1)
		}
	}

	override suspend fun interLoopSleep() {
		if (time() >= endTime) {
			reportRunEndResidue()
			generator.terminate()
			env.stop()
			return
		}
		hold(1.0)
	}

	/**
	 * Run-end residue (Issue #1154): a train that no release ever wakes keeps its place in the queue
	 * to the end of the run, with no entry failure recorded and no watchdog report -- the run ends
	 * normally at [endTime]. The residue is logged here and exposed through [getWaitingTrainNames],
	 * so a run with such trains stays diagnosable.
	 */
	private fun reportRunEndResidue() {
		if (waitingTrains.isEmpty()) {
			return
		}
		logger.warn {
			"MultiTrainLoop: ${waitingTrains.size} train(s) still wait for a release at run end and " +
				"have no entry failure recorded: ${getWaitingTrainNames().joinToString()}"
		}
	}

	/** Test-observability: number of trains that entered the pending queue. */
	fun getTrainsEntered(): Int = trainsEnteredCount

	/** Test-observability: number of trains that completed their journey. */
	fun getTrainsExited(): Int = trainsExitedCount

	/** Test-observability: peak number of concurrently approved trains. */
	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	/**
	 * Test-observability: simulation times at which a pending wake was answered by the retry drain
	 * (the reactive process or the tick's synchronous pass), once per instant (Issue #1154).
	 */
	fun getRetryActivationTimes(): List<Double> = retryActivationTimes.toList()

	/** Test-observability: names of the trains still queued for a release (Issue #1154). */
	fun getWaitingTrainNames(): List<String> = waitingTrains.map { it.name }

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
