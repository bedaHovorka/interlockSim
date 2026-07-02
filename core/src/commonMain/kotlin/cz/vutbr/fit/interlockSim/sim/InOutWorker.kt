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

import cz.hovorka.kdisco.Condition
import cz.hovorka.kdisco.Head
import cz.hovorka.kdisco.Link
import cz.hovorka.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
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
 */
class InOutWorker(
	private val env: SimulationEnvironment,
	private val inOut: DynamicInOut,
	val navigator: TopologyNavigator = env.getTopologyNavigator(),
	private val pathReservationService: PathReservationService = env.getPathReservationService()
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Simulation-time delay before retrying a reservation that returned AllPathsBlocked.
		 *
		 * Required to prevent a wall-clock livelock (Issue #685): kDisco's `waitUntil`
		 * returns immediately without suspending when the condition is already true.
		 * `pathFree` (isPathToAnyNextSemaphoreAvailable) only checks block FREE-ness,
		 * while the actual reservation can still fail (e.g. switch locks held by another
		 * train, next-block validation). When the two disagree, a bare `continue` back
		 * to `waitUntil(pathFree)` spins forever at a fixed simulation time. Holding
		 * before the retry guarantees simulation time advances so other processes can
		 * run and release the conflicting resources.
		 */
		private const val RETRY_HOLD_SECONDS = 1.0
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
			myIdle = false
			logger.debug { "InOutWorker ${inOut.name} queue non-empty, processing train" }
			env.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS)
			waitUntil(pathFree)
			val first = queqe.first()
			if (first == null) {
				logger.debug {
					"InOutWorker ${inOut.name} queue became empty while waiting for a free path; " +
						"resuming loop to re-check queue state"
				}
				continue
			}
			val firstLink = first as Link
			logger.debug { "InOutWorker ${inOut.name} path is now free, reserving for train" }

			var allPathsBlocked = false
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
						allPathsBlocked = true
					}
					is PathReservationService.ReservationResult.Conflict -> {
						val errorMsg =
							"InOut ${inOut.name} - Reservation conflict: " +
								"block ${result.conflictingBlock} already owned by ${result.existingOwner}. " +
								"This indicates a race condition or logic error."
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
				return
			}
			if (allPathsBlocked) {
				// LIVELOCK GUARD (Issue #685): pathFree may still test true (it only
				// checks block FREE-ness, not switch locks / next-block validation),
				// in which case waitUntil() would return immediately and this loop
				// would spin forever at the same simulation time, hanging the run.
				// Hold first so simulation time advances and other processes can
				// release the conflicting resources.
				hold(RETRY_HOLD_SECONDS)
				continue
			}
			env.report("Path reserved for $firstLink", inOut, ReportType.NODE_EVENTS)

			// cekej na odchod vlaku z fronty
			logger.debug { "InOutWorker ${inOut.name} waiting for train $firstLink to leave queue" }
			waitUntil(
				object : Condition {
					override fun test(): Boolean = firstLink != queqe.first()
				}
			)
			logger.debug { "InOutWorker ${inOut.name} train left queue" }
		}
		myIdle = true
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
			train.into(queqe)
		} else {
			Process.wait(queqe)
		}

		if (myIdle) {
			logger.debug { "InOutWorker ${inOut.name} was idle, activating to process train $train" }
			Process.activate(this)
		}
	}
}
