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
 * Goal 1 SP4: Three-train prototype scenario on the built-in shunting loop.
 *
 * This interlocking process demonstrates three trains sharing the `vyhybna.xml`
 * network. It is intentionally thin: it reuses the deterministic dispatcher and
 * full-path reservation machinery of [MultiTrainLoop] with a fixed, well-known
 * train specification that guarantees a contested shared block.
 *
 * ## Train specification
 *
 * - Train 1: A → B, enters at t = 0.0
 * - Train 2: B → A, enters at t = 1.0
 * - Train 3: A → B, enters at t = 2.0
 *
 * All three trains cross the central part of the shunting loop, so at least
 * one block is shared/queued during the run.
 *
 * ## Usage
 *
 * ```kotlin
 * val context = simulationContextFactory.createContext(vyhybnaXmlStream)
 * context.getInOuts()
 * context.setMainProcess(ThreeTrainLoop(context, endTime = 300L))
 * context.run()
 * ```
 *
 * @param context Simulation context (must be built from `vyhybna.xml`)
 * @param endTime Simulation wall-clock time at which the scenario stops
 * @param enableRealTimeSync When true, throttle simulation time to wall clock
 * @param initialSpeedMultiplier Initial speed multiplier for real-time sync
 *
 * @see MultiTrainLoop
 * @since Issue #584 (Goal 1 SP4)
 */
class ThreeTrainLoop(
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
		/** Fixed three-train specification used by the prototype. */
		internal val TRAIN_SPECS: List<MultiTrainLoop.TrainSpec> =
			listOf(
				MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 40.0),
				MultiTrainLoop.TrainSpec(inName = "B", outName = "A", inTime = 1.0, length = 40.0),
				MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 2.0, length = 40.0)
			)
	}
}
