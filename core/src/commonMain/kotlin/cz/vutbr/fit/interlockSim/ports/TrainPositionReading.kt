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
 * @property length Physical train length in **metres** — the value
 *   [cz.vutbr.fit.interlockSim.sim.MultiTrainLoop.getTrainSnapshot] reads live, published here so an
 *   off-thread reader needs no live read (Issue #1472). `0.0` in a reading not sampled from a live
 *   train (hand-built test readings and projections).
 * @property state The train's [TrainMotionState] at the same instant; `null` in a reading not
 *   sampled from a live train.
 *
 * ## Threading (Issue #1472)
 *
 * The readings in [SimulationSnapshot.trainPositions] are the train sample of the planning
 * snapshot: [DefaultNetworkPerceptionPort.captureSnapshot] takes them on the simulation thread in the
 * same control step as the rest of the snapshot, and `ShuntingLoop` publishes them inside its one
 * `@Volatile` tick record, read as [DispatchLoopSnapshot] (#1467 spec rules P1, P2). An off-thread
 * reader reads that published value only, never a live `Train`, so it cannot see one train from one
 * step and another from the next. A train's held routes are not a field of this reading: they belong
 * to the snapshot's route facet (#1469), filtered by [trainId].
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 * @since Issue #1472 — [length] and [state]; the threading rule above
 */
data class TrainPositionReading(
	val trainId: String,
	val velocity: Double,
	val acceleration: Double,
	val totalDistance: Double,
	val frontSectionName: String?,
	val length: Double = 0.0,
	val state: TrainMotionState? = null
)
