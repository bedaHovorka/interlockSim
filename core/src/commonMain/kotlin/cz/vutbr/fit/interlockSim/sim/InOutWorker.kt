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

import cz.ksimulantenbande.kdisco.Condition
import cz.ksimulantenbande.kdisco.Head
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Behaviour of InOut process
 *
 * Keeps [DynamicInOut.occupied] equal to "the entry queue is non-empty" (issue #1008): set when a
 * train enters, only after that train is in the queue, and re-checked whenever the worker observes
 * the queue.
 */
class InOutWorker(
	private val env: SimulationEnvironment,
	private val inOut: DynamicInOut,
	val navigator: TopologyNavigator = env.getRoutingServices().getTopologyNavigator(),
	private val pathReservationService: PathReservationService = env.getRoutingServices().getPathReservationService(),
	/** Train-side view of the path registry, used by the episode-end check in [waitUntilPathFreeBoundedByStaleOrigin]. */
	private val trainNavigationService: TrainNavigationService = env.getRoutingServices().getTrainNavigationService()
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Issue #1295: the wait before the next entry-path attempt while the queued train still holds a
		 * stale reservation that makes its entry route non-contiguous — one control period. A plain
		 * `hold`, not a `waitUntil`: some scenarios (MultiTrainLoop, ThreeTrainLoop) never expire
		 * reservations, so a state condition could block forever.
		 *
		 * The bounded blocked-path poll in [waitUntilPathFreeBoundedByStaleOrigin] steps by the same
		 * period while a stale-origin episode runs (Issue #1295 review fix).
		 */
		internal const val STALE_ORIGIN_RETRY_SECONDS = 2.0

		/**
		 * Issue #1295: how long a stale-origin episode may last, counted from its first denial, before
		 * it is suspicious. Assumes the stale reservation expires within one reservation TTL plus one
		 * control period, which must stay at most this value.
		 *
		 * Issue #1457: a one-time WARNING threshold, no longer a FATAL. [checkStaleOriginDeadline] logs
		 * the one WARNING of the train once the episode passes it and throws nothing; the train keeps
		 * waiting at its InOut, with simulated time advancing, until its entry route is granted or the
		 * run ends.
		 */
		internal const val STALE_ORIGIN_MAX_WAIT_SECONDS =
			2 * DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS
	}

	/**
	 * Issue #1295: how many stale-origin episodes this worker has recorded — one per queued train
	 * whose entry route was refused as non-contiguous with a reservation it still held.
	 */
	var staleOriginDenials: Int = 0
		private set

	/** The train of the running stale-origin episode, or `null` when none runs (Issue #1295). */
	private var staleOriginTrainId: String? = null

	/** Simulation time of the running episode's first denial (Issue #1295). */
	private var staleOriginSince: Double = 0.0

	/**
	 * Reason of the running episode's first denial (Issue #1295). The episode threshold WARNING
	 * names this reason when the entry path stays unavailable and no denial arrives to supply a fresh
	 * one.
	 */
	private var staleOriginReason: String = ""

	/**
	 * Issue #1457: the trains this worker has logged its threshold WARNING for — one WARNING per
	 * train, however many episodes the train's wait splits into.
	 */
	private val staleOriginWarnedTrains: MutableSet<String> = mutableSetOf()

	/**
	 * Issue #1457: the refused-entry state of this worker — the train at the head of its queue whose
	 * entry route the running stale-origin episode refuses as non-contiguous, this InOut and the time
	 * of the episode's first refusal — or `null` when no episode runs. The train waits at the InOut
	 * with simulated time advancing; the route that blocks it is the dispatcher's to cancel, so a
	 * watchdog can read this as actionable work. Read on the simulation thread or after the run. The
	 * episode premise is revalidated at read time: during the retry hold a dispatcher regrant or a
	 * departure turns the state `null` before the worker's next pass, which is what keeps a run that
	 * ends inside the hold from publishing a recovered train as refused-waiting.
	 *
	 * Known limit: for a refused train that holds no blocks, the premise check in
	 * [waitUntilPathFreeBoundedByStaleOrigin] ("the holding is gone") is always true, so a pass with
	 * the entry path blocked ends the episode. That resets `since` at the next refusal, and a run
	 * that ends inside such a blocked pass leaves the train out of the run record's refused entries.
	 * Unreachable today by construction (Issue #893): a train that holds no blocks has an empty
	 * footprint, so the contiguity check passes vacuously and the entry is never refused as
	 * non-contiguous; the no-block wait exists only as a safety net.
	 */
	val refusedEntry: RefusedEntry?
		get() {
			val trainId = staleOriginTrainId ?: return null
			// Issue #1457 review round 1: the episode premise is revalidated at read time, not only in
			// [waitUntilPathFreeBoundedByStaleOrigin]'s poll. During the [STALE_ORIGIN_RETRY_SECONDS]
			// hold a dispatcher can grant the train usable entry authority (or the train can leave the
			// queue some other way), and a run that ends before the worker's next pass must not
			// publish the recovered train as refused-waiting. Both probes are plain reads (no engine
			// calls), the same arms the poll checks. The holding-is-gone arm is deliberately left out:
			// the wait also covers a train that holds no reservation, whose footprint is empty the
			// whole time.
			if ((queqe.first() as? Train)?.name != trainId) return null
			if (trainNavigationService.isPathReservedForTrain(trainId, inOut)) return null
			return RefusedEntry(trainId, inOut.name, EntryRefusalKind.NON_CONTIGUOUS, staleOriginSince)
		}

	private val queqe = Head()
	private var myIdle = true
	private val next: TrackSection =
		requireNotNull(
			navigator.getNextTrackSection(inOut, null)
		) {
			"InOut ${inOut.name} has no outgoing track section. " +
				"This is a configuration error - InOut must be connected to the network."
		}

	private val pathFree =
		Condition {
			try {
				// next is guaranteed non-null by init check
				pathReservationService.isPathToAnyNextSemaphoreAvailable(inOut, next)
			} catch (e: TrackOperationException) {
				logger.error {
					"${Process.time()} APPROVAL_ERROR: InOut ${inOut.name} - " +
						"path check failed: ${e.message}"
				}
				logger.error(e) { "InOutWorker ${inOut.name} pathFree condition failed with exception" }
				env.errorStop(e)
				false
			}
		}

	@Suppress("NestedBlockDepth") // Legacy sim/ code - deep nesting required for kDisco event-driven logic
	override suspend fun iteration() {
		while (!queqe.empty()) {
			inOut.setOccupied(!queqe.empty())
			myIdle = false
			logger.debug { "InOutWorker ${inOut.name} queue non-empty, processing train" }
			env.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS)
			// Issue #1295 review fix: with no stale-origin episode the plain waitUntil stays (ordinary
			// contention must never be timed out); with one running, the poll keeps the WARNING
			// threshold armed (Issue #1457: it no longer stops the run)
			waitUntilPathFreeBoundedByStaleOrigin()
			val first = queqe.first()
			if (first == null) {
				logger.debug {
					"InOutWorker ${inOut.name} queue became empty while waiting for a free path; " +
						"resuming loop to re-check queue state"
				}
				inOut.setOccupied(!queqe.empty())
				continue
			}
			val firstLink = first
			logger.debug { "InOutWorker ${inOut.name} path is now free, reserving for train" }

			try {
				// Use integrated path setup like working version
				// This reserves blocks AND sets up semaphore signals in one call
				val train = firstLink as? Train
				val trainId =
					train?.name ?: throw SimulationException(
						"InOutWorker ${inOut.name} encountered non-Train entity in queue: $firstLink"
					)
				// next is guaranteed non-null by init check
				val result =
					pathReservationService
						.reservePathToAnyNextSemaphore(trainId, inOut, next)

				// Handle reservation result
				when (result) {
					is PathReservationService.ReservationResult.Success -> {
						logger.info {
							"${time()} APPROVAL: InOut ${inOut.name} - " +
								"Path reserved successfully for train $trainId, " +
								"${result.reservedBlocks.size} blocks"
						}
						// Issue #1295: disarm the episode if one was armed on this train — the success is
						// the entry grant the episode was waiting for. since/reason keep the previous
						// episode's values until the next episode's first denial overwrites them.
						staleOriginTrainId = null
						// Train can now proceed
					}
					is PathReservationService.ReservationResult.NoPathExists -> {
						val errorMsg =
							"InOut ${inOut.name} - No path exists to any semaphore. " +
								"This is a network configuration error."
						logger.error { "${time()} APPROVAL_DENIED: $errorMsg" }
						throw SimulationException(errorMsg)
					}
					is PathReservationService.ReservationResult.AllPathsBlocked -> {
						// All paths are currently blocked - this is normal during busy periods
						// The train will wait in the queue and try again
						logger.debug {
							"${time()} APPROVAL_WAIT: InOut ${inOut.name} - " +
								"All ${result.attemptedPaths} path(s) blocked for train $trainId, " +
								"will retry"
						}
						// Continue waiting (waitUntil will be called again in next iteration)
						continue
					}
					is PathReservationService.ReservationResult.Conflict -> {
						val errorMsg =
							"InOut ${inOut.name} - Reservation conflict: " +
								"block ${result.conflictingBlock} already owned by ${result.existingOwner}. " +
								"This indicates a race condition or logic error."
						logger.error { "${time()} APPROVAL_ERROR: $errorMsg" }
						throw SimulationException(errorMsg)
					}
					is PathReservationService.ReservationResult.NonContiguousStart -> {
						// Issue #1457: a refused route the train waits out at this InOut, never a FATAL;
						// the wait holds simulated time before the caller's retry (Issue #1295).
						waitOutNonContiguousEntry(trainId, result.reason)
						continue
					}
					is PathReservationService.ReservationResult.GeometricallyImpossible -> {
						// Issue #903: this
						// is a permanent impossibility (the non-oriented endpoint classes cannot arise here,
						// because the origin is an InOut and every target a semaphore), not ordinary
						// contention (classes: see ReservationResult.GeometricallyImpossible), so
						// `continue`-and-retry via waitUntil(pathFree) would spin without advancing simulation
						// time -- the condition it waits on is path AVAILABILITY, which this failure mode
						// leaves untouched.
						val errorMsg =
							"InOut ${inOut.name} - Route geometrically impossible for train " +
								"$trainId: ${result.reason}"
						logger.error { "${time()} APPROVAL_ERROR: $errorMsg" }
						throw SimulationException(errorMsg)
					}
					is PathReservationService.ReservationResult.DivergesFromHeldRoute -> {
						// Issue #1066. Unreachable by construction: a
						// train at the head of this InOut's admission queue has no stored PathInfo, so
						// there is no held route a candidate could diverge from. Failing loudly beats
						// `continue`, which would re-attempt the same refused request without
						// advancing simulation time.
						val errorMsg =
							"InOut ${inOut.name} - Route diverges from the route already held by " +
								"train $trainId: ${result.reason}"
						logger.error { "${time()} APPROVAL_ERROR: $errorMsg" }
						throw SimulationException(errorMsg)
					}
				}
			} catch (e: Exception) {
				logger.warn {
					"${Process.time()} APPROVAL_DENIED: InOut ${inOut.name} - " +
						"path setup failed for $firstLink: ${e.message}"
				}
				logger.error(e) { "InOutWorker ${inOut.name} path setup failed with exception" }
				env.errorStop(e)
				inOut.setOccupied(!queqe.empty())
				return
			}
			env.report("Path reserved for $firstLink", inOut, ReportType.NODE_EVENTS)

			// wait for the train to leave the queue
			logger.debug { "InOutWorker ${inOut.name} waiting for train $firstLink to leave queue" }
			waitUntil(Condition { firstLink != queqe.first() })
			inOut.setOccupied(!queqe.empty())
			logger.debug { "InOutWorker ${inOut.name} train left queue" }
		}
		inOut.setOccupied(!queqe.empty())
		// Issue #1457: an empty queue has no head train, so no refused entry can still run.
		staleOriginTrainId = null
		myIdle = true
	}

	/**
	 * Issue #1457: waits out an entry route refused as non-contiguous with the footprint of [trainId]
	 * — a refused route, never a FATAL `SimulationException` (1B-SP10.1 G1: a dispatcher error must
	 * not kill the railway).
	 *
	 * The usual cause is a stale reservation the train still holds (Issue #1295; for example a route
	 * set up from the wrong InOut): the reservation expiry or the dispatcher releases it. A train that
	 * holds no reservation (Issue #893 deemed that unreachable) waits the same way. Either way this
	 * waits [STALE_ORIGIN_RETRY_SECONDS] and the caller's `continue` retries with simulation time
	 * advanced — on EVERY refusal, also the first one of a new episode: re-entering `waitUntil(pathFree)`
	 * without the `hold` would spin, because a non-contiguous origin leaves the path perfectly free, so
	 * the condition is true at once and the reservation is re-attempted and re-refused at a frozen
	 * instant.
	 *
	 * The first denial of an episode (a new train, or the first after a success or a void premise) is
	 * logged at INFO, reported, counted in [staleOriginDenials] and exposed as [refusedEntry]; retries
	 * log at DEBUG and check the one-time WARNING threshold ([checkStaleOriginDeadline]). The stale
	 * reservation is not released here — the dispatcher or
	 * [cz.vutbr.fit.interlockSim.context.navigation.ReservationExpiry] does that.
	 */
	private suspend fun waitOutNonContiguousEntry(
		trainId: String,
		reason: String
	) {
		val now = time()
		if (staleOriginTrainId != trainId) {
			staleOriginTrainId = trainId
			staleOriginSince = now
			staleOriginDenials++
			staleOriginReason = reason
			val holding =
				if (pathReservationService.getReservedBlocks(trainId).isEmpty()) {
					"it holds no reservation"
				} else {
					"it still holds a stale reservation"
				}
			val message =
				"InOut ${inOut.name} - ${EntryRefusalKind.NON_CONTIGUOUS.text} for train $trainId ($holding): " +
					"$reason; the train waits at its InOut"
			logger.info { "$now APPROVAL_DENIED: $message" }
			env.report(message, inOut, ReportType.NODE_EVENTS)
		} else {
			val waited = checkStaleOriginDeadline(trainId, reason)
			logger.debug {
				"$now APPROVAL_WAIT: InOut ${inOut.name} - train $trainId still refused as non-contiguous " +
					"after $waited s, will retry"
			}
		}
		hold(STALE_ORIGIN_RETRY_SECONDS)
	}

	/**
	 * Issue #1295 review fix: the one threshold check for a running stale-origin episode, shared by
	 * [waitOutNonContiguousEntry] (a further denial arrived) and the blocked-path poll in [iteration]
	 * (with the entry path unavailable the reservation is never re-attempted, so no denial — and
	 * with the previously inline check alone, no threshold check — can arrive there).
	 *
	 * Issue #1457: once the episode has lasted [STALE_ORIGIN_MAX_WAIT_SECONDS] this logs one WARNING
	 * — the one WARNING of the train, never repeated for it — and throws nothing.
	 *
	 * @return the seconds already waited since the episode's first denial ([staleOriginSince])
	 */
	private fun checkStaleOriginDeadline(
		trainId: String,
		reason: String
	): Double {
		val now = time()
		val waited = now - staleOriginSince
		if (waited >= STALE_ORIGIN_MAX_WAIT_SECONDS && staleOriginWarnedTrains.add(trainId)) {
			logger.warn {
				"$now APPROVAL_WAIT: InOut ${inOut.name} - ${EntryRefusalKind.NON_CONTIGUOUS.text} for train " +
					"$trainId for $waited s (threshold $STALE_ORIGIN_MAX_WAIT_SECONDS s); the train keeps waiting " +
					"at its InOut until the route that blocks it is released: $reason"
			}
		}
		return waited
	}

	/**
	 * The availability wait every [iteration] pass makes before attempting the entry reservation.
	 *
	 * Issue #1295 review fix: with no stale-origin episode running this is the plain
	 * `waitUntil(pathFree)` — ordinary contention must NEVER be timed out. With an episode running,
	 * pathFree may stay false for a reason the episode never sees — another train occupying the
	 * entry — and the bare waitUntil would park past the episode threshold, whose check otherwise
	 * only runs when a further denial arrives (a denial cannot arrive: the reservation is never
	 * re-attempted while the path is unavailable). The poll then steps by
	 * [STALE_ORIGIN_RETRY_SECONDS] and re-checks the episode threshold through
	 * [checkStaleOriginDeadline] each pass, so a stale reservation that cannot expire still gets its
	 * one WARNING at [STALE_ORIGIN_MAX_WAIT_SECONDS] (Issue #1457: a WARNING, no longer a stop); a
	 * path that frees while the reservation is still unexpired simply lets the loop pass and the
	 * caller re-attempts the reservation. Each pass also re-reads the episode's premise and ends the
	 * episode when it has become void — the full rule (and why the registry's own bookkeeping cannot
	 * answer it) sits with the check below: a still-blocked entry without the premise is ordinary
	 * contention, which must never be timed out. A refusal after the episode ended starts a new
	 * episode through [waitOutNonContiguousEntry], whose `hold` keeps simulated time advancing.
	 *
	 * Nothing here can throw: the [pathFree] condition handles its own [TrackOperationException],
	 * [checkStaleOriginDeadline] only logs and [hold] cannot fail on the retry constant.
	 */
	private suspend fun waitUntilPathFreeBoundedByStaleOrigin() {
		val staleTrainId = staleOriginTrainId
		if (staleTrainId == null) {
			waitUntil(pathFree)
			return
		}
		while (!pathFree.test()) {
			// Issue #1295 review fix (round 4): end the episode once its premise is observably
			// void. The stale wrong-origin holding was the only thing between the head train and
			// its entry, so the episode ends when any of these holds:
			// - the holding is gone (expired or cancelled): what remains is ordinary contention;
			// - the train no longer heads this admission queue: it departed on a grant booked
			//   without this reservation attempt; or
			// - the train already holds usable entry authority FROM THIS InOut: a fresh VALID
			//   grant (cancellation + re-request inside one control step) replaced the stale
			//   holding, which keeps getReservedBlocks non-empty — the registry cannot tell the
			//   two holdings apart, the train-side view can.
			// Healthy operation in all three shapes must never be timed out; any re-attempt the
			// caller makes right after runs the normal path (self-owned blocks accept, so the
			// entry route completes, and real refusals keep their own handling).
			// Issue #1457 known limit: for a train that holds no blocks the first arm is always
			// true, so a blocked pass ends its episode (see [refusedEntry]); unreachable today by
			// construction (Issue #893).
			if (
				pathReservationService.getReservedBlocks(staleTrainId).isEmpty() ||
				(queqe.first() as? Train)?.name != staleTrainId ||
				trainNavigationService.isPathReservedForTrain(staleTrainId, inOut)
			) {
				staleOriginTrainId = null
				waitUntil(pathFree)
				return
			}
			checkStaleOriginDeadline(staleTrainId, staleOriginReason)
			hold(STALE_ORIGIN_RETRY_SECONDS)
		}
	}

	/**
	 * @return input queue
	 */
	fun getQueqe(): Head = queqe

	/**
	 * In InOut is new Train - process awakening signal
	 * @param train
	 */
	suspend fun enterTrain(train: Train) {
		logger.debug { "InOutWorker ${inOut.name} entering train $train, queue empty: ${queqe.empty()}" }
		if (queqe.empty()) {
			// Insert first: listeners run synchronously and must already see the train in the queue.
			train.into(queqe)
			inOut.setOccupied(true)
		} else {
			// Already true (the queue is non-empty). Kept above Process.wait, which inserts the train
			// and passivates it until the train ahead leaves and reactivates it.
			inOut.setOccupied(true)
			Process.wait(queqe)
		}

		if (myIdle) {
			logger.debug { "InOutWorker ${inOut.name} was idle, activating to process train $train" }
			Process.activate(this)
		}
	}
}
