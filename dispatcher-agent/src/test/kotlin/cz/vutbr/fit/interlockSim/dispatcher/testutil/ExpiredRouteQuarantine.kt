/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Reference consumer of [ReservationExpired] (Issue #1243): a [Dispatcher] decorator that stops
 * re-granting a train that does not move. Two strikes on the same footprint: the first expiry of a
 * train is forgiven (the re-grant is the recovery); a second expiry with the same [ReservationExpired.keptBlocks]
 * quarantines the train until its occupied set changes or it leaves the snapshot. An expiry on a
 * different footprint lifts the quarantine at once and counts as a first strike. Only the train's
 * [DispatchDecision.ReservePath] decisions are withheld; every other decision, `RequestRoute` included,
 * passes. Test-source only in #1243; the production adoption is a Goal 9B follow-up.
 *
 * The occupied set is read from the complete `snapshot.blocks` readings -- every OCCUPIED block the
 * train owns, switch blocks included, under the same [BlockIdentity.stableBlockId] as
 * [ReservationExpired.keptBlocks] -- not from the block inputs, which a `ShuntingLoop` publishes only
 * for its platform tracks and entry/exit blocks. An observation with trains but no block readings
 * fails fast: the asynchronous `DispatchObservation.from` adapter does not carry them yet (Issue #1249).
 *
 * [onReservationExpired] runs on the simulation thread, [decide] on the dispatcher's thread (the same
 * thread in the synchronous wiring, the driver thread under
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]); hence the concurrent maps, as in
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.ConflictHintLatch].
 */
class ExpiredRouteQuarantine(
	private val inner: Dispatcher
) : Dispatcher {
	/** Per train, the ids of [ReservationExpired.keptBlocks] at its most recent expiry. */
	private val lastExpiryFootprint: ConcurrentHashMap<String, Set<String>> = ConcurrentHashMap()

	/** Trains whose last two expiries had the same footprint. */
	private val quarantined: MutableSet<String> = ConcurrentHashMap.newKeySet()

	/** Register through `environment.onReservationExpired(quarantine::onReservationExpired)` before `run()`. */
	fun onReservationExpired(event: ReservationExpired) {
		val footprint = event.keptBlocks.map(BlockIdentity::stableBlockId).toSet()
		if (lastExpiryFootprint.put(event.trainId, footprint) != footprint) {
			// A new footprint: the train moved since its last expiry, so this one is a first strike.
			quarantined.remove(event.trainId)
			return
		}
		if (quarantined.add(event.trainId)) {
			logger.info {
				"Train '${event.trainId}' is not offered a route: its reservation expired twice while it stood on " +
					"${footprint.sorted()} (Issue #1243)"
			}
		}
	}

	override fun decide(observed: DispatchObservation): List<DispatchDecision> {
		forgetTrainsThatMovedOrLeft(observed)
		return inner
			.decide(observed)
			.filterNot { it is DispatchDecision.ReservePath && it.trainId in quarantined }
			.ifEmpty { listOf(DispatchDecision.NoAction) }
	}

	private fun forgetTrainsThatMovedOrLeft(observed: DispatchObservation) {
		val snapshot = observed.snapshot
		check(snapshot.trainPositions.isEmpty() || snapshot.blocks.isNotEmpty()) {
			"ExpiredRouteQuarantine needs the snapshot's block readings, but this observation has trains and " +
				"no blocks: the asynchronous DispatchObservation.from adapter does not carry them yet (Issue #1249)"
		}
		val present = snapshot.trainPositions.mapTo(HashSet()) { it.trainId }
		lastExpiryFootprint.entries.removeIf { (trainId, footprint) ->
			trainId !in present || occupiedBlockIds(observed, trainId) != footprint
		}
		quarantined.retainAll(lastExpiryFootprint.keys)
	}

	/** The blocks [trainId] stands on as the snapshot reads them: every OCCUPIED block it owns. */
	private fun occupiedBlockIds(
		observed: DispatchObservation,
		trainId: String
	): Set<String> =
		observed.snapshot.blocks
			.filter { it.state == TrackFacility.State.OCCUPIED && it.trainId == trainId }
			.mapTo(HashSet()) { it.blockId }
}
