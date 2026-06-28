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

/**
 * Goal 1 SP5: deterministic two-train concurrency validation scenario.
 *
 * This interlocking process demonstrates the foundational multi-train behaviour that
 * the Goal 1 verification plan requires:
 * - **Concurrent execution**: two trains are generated and approved by the same
 *   deterministic dispatcher ([MultiTrainLoop]).
 * - **Same-step arrivals**: both trains are injected at simulation time `0.0`, so the
 *   dispatcher must handle two simultaneous requests.
 * - **Tail-to-head following**: both trains travel the same A → B route. Because
 *   [MultiTrainLoop] reserves the full entry-to-exit path before starting a train,
 *   the second train can only begin after the first train has cleared the route,
 *   producing a safe head-follows-tail sequence.
 * - **Block handover**: as the first train's tail leaves each block, the reservation
 *   is released and can be reused by the following train.
 *
 * The fixed specification uses a short train length (20 m) on the standard
 * `linearPathWithSemaphore` topology so that two trains fit safely on the shared
 * blocks while still exercising queuing.
 *
 * @param context Simulation context (must contain InOuts named `A` and `B`)
 * @param endTime Simulation wall-clock time at which the scenario stops
 * @param enableRealTimeSync When true, throttle simulation time to wall clock
 * @param initialSpeedMultiplier Initial speed multiplier for real-time sync
 *
 * @see MultiTrainLoop
 * @see ThreeTrainLoop
 * @since Issue #587 (Goal 1 SP5)
 */
class TwoTrainLoop(
	context: SimulationContext,
	endTime: Long,
	enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0
) : MultiTrainLoop(
		context = context,
		endTime = endTime,
		trainSpecs = TRAIN_SPECS,
		enableRealTimeSync = enableRealTimeSync,
		initialSpeedMultiplier = initialSpeedMultiplier
	) {
	companion object {
		/** Fixed two-train specification used by the concurrency validation scenario. */
		internal val TRAIN_SPECS: List<MultiTrainLoop.TrainSpec> =
			listOf(
				MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
				MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 1.0, length = 20.0)
			)
	}
}
