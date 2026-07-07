/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

/**
 * Immutable snapshot of a train's timetable at a moment in simulation time.
 *
 * Produced by [NetworkPerceptionPort.trainTimetable] and [NetworkPerceptionPort.allTrainTimetables].
 * The snapshot reflects the timetable state **at the instant it is read**; for trains
 * that have reversed direction (bidirectional operation), [originInOutName] and
 * [destinationInOutName] reflect the **current** direction, not the original assignment.
 *
 * @property trainId Train identifier (e.g. `"Train #1"`).
 * @property originInOutName Name of the InOut (entry/exit point) from which this train
 *   departs on its current leg of the journey (e.g. `"A"`, `"B"`).
 * @property destinationInOutName Name of the InOut at which this train aims to arrive
 *   on its current leg of the journey.
 * @property scheduledDepartureTime Simulation time (in seconds) at which the train is
 *   scheduled to depart from [originInOutName].  Zero if unscheduled.
 * @property scheduledArrivalTime Simulation time (in seconds) at which the train is
 *   scheduled to arrive at [destinationInOutName].  Zero if unscheduled.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
data class TimetableReading(
	val trainId: String,
	val originInOutName: String,
	val destinationInOutName: String,
	val scheduledDepartureTime: Double,
	val scheduledArrivalTime: Double
)
