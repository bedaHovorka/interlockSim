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
	private val pathReservationService: PathReservationService = env.getRoutingServices().getPathReservationService()
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
		 * Issue #1295: the longest a stale-origin episode may last, counted from its first denial,
		 * before the run stops with a [SimulationException] — the bounded-retry precedent of
		 * [Train.MAX_ORIGIN_NO_PATH_RETRIES]. Assumes the stale reservation expires within one
		 * reservation TTL plus one control period, which must stay at most this value.
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
	 * Reason of the running episode's first denial (Issue #1295). The episode deadline throws with
	 * this reason when the entry path stays unavailable and no denial arrives to supply a fresh one.
	 */
	private var staleOriginReason: String = ""

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
			// contention must never be timed out); with one running, the poll keeps the deadline armed
			if (waitUntilPathFreeBoundedByStaleOrigin()) {
				// The deadline fired mid-wait: the poll restored occupancy and error-stopped the run
				// already, so terminate this worker exactly like the reservation catch below does.
				return
			}
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
						// Issue #1295: retried while the train holds a stale reservation, FATAL otherwise.
						waitForStaleOriginOrFail(trainId, result.reason)
						continue
					}
					is PathReservationService.ReservationResult.GeometricallyImpossible -> {
						// Issue #903, same reasoning as the NonContiguousStart branch above: this
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
						// Issue #1066. Unreachable by construction, like NonContiguousStart above: a
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
		myIdle = true
	}

	/**
	 * Issue #1295: handles an entry route refused as non-contiguous with the footprint of [trainId].
	 *
	 * Recoverable only while the train still holds a stale reservation of its own (for example a route
	 * set up from the wrong InOut): the reservation expiry releases it, so this waits
	 * [STALE_ORIGIN_RETRY_SECONDS] (the caller's `continue` then retries with simulation time advanced).
	 * The first denial of an episode (a new train, or the first after a success) is logged at WARN,
	 * reported and counted in [staleOriginDenials]; retries log at DEBUG. The stale reservation is
	 * not released here — [cz.vutbr.fit.interlockSim.context.navigation.ReservationExpiry] does that.
	 *
	 * @throws SimulationException when the train holds no reservation (a real invariant violation),
	 *   or when the episode has lasted [STALE_ORIGIN_MAX_WAIT_SECONDS]
	 */
	private suspend fun waitForStaleOriginOrFail(
		trainId: String,
		reason: String
	) {
		if (pathReservationService.getReservedBlocks(trainId).isEmpty()) {
			// Issue #893. Unreachable by construction: the refusal only comes for the train at
			// the head of THIS InOut's admission queue, i.e. one that has not entered the
			// network. Holding no registry block (checked above), it occupies none either
			// (it has never entered a block), so its footprint is empty and the contiguity
			// predicate passes vacuously.
			//
			// NOTE the failure mode if that reasoning is ever wrong: the throw is
			// caught in iteration() and turned into env.errorStop(e) + return -- a HARD stop of
			// the simulation and permanent termination of this worker, not a retry.
			// That is nevertheless the right choice here, and the AllPathsBlocked
			// `continue` is NOT a usable alternative: `continue` re-enters
			// waitUntil(pathFree), whose condition is about path AVAILABILITY. A
			// non-contiguous origin leaves the path perfectly free, so the condition
			// would be immediately true, the reservation would be re-attempted and
			// re-rejected, and the loop would spin without advancing simulation time --
			// a silent hang, which is strictly worse to diagnose than a named stop.
			// Same reasoning as the Conflict branch above.
			val errorMsg = "InOut ${inOut.name} - Route origin rejected as non-contiguous for train $trainId: $reason"
			logger.error { "${time()} APPROVAL_ERROR: $errorMsg" }
			throw SimulationException(errorMsg)
		}
		val now = time()
		if (staleOriginTrainId != trainId) {
			staleOriginTrainId = trainId
			staleOriginSince = now
			staleOriginDenials++
			staleOriginReason = reason
			val message =
				"InOut ${inOut.name} - Entry route for train $trainId refused as non-contiguous with " +
					"the stale reservation it still holds: $reason; waiting for the reservation to expire"
			logger.warn { "$now APPROVAL_DENIED: $message" }
			env.report(message, inOut, ReportType.NODE_EVENTS)
		} else {
			val waited = checkStaleOriginDeadline(trainId, reason)
			logger.debug {
				"$now APPROVAL_WAIT: InOut ${inOut.name} - train $trainId still holds a stale " +
					"reservation after $waited s, will retry"
			}
		}
		hold(STALE_ORIGIN_RETRY_SECONDS)
	}

	/**
	 * Issue #1295 review fix: the one deadline check for a running stale-origin episode, shared by
	 * [waitForStaleOriginOrFail] (a further denial arrived) and the blocked-path poll in [iteration]
	 * (with the entry path unavailable the reservation is never re-attempted, so no denial — and
	 * with the previously inline check alone, no deadline check — can arrive there).
	 *
	 * @return the seconds already waited since the episode's first denial ([staleOriginSince])
	 * @throws SimulationException when the episode has lasted [STALE_ORIGIN_MAX_WAIT_SECONDS] — the
	 *   exact message the inline check produced before the extraction, so no caller's failure
	 *   semantics change
	 */
	private fun checkStaleOriginDeadline(
		trainId: String,
		reason: String
	): Double {
		val waited = time() - staleOriginSince
		if (waited >= STALE_ORIGIN_MAX_WAIT_SECONDS) {
			throw SimulationException(
				"InOut ${inOut.name} - train $trainId waited $waited s for its entry route: " +
					"stale reservation not expired. $reason"
			)
		}
		return waited
	}

	/**
	 * The availability wait every [iteration] pass makes before attempting the entry reservation.
	 *
	 * Issue #1295 review fix: with no stale-origin episode running this is the plain
	 * `waitUntil(pathFree)` — ordinary contention must NEVER be timed out. With an episode running,
	 * pathFree may stay false for a reason the episode never sees — another train occupying the
	 * entry — and the bare waitUntil would park past the episode bound, whose check otherwise only
	 * runs when a further denial arrives (a denial cannot arrive: the reservation is never
	 * re-attempted while the path is unavailable). The poll then steps by
	 * [STALE_ORIGIN_RETRY_SECONDS] and re-arms the episode deadline through
	 * [checkStaleOriginDeadline] each pass, so a stale reservation that cannot expire still stops
	 * the run at [STALE_ORIGIN_MAX_WAIT_SECONDS]; a path that frees while the reservation is still
	 * unexpired simply lets the loop pass and the caller re-attempts the reservation. Once the stale
	 * holding is gone ([PathReservationService.getReservedBlocks] empty) the episode ends and the
	 * poll falls back to the plain `waitUntil(pathFree)`, because a still-blocked entry is then
	 * ordinary contention.
	 *
	 * The poll sits before the reservation `try` in [iteration], so a deadline thrown here cannot
	 * reach any caller catch: this method restores occupancy, reports the fire to the environment
	 * ([SimulationEnvironment.errorStop]) and returns `true` for the caller to terminate the worker
	 * the way its own catch does. [SimulationException] is the only thing the poll can throw — the
	 * [pathFree] condition handles its own [TrackOperationException] and [hold] cannot fail on the
	 * retry constant.
	 *
	 * @return `true` when the stale-origin deadline fired and the run was error-stopped, `false`
	 *   when the path became free (or there is no episode) and the caller may continue.
	 */
	private suspend fun waitUntilPathFreeBoundedByStaleOrigin(): Boolean {
		val staleTrainId = staleOriginTrainId
		if (staleTrainId == null) {
			waitUntil(pathFree)
			return false
		}
		return try {
			while (!pathFree.test()) {
				if (pathReservationService.getReservedBlocks(staleTrainId).isEmpty()) {
					// The stale holding expired while the entry stayed blocked: the episode is over and
					// what remains is ordinary contention, which must never be timed out.
					staleOriginTrainId = null
					waitUntil(pathFree)
					return false
				}
				checkStaleOriginDeadline(staleTrainId, staleOriginReason)
				hold(STALE_ORIGIN_RETRY_SECONDS)
			}
			false
		} catch (e: SimulationException) {
			logger.warn {
				"${Process.time()} APPROVAL_DENIED: InOut ${inOut.name} - " +
					"stale-origin deadline passed while the entry path stayed unavailable: ${e.message}"
			}
			logger.error(e) { "InOutWorker ${inOut.name} path setup failed with exception" }
			env.errorStop(e)
			inOut.setOccupied(!queqe.empty())
			true
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
