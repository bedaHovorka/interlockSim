/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

/**
 * Immutable kinematic snapshot of a single train, used by the predictive
 * time-to-collision (TTC) monitor in [DefaultCollisionDetectionService].
 *
 * All fields are captured at a single simulation instant; the snapshot is
 * not updated as the simulation advances.
 *
 * **Threading (Issue #1028):** an immutable value, safe to hand to any thread once built. In
 * production it is built on the kDisco simulation thread by
 * [cz.vutbr.fit.interlockSim.sim.MultiTrainLoop.getTrainSnapshot], called from
 * [DefaultCollisionDetectionService]'s predictive TTC evaluation on block events emitted
 * through kDisco `emitCustom`, so its fields belong to one simulation instant. A snapshot built
 * off the simulation thread (tests only) reads the continuous values live and is a
 * stale-tolerant estimate instead.
 *
 * @property trainId   Unique train identifier (matches registry keys).
 * @property velocity  Current velocity in m/s (≥ 0.0).
 * @property totalDistance Total distance traveled by the train's front since
 *   departure, in metres (≥ 0.0). Used to determine which train is "ahead".
 * @property length    Physical train length in metres (> 0.0). Used to compute
 *   the gap between the trailing train's front and the leading train's tail.
 * @since Issue #614 (Goal 3 SP4)
 */
data class TrainSnapshot(
	val trainId: String,
	val velocity: Double,
	val totalDistance: Double,
	val length: Double
)
