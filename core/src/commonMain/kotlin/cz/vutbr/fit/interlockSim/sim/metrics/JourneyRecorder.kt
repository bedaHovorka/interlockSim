/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Builds one [TrainJourneyRecord] per train journey from the [TrainJourneyEvent] stream
 * (Issue #673, Goal 6 SP2).
 *
 * A journey is **open** from its first event until its [TrainJourneyEvent.Exited]. Each event
 * updates the train's open journey, or opens a new one when the step does not fit:
 *
 * - [TrainJourneyEvent.Queued] always opens a new journey (a train queued twice journeys twice).
 * - [TrainJourneyEvent.Admitted] fills the open journey if it has not been admitted yet, otherwise
 *   opens a new journey with unknown entry — the case of a scenario that emits no queue step.
 * - [TrainJourneyEvent.Exited] closes the open journey, or records a journey of its own when none
 *   is open.
 *
 * So the same train id journeying again always gets a new record. Records keep the order their
 * journeys were opened in. There is no cap: one small record per journey, not per event.
 *
 * Not thread-safe: fed on the simulation thread; read there or after the run.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
internal class JourneyRecorder {
	private val records = mutableListOf<TrainJourneyRecord>()

	/** Index into [records] of each train's open journey. */
	private val openJourneyIndex = mutableMapOf<String, Int>()

	/**
	 * Apply one journey step.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	fun record(event: TrainJourneyEvent) {
		when (event) {
			is TrainJourneyEvent.Queued -> onQueued(event)
			is TrainJourneyEvent.Admitted -> onAdmitted(event)
			is TrainJourneyEvent.Exited -> onExited(event)
		}
	}

	/**
	 * Every journey recorded so far, open or closed, in the order opened; a copy.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	fun records(): List<TrainJourneyRecord> = records.toList()

	private fun onQueued(event: TrainJourneyEvent.Queued) {
		open(
			TrainJourneyRecord(
				trainId = event.trainId,
				entryInOut = event.entryInOut,
				entryTime = event.time,
				plannedExit = event.plannedExit
			)
		)
	}

	private fun onAdmitted(event: TrainJourneyEvent.Admitted) {
		val index = openJourneyIndex[event.trainId]
		if (index != null && records[index].admissionTime == null) {
			records[index] = records[index].copy(admissionTime = event.time)
		} else {
			open(TrainJourneyRecord(trainId = event.trainId, admissionTime = event.time))
		}
	}

	private fun onExited(event: TrainJourneyEvent.Exited) {
		val index = openJourneyIndex.remove(event.trainId)
		if (index == null) {
			logger.debug { "Train ${event.trainId} exited with no open journey; recording the exit alone" }
			records +=
				TrainJourneyRecord(trainId = event.trainId, actualExit = event.actualExitInOut, exitTime = event.time)
			return
		}
		records[index] = records[index].copy(actualExit = event.actualExitInOut, exitTime = event.time)
	}

	private fun open(record: TrainJourneyRecord) {
		openJourneyIndex[record.trainId] = records.size
		records += record
	}

	private companion object {
		private val logger = KotlinLogging.logger {}
	}
}
