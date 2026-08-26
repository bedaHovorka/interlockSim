/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: TrainPerceptionReading factory
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading

/**
 * Builds a [TrainPerceptionReading] with defaults, overriding only what the decision under test
 * depends on.
 *
 * `AlgorithmicTrainDecisionPolicyTest` and `ReactiveTrainDeciderTest` each carried a private
 * `reading(...)` factory with the same defaults, the second one simply a superset of the first
 * (Issue #955, cluster C5). This is that superset.
 *
 * The names are deliberately fixed and meaningless — `Train #1`, `sA`, `block-1`, `B`. A decision
 * policy reads aspects, distances and speeds, never names, so a test that cares about a name is
 * testing something else and should build its reading directly.
 */
@Suppress("LongParameterList")
fun trainPerceptionReading(
	signalAhead: Signal?,
	nextSignalAhead: Signal? = null,
	distanceToSignal: Double = 100.0,
	speedLimit: Double = 30.0,
	velocity: Double = 0.0
): TrainPerceptionReading =
	TrainPerceptionReading(
		trainId = "Train #1",
		signalAheadName = signalAhead?.let { "sA" },
		signalAheadAspect = signalAhead,
		distanceToSignalAheadMetres = distanceToSignal,
		currentSpeedLimitMps = speedLimit,
		velocity = velocity,
		acceleration = 0.0,
		totalDistance = 0.0,
		frontSectionName = "block-1",
		destinationInOutName = "B",
		scheduledArrivalTime = 0.0,
		isDwelling = velocity == 0.0,
		nextSignalAheadName = nextSignalAhead?.let { "sB" },
		nextSignalAheadAspect = nextSignalAhead
	)
