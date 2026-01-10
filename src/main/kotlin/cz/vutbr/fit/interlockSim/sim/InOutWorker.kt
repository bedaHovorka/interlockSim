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
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import jDisco.Condition
import jDisco.Head
import jDisco.Link
import jDisco.Process
import io.github.oshai.kotlinlogging.KotlinLogging

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
				// EXTENSION az bude Interlocking: co kdyz cesta vubec neexistuje
				// Local variable for smart cast since next is mutable property
				val nextLocal = next
				path = if (nextLocal != null) context.pathToNextSemaphore(inOut, nextLocal) else null
				return try {
					val pathExists = path != null
					val isFree = pathExists && path?.isFreeFrom(inOut) ?: false

					if (logger.isDebugEnabled) {
						if (!pathExists) {
							logger.debug(
								"{} APPROVAL_CHECK: InOut {} - path does not exist",
								Process.time(),
								inOut.getName()
							)
						} else if (!isFree) {
							logger.debug(
								"{} APPROVAL_CHECK: InOut {} - path not free, length={}",
								Process.time(),
								inOut.getName(),
								path?.length()
							)
						}
					}
					isFree
				} catch (e: TrackOperationException) {
					logger.error(
						"{} APPROVAL_ERROR: InOut {} - path check failed: {}",
						Process.time(),
						inOut.getName(),
						e.message
					)
					context.errorStop(e)
					false
				}
			}
		}

	override fun iteration() {
		while (!queqe.empty()) {
			myIdle = false
			logger.debug("InOutWorker {} queue non-empty, processing train", inOut.getName())
			context.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS)
			waitUntil(pathFree)
			val first = queqe.first() as Link
			logger.debug("InOutWorker {} path is now free, reserving for train", inOut.getName())

			try {
				// zarezervovat koleje
				path?.setUpPath(inOut)
				if (logger.isInfoEnabled) {
					logger.info(
						"{} APPROVAL_GRANTED: InOut {} - path reserved for {}, length={}",
						Process.time(),
						inOut.getName(),
						first,
						path?.length()
					)
				}
			} catch (e: Exception) {
				if (logger.isWarnEnabled) {
					logger.warn(
						"{} APPROVAL_DENIED: InOut {} - path setup failed for {}: {}",
						Process.time(),
						inOut.getName(),
						first,
						e.message
					)
				}
				logger.debug("InOutWorker {} path setup failed: {}", inOut.getName(), e.message)
				context.errorStop(e)
				return
			}
			context.report("Path reserved for $first", inOut, ReportType.NODE_EVENTS)

			// cekej na odchod vlaku z fronty
			logger.debug("InOutWorker {} waiting for train {} to leave queue", inOut.getName(), first)
			waitUntil(
				object : Condition {
					override fun test(): Boolean = first != queqe.first()
				}
			)
			logger.debug("InOutWorker {} train left queue", inOut.getName())
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
		logger.debug("InOutWorker {} entering train {}, queue empty: {}", inOut.getName(), train, queqe.empty())
		if (queqe.empty()) {
			train.into(queqe)
		} else {
			Process.wait(queqe)
		}

		if (myIdle) {
			logger.debug("InOutWorker {} was idle, activating to process train {}", inOut.getName(), train)
			Process.activate(this)
		}
	}
}
