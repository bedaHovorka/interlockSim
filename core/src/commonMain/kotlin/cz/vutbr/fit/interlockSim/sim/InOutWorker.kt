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
import cz.ksimulantenbande.kdisco.Link
import cz.ksimulantenbande.kdisco.Process
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
	val navigator: TopologyNavigator = env.getRoutingServices().getTopologyNavigator(),
	private val pathReservationService: PathReservationService = env.getRoutingServices().getPathReservationService()
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}
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
						// Issue #893. Unreachable by construction: this branch is only reached for
						// the train at the head of THIS InOut's admission queue, i.e. one that has
						// not entered the network. Such a train holds no registry block (the only
						// reservation ever made for it is the one being attempted right here) and
						// occupies none (it has never entered a block), so its footprint is empty
						// and the contiguity predicate passes vacuously.
						//
						// NOTE the failure mode if that reasoning is ever wrong: the throw is
						// caught below and turned into env.errorStop(e) + return -- a HARD stop of
						// the simulation and permanent termination of this worker, not a retry.
						// That is nevertheless the right choice here, and the AllPathsBlocked
						// `continue` is NOT a usable alternative: `continue` re-enters
						// waitUntil(pathFree), whose condition is about path AVAILABILITY. A
						// non-contiguous origin leaves the path perfectly free, so the condition
						// would be immediately true, the reservation would be re-attempted and
						// re-rejected, and the loop would spin without advancing simulation time --
						// a silent hang, which is strictly worse to diagnose than a named stop.
						// Same reasoning as the Conflict branch above.
						val errorMsg =
							"InOut ${inOut.name} - Route origin rejected as non-contiguous for " +
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
				return
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
