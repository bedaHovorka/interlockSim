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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Condition
import jDisco.Head
import jDisco.Link
import jDisco.Process

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
	}

	private val queqe = Head()
	private var myIdle = true
	private val next: TrackSection = requireNotNull(
		navigator.getNextTrackSection(inOut, null)
	) {
		"InOut ${inOut.name} has no outgoing track section. " +
			"This is a configuration error - InOut must be connected to the network."
	}

	private val pathFree = Condition {
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

	@Suppress("NestedBlockDepth") // Legacy sim/ code - deep nesting required for jDisco event-driven logic
	override fun iteration() {
		while (!queqe.empty()) {
			myIdle = false
			logger.debug { "InOutWorker ${inOut.name} queue non-empty, processing train" }
			env.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS)
			waitUntil(pathFree)
			val first = queqe.first() as Link
			logger.debug { "InOutWorker ${inOut.name} path is now free, reserving for train" }

			try {
				// Use integrated path setup like working version
				// This reserves blocks AND sets up semaphore signals in one call
				val train = first as? Train
				val trainId = train?.name ?: throw SimulationException(
					"InOutWorker ${inOut.name} encountered non-Train entity in queue: $first"
				)
				// next is guaranteed non-null by init check
				val result = pathReservationService
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
						val errorMsg = "InOut ${inOut.name} - No path exists to any semaphore. " +
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
						val errorMsg = "InOut ${inOut.name} - Reservation conflict: " +
							"block ${result.conflictingBlock} already owned by ${result.existingOwner}. " +
							"This indicates a race condition or logic error."
						logger.error { "${time()} APPROVAL_ERROR: $errorMsg" }
						throw SimulationException(errorMsg)
					}
				}

			} catch (e: Exception) {
				logger.warn {
					"${Process.time()} APPROVAL_DENIED: InOut ${inOut.name} - " +
						"path setup failed for $first: ${e.message}"
				}
				logger.error(e) { "InOutWorker ${inOut.name} path setup failed with exception" }
				env.errorStop(e)
				return
			}
			env.report("Path reserved for $first", inOut, ReportType.NODE_EVENTS)

			// cekej na odchod vlaku z fronty
			logger.debug { "InOutWorker ${inOut.name} waiting for train $first to leave queue" }
			waitUntil(
				object : Condition {
					override fun test(): Boolean = first != queqe.first()
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
	fun enterTrain(train: Train) {
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
