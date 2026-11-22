/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): a MultiTrainLoop that is told when reservations are released.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired

/**
 * A [MultiTrainLoop] plus a scripted process that announces a [ReservationExpired] at each of
 * [releaseTimes] (simulation seconds, ascending).
 *
 * With a fake reservation service that refuses every request, the announced releases are the only
 * thing that can wake the blocked trains, so a test controls exactly when the loop retries.
 * [ReservationExpired] needs no block, so the announcement works on any topology.
 */
internal class ReleasingMultiTrainLoop(
	context: SimulationContext,
	endTime: Long,
	trainSpecs: List<MultiTrainLoop.TrainSpec>,
	pathReservationService: PathReservationService,
	maxEntryAttempts: Int,
	private val releaseTimes: List<Double>,
	maxConcurrentTrains: Int = MultiTrainLoop.DEFAULT_MAX_CONCURRENT_TRAINS
) : MultiTrainLoop(
		context,
		endTime = endTime,
		trainSpecs = trainSpecs,
		maxConcurrentTrains = maxConcurrentTrains,
		pathReservationService = pathReservationService,
		maxEntryAttempts = maxEntryAttempts
	) {
	override suspend fun startAction() {
		super.startAction()
		Process.activate(ReleaseAnnouncer())
	}

	private inner class ReleaseAnnouncer : Process() {
		override suspend fun actions() {
			var last = 0.0
			for (at in releaseTimes) {
				hold(at - last)
				last = at
				emitCustom(
					ReservationExpired(
						trainId = "scripted",
						kind = ReservationExpired.Kind.WHOLE_HOLDING,
						releasedBlocks = emptyList(),
						keptBlocks = emptyList(),
						stalledForSimSeconds = 0.0,
						ttlSimSeconds = 0.0,
						time = at
					)
				)
			}
		}
	}

	companion object {
		/** Releases every [period] seconds from [period] up to, and excluding, [endTime]. */
		fun every(
			period: Double,
			endTime: Long
		): List<Double> = generateSequence(period) { it + period }.takeWhile { it < endTime }.toList()
	}
}
