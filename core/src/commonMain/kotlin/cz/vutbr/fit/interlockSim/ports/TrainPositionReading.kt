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
 * Immutable snapshot of a train's kinematics at a moment in simulation time.
 *
 * Produced by [NetworkPerceptionPort.trainPosition] and [NetworkPerceptionPort.allTrainPositions].
 * The snapshot captures position and motion state at the instant it is read; it is not
 * updated as the simulation advances.
 *
 * ## Coordinate System
 *
 * - [velocity] and [acceleration] are physics-model values in SI units (m/s, m/s²).
 * - [totalDistance] is the odometer reading for the train's **front**; it accumulates
 *   across successive track blocks since departure from the origin InOut.
 * - [frontSectionName] identifies the block the train's front currently occupies,
 *   or `null` if the train has not yet entered a block (e.g. just approved but
 *   the path reservation race is still in progress).
 *
 * @property trainId Train identifier (e.g. `"Train #1"`).
 * @property velocity Current speed of the train's front in **m/s** (≥ 0.0).
 * @property acceleration Current acceleration in **m/s²** (positive = accelerating,
 *   negative = braking, 0.0 = coasting or stopped).
 * @property totalDistance Total distance traveled by the train's front since departure
 *   from the origin InOut, in **metres** (≥ 0.0).
 * @property frontSectionName Name of the track block currently containing the train's
 *   front (derived from block name or endpoint separator names), or `null` if not yet
 *   entered.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
data class TrainPositionReading(
	val trainId: String,
	val velocity: Double,
	val acceleration: Double,
	val totalDistance: Double,
	val frontSectionName: String?,
)
