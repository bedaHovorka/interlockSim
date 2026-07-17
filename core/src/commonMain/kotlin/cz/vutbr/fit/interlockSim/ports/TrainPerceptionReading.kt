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

import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Immutable first-person perception snapshot of a single train at one simulation instant.
 *
 * Produced by [NetworkPerceptionPort.trainPerception] and
 * [NetworkPerceptionPort.allTrainPerceptions] on the kDisco simulation thread, and
 * by [SnapshotProjectionNetworkPerceptionPort] off-thread from the most recent
 * [SimulationSnapshot.trainPerceptions].
 *
 * This reading bundles all information a reactive train agent (SP2a, Issue #537) needs
 * for its sense → decide → act loop:
 *
 * | Perception facet | Fields |
 * |---|---|
 * | Signal ahead  | [signalAheadName], [signalAheadAspect], [distanceToSignalAheadMetres] |
 * | Speed limit   | [currentSpeedLimitMps] |
 * | Own kinematics | [velocity], [acceleration], [totalDistance], [frontSectionName] |
 * | Next timetable event | [destinationInOutName], [scheduledArrivalTime] |
 * | Dwell state   | [isDwelling] |
 *
 * ## Signal-ahead semantics
 *
 * [signalAheadAspect] reflects the signal at the **next semaphore** along the reserved
 * path, as seen by the train's [cz.vutbr.fit.interlockSim.sim.Train.nextSemaphore]
 * method. Both fields are `null` when no path is currently reserved for the train
 * (e.g. the train is still queued at an InOut waiting for the interlocking to set a route).
 *
 * ## Speed-limit semantics
 *
 * [currentSpeedLimitMps] is the physical track-section speed limit of the reserved path
 * from the train's current position onwards, computed via [cz.vutbr.fit.interlockSim.objects.paths.Path.maxSpeed].
 * It is independent of the signal aspect: even if the signal is FREE, the track limit may
 * be lower. When no path is reserved, it falls back to
 * [cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED] (no known constraint).
 *
 * ## Dwell semantics
 *
 * [isDwelling] is `true` when the train is not moving (velocity = 0). This covers both
 * *waiting at a STOP signal* and *holding at a station between direction reversals*.
 * The reactive agent's decision step (SP2a.2) can distinguish these cases by inspecting
 * [signalAheadAspect]: if aspect is [Signal.STOP] the train is blocked by a signal; if
 * aspect is allowing (or null) the train may be at a station dwell.
 *
 * @property trainId Train identifier (e.g. `"Train #1"`).
 * @property signalAheadName Name of the next semaphore ahead, or `null` if no path set.
 * @property signalAheadAspect [Signal] aspect of the next semaphore, or `null` if no path.
 * @property distanceToSignalAheadMetres Distance from the train's front to the next
 *   semaphore in metres (≥ 0). Zero when no path is reserved.
 * @property currentSpeedLimitMps Maximum allowed speed from the reserved path in **m/s**.
 *   Reflects physical track constraints, independent of signal aspect. Falls back to
 *   [cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED] when no path is set.
 * @property velocity Current speed of the train's front in **m/s** (≥ 0).
 * @property acceleration Current acceleration in **m/s²** (positive = accelerating,
 *   negative = braking).
 * @property totalDistance Total distance traveled by the train's front since departure
 *   from the origin InOut, in **metres** (≥ 0).
 * @property frontSectionName Name of the track block currently containing the train's
 *   front, or `null` if not yet entered.
 * @property destinationInOutName Name of the InOut at which this train aims to arrive on
 *   its current leg of the journey (e.g. `"A"`, `"B"`).
 * @property scheduledArrivalTime Simulation time (in seconds) at which the train is
 *   scheduled to arrive at [destinationInOutName]. Zero if unscheduled.
 * @property isDwelling `true` when the train is stopped (velocity = 0).
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 */
data class TrainPerceptionReading(
	val trainId: String,
	val signalAheadName: String?,
	val signalAheadAspect: Signal?,
	val distanceToSignalAheadMetres: Double,
	val currentSpeedLimitMps: Double,
	val velocity: Double,
	val acceleration: Double,
	val totalDistance: Double,
	val frontSectionName: String?,
	val destinationInOutName: String,
	val scheduledArrivalTime: Double,
	val isDwelling: Boolean
)
