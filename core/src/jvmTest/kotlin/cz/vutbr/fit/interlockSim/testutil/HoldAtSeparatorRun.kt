/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Shared scenario chain for the tests that hold a train at a separator with an ownership conflict.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.SimpleLinearTrackTestProcess
import cz.vutbr.fit.interlockSim.sim.Train

/** Simulation end time; the journey with the stand takes about 30 s. */
const val HOLD_AT_SEPARATOR_END_TIME = 90L

/** Train length; short enough to stand at the held separator with its tail clear of the origin. */
const val HOLD_AT_SEPARATOR_TRAIN_LENGTH = 20.0

/** Sampling period of the kinematic sampler that also drives the scenario. */
const val HOLD_AT_SEPARATOR_SAMPLE_PERIOD = 0.05

/** Held long enough for the wait to have settled before the reading is taken. */
const val HOLD_AT_SEPARATOR_STAND_HOLD_SECONDS = 2.0

/**
 * One observation of a hold-at-separator run, handed to the callbacks of
 * [runHoldAtSeparatorScenario] on the simulation thread — the only thread allowed to read
 * [train] or to take a reading from [port].
 *
 * [standing] is `true` from the sample the stand was detected at onwards, so a callback can tell
 * the approach apart from the wait without keeping its own stand time.
 */
class HoldAtSeparatorObservation(
	val train: Train,
	val port: NetworkPerceptionPort,
	val sample: TrainKinematicSample,
	val standing: Boolean
)

/**
 * Runs the scenario chain the hold-at-separator tests repeat: one train [inName] → [outName]
 * over [context] with its whole route reserved, navigation answering every query at the
 * separator named [holdSignal] with a [PathResult.OwnershipConflict] while [holding] says so,
 * and a [TrainKinematicSampler] driving the run at [samplePeriod].
 *
 * The callbacks run on the simulation thread, in this order per sample:
 *
 * - [onSample] — every sample, for windows measured while the train still runs;
 * - [onStand] — the first sample with a zero velocity past [standThreshold] metres, which tells
 *   the stand at the held separator apart from the stand at the origin before admission;
 * - [onHoldElapsed] — exactly once, [standHoldSeconds] after the stand, by when every wake-up of
 *   the stop has been delivered and settled; this is where a reading is taken, or the hold lifted
 *   by flipping whatever [holding] reads.
 *
 * Context lifetime stays with the caller: register it with `KoinTestBase.tracked()` — that
 * extension is protected, so this runner cannot own it — exactly like [runClearanceStopScenario].
 */
fun runHoldAtSeparatorScenario(
	context: DefaultSimulationContext,
	holdSignal: String,
	standThreshold: Double,
	endTime: Long = HOLD_AT_SEPARATOR_END_TIME,
	inName: String = "B",
	outName: String = "A",
	trainLength: Double = HOLD_AT_SEPARATOR_TRAIN_LENGTH,
	samplePeriod: Double = HOLD_AT_SEPARATOR_SAMPLE_PERIOD,
	standHoldSeconds: Double = HOLD_AT_SEPARATOR_STAND_HOLD_SECONDS,
	holding: () -> Boolean = { true },
	onSample: (HoldAtSeparatorObservation) -> Unit = {},
	onStand: (HoldAtSeparatorObservation) -> Unit = {},
	onHoldElapsed: (HoldAtSeparatorObservation) -> Unit = {}
): SimpleLinearTrackRun {
	val inOuts = context.getInOuts().toList()
	val origin = inOuts.single { it.name == inName }
	val destination = inOuts.single { it.name == outName }
	val reservationService = context.getRoutingServices().getPathReservationService()
	val realNav = context.getRoutingServices().getTrainNavigationService()
	val holdingNav =
		decoratingTrainNavigationService(realNav) { trainId, separator ->
			if (holding() && separatorLabel(separator) == holdSignal) {
				PathResult.OwnershipConflict
			} else {
				realNav.findReservedPathForTrain(trainId, separator)
			}
		}

	var standTime = -1.0
	var holdElapsed = false

	return runSimpleLinearTrackScenario(
		context,
		endTime = endTime,
		trainSpecs =
			listOf(
				SimpleLinearTrackTestProcess.TrainSpec(
					inName = inName,
					outName = outName,
					inTime = 1.0,
					outTime = endTime.toDouble(),
					length = trainLength
				)
			),
		env = NavigationDecoratingContext(context, holdingNav)
	) { train ->
		assertReservationSuccess(reservationService.reservePath(train.name, origin, destination))
		val port = DefaultNetworkPerceptionPort(context, activeTrains = { listOf(train) })
		Process.activate(
			TrainKinematicSampler(train, endTime.toDouble(), samplePeriod) { sample ->
				val standsNow = standTime < 0.0 && sample.velocity == 0.0 && sample.totalDistance > standThreshold
				if (standsNow) {
					standTime = sample.time
				}
				val observation =
					HoldAtSeparatorObservation(train, port, sample, standing = standTime >= 0.0)
				onSample(observation)
				if (standsNow) {
					onStand(observation)
				}
				if (!holdElapsed && standTime >= 0.0 && sample.time >= standTime + standHoldSeconds) {
					holdElapsed = true
					onHoldElapsed(observation)
				}
			}
		)
	}
}
