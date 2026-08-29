/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared scenario chain for SimpleLinearTrackTestProcess.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.SimpleLinearTrackTestProcess
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * One run of the shared [SimpleLinearTrackTestProcess] scenario chain: the built process and
 * every train it created, in creation order.
 */
class SimpleLinearTrackRun(
	val process: SimpleLinearTrackTestProcess,
	val trains: List<Train>
) {
	/** The single train of a one-train scenario; fails fast on a multi-train spec list. */
	val train: Train
		get() = trains.single()
}

/**
 * Runs the scenario chain every [SimpleLinearTrackTestProcess] test repeats: build the
 * process, make it the context's main process, run the context to its end time, and collect
 * the trains it created.
 *
 * The process itself reserves nothing — a scenario that needs a reserved route does it in
 * [onTrainCreated], exactly as the process's KDoc prescribes. Context lifetime stays with the
 * caller: register it with `KoinTestBase.tracked()` or close it in an `@AfterEach`, as before.
 */
fun runSimpleLinearTrackScenario(
	context: DefaultSimulationContext,
	endTime: Long,
	trainSpecs: List<SimpleLinearTrackTestProcess.TrainSpec>,
	onTrainCreated: (Train) -> Unit = {}
): SimpleLinearTrackRun {
	val trains = mutableListOf<Train>()
	val process =
		SimpleLinearTrackTestProcess(
			context,
			endTime = endTime,
			trainSpecs = trainSpecs,
			onTrainCreated = { train ->
				trains += train
				onTrainCreated(train)
			}
		)
	context.setMainProcess(process)
	context.run()
	return SimpleLinearTrackRun(process = process, trains = trains.toList())
}
