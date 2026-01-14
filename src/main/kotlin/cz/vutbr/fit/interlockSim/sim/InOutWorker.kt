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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Path
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
	private val context: SimulationContext,
	private val inOut: InOut
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	private val queqe = Head()
	private var myIdle = true
	private val next: TrackSection? = context.getNextTrackSection(inOut, null)
	private var path: Path? = null // cesta k naskedujicimu semaforu - pokud existuje

	private val pathFree: Condition =
		object : Condition {
			override fun test(): Boolean {
				// GOAL 2 & GOAL 4: Path finding and interlocking validation
				// When automatic pathfinding is implemented (Goal 2), handle case where no valid path exists
				// Related to interlocking validation (Goal 4) - see LONG_TERM_GOALS.md
				// Local variable for smart cast since next is mutable property
				val nextLocal = next
				path = if (nextLocal != null) context.pathToNextSemaphore(inOut, nextLocal) else null
				return try {
					val pathExists = path != null
					val isFree = pathExists && path?.isFreeFrom(inOut) ?: false

					if (!pathExists) {
						logger.debug {
							"${Process.time()} APPROVAL_CHECK: InOut ${inOut.getName()} - path does not exist"
						}
					} else if (!isFree) {
						logger.debug {
							"${Process.time()} APPROVAL_CHECK: InOut ${inOut.getName()} - " +
								"path not free, length=${path?.length()}"
						}
					}
					isFree
				} catch (e: TrackOperationException) {
					logger.error {
						"${Process.time()} APPROVAL_ERROR: InOut ${inOut.getName()} - " +
							"path check failed: ${e.message}"
					}
					context.errorStop(e)
					false
				}
			}
		}

	override fun iteration() {
		while (!queqe.empty()) {
			myIdle = false
			logger.debug { "InOutWorker ${inOut.getName()} queue non-empty, processing train" }
			context.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS)
			waitUntil(pathFree)
			val first = queqe.first() as Link
			logger.debug { "InOutWorker ${inOut.getName()} path is now free, reserving for train" }

			try {
				// zarezervovat koleje
				path?.setUpPath(inOut)
				logger.info {
					"${Process.time()} APPROVAL_GRANTED: InOut ${inOut.getName()} - " +
						"path reserved for $first, length=${path?.length()}"
				}
			} catch (e: Exception) {
				logger.warn {
					"${Process.time()} APPROVAL_DENIED: InOut ${inOut.getName()} - " +
						"path setup failed for $first: ${e.message}"
				}
				logger.debug { "InOutWorker ${inOut.getName()} path setup failed: ${e.message}" }
				context.errorStop(e)
				return
			}
			context.report("Path reserved for $first", inOut, ReportType.NODE_EVENTS)

			// cekej na odchod vlaku z fronty
			logger.debug { "InOutWorker ${inOut.getName()} waiting for train $first to leave queue" }
			waitUntil(
				object : Condition {
					override fun test(): Boolean = first != queqe.first()
				}
			)
			logger.debug { "InOutWorker ${inOut.getName()} train left queue" }
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
		logger.debug { "InOutWorker ${inOut.getName()} entering train $train, queue empty: ${queqe.empty()}" }
		if (queqe.empty()) {
			train.into(queqe)
		} else {
			Process.wait(queqe)
		}

		if (myIdle) {
			logger.debug { "InOutWorker ${inOut.getName()} was idle, activating to process train $train" }
			Process.activate(this)
		}
	}
}
