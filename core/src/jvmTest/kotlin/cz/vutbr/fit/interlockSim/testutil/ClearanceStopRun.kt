/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared scenario chain for the Issue #989 clearance-stop tests.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.SimpleLinearTrackTestProcess
import cz.vutbr.fit.interlockSim.sim.Train

/**
 * The canonical `A → B` spec every linear-track scenario starts from: enter at [inTime],
 * leave at [outTime], [length] metres of train.
 */
fun trainSpecAB(
	outTime: Double,
	inTime: Double = 1.0,
	length: Double = 20.0
): SimpleLinearTrackTestProcess.TrainSpec =
	SimpleLinearTrackTestProcess.TrainSpec(
		inName = "A",
		outName = "B",
		inTime = inTime,
		outTime = outTime,
		length = length
	)

/**
 * Everything a clearance-stop scenario produces for assertion after the run: the single train,
 * its samples in time order, and the process that coordinated it.
 */
class ClearanceStopRun(
	val train: Train,
	val samples: List<TrainKinematicSample>,
	val process: SimpleLinearTrackTestProcess
)

/**
 * Runs the scenario chain every Issue #989 clearance-stop test repeats: one train `A → B`
 * over [context] with the route reserved (or reserved only up to the first semaphore, for the
 * no-onward-route scenarios), every [semaphore] forced to [initialAspect] — the reservation
 * would otherwise light them — and a [TrainKinematicSampler] feeding [onSample] for the whole
 * run.
 *
 * Context lifetime stays with the caller: register it with `KoinTestBase.tracked()` — that
 * extension is protected, so this runner cannot own it — or close it in an `@AfterEach`,
 * exactly like [runSimpleLinearTrackScenario].
 */
fun runClearanceStopScenario(
	context: DefaultSimulationContext,
	semaphores: List<DynamicRailSemaphore>,
	endTime: Long,
	initialAspect: Signal = Signal.STOP,
	trainLength: Double = 20.0,
	samplePeriod: Double = 0.05,
	reserveOnlyToSemaphore: Boolean = false,
	onReserved: (List<DynamicTrackBlock>) -> Unit = {},
	onSample: (Train, TrainKinematicSample) -> Unit = { _, _ -> }
): ClearanceStopRun {
	val inOuts = context.getInOuts().toList()
	val a = inOuts.single { it.name == "A" }
	val b = inOuts.single { it.name == "B" }
	val target = if (reserveOnlyToSemaphore) semaphores.first() else b
	val reservationService = context.getRoutingServices().getPathReservationService()
	val samples = mutableListOf<TrainKinematicSample>()
	val run =
		runSimpleLinearTrackScenario(
			context,
			endTime = endTime,
			trainSpecs = listOf(trainSpecAB(outTime = endTime.toDouble(), length = trainLength))
		) { train ->
			val reserved =
				assertReservationSuccess(reservationService.reservePath(train.name, a, target))
			onReserved(reserved.reservedBlocks)
			semaphores.forEach { it.signal = initialAspect }
			Process.activate(
				TrainKinematicSampler(train, endTime.toDouble(), samplePeriod) { sample ->
					samples += sample
					onSample(train, sample)
				}
			)
		}
	return ClearanceStopRun(train = run.train, samples = samples.toList(), process = run.process)
}
