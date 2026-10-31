/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1271): a MultiTrainLoop with a scripted state-change process.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * A [MultiTrainLoop] plus a scripted process that runs each of [steps] at its simulation time
 * (seconds, ascending), on the simulation thread.
 *
 * The release-path audit of Issue #1271 uses it to change the reservation state at a known instant
 * -- a holder's Tail clears a block, the holder leaves the network, a route is cancelled -- and then
 * checks that the waiting train is retried exactly at that instant. Unlike [ReleasingMultiTrainLoop]
 * it announces nothing itself: whatever event reaches the retry comes from the production path the
 * step calls.
 */
internal class ScriptedMultiTrainLoop(
	context: SimulationContext,
	endTime: Long,
	trainSpecs: List<MultiTrainLoop.TrainSpec>,
	pathReservationService: PathReservationService,
	private val steps: List<Pair<Double, () -> Unit>>
) : MultiTrainLoop(
		context,
		endTime = endTime,
		trainSpecs = trainSpecs,
		pathReservationService = pathReservationService
	) {
	override suspend fun startAction() {
		super.startAction()
		Process.activate(Script())
	}

	private inner class Script : Process() {
		override suspend fun actions() {
			var last = 0.0
			for ((at, step) in steps) {
				hold(at - last)
				last = at
				step()
			}
		}
	}
}

/**
 * A train body that stands still: the occupant a scripted step puts on a block. Its progress reading
 * never changes, so the reservation expiry sees it make no progress.
 */
internal class StandingOccupant(
	override val name: String
) : TrackOccupant {
	override fun distanceToSemaphore(): Double = 0.0

	override fun nextSemaphore(): OrientedPathSeparator? = null
}

/**
 * What `Train.Tail.separatorAction` does when the tail leaves [block]: the body enters and leaves it
 * (`DynamicTrackBlock.leave` makes it FREE), then the train asks to unregister it.
 */
internal fun SimulationContext.tailClears(
	occupant: TrackOccupant,
	block: DynamicTrackBlock
) {
	block.enter(occupant)
	block.leave(occupant)
	unregisterBlock(occupant.name, block)
}
