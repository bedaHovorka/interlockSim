/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlin.math.abs

/**
 * The stall judgement of reservation expiry phase 2 (Issue #1220, traffic-simulation-expert ruling
 * D1–D3 on #1220): which trains standing on part of their holding have made no progress for the TTL.
 * Split out of [DefaultReservationExpiry], which keeps the release and its logging and asks this
 * class only for [stalled] trains.
 *
 * For every train [PathReservationRegistry.occupyingHoldings] reports, it keeps a [ProgressSnapshot]:
 * the blocks the train stands on, its un-entered RESERVED tail and the occupant's
 * [cz.vutbr.fit.interlockSim.objects.core.TrackOccupant.distanceToSemaphore] reading. The reading is
 * a change detector, not a distance: it moves while the train moves inside a block, jumps at block
 * boundaries and stays constant while the train stands. The stall clock restarts whenever any of the
 * three changes, the reading by more than [PROGRESS_THRESHOLD_METERS] (an asymptotic approach,
 * Issue #797, creeps by less), so a new registration restarts it too. A train with no readable
 * occupant of its own is not judged on that call; a train with no tail (the #943 waiter at a STOP
 * signal, which has consumed its leg) is not judged either and keeps no clock. The snapshot of a
 * train that leaves the set is dropped.
 *
 * The state lives here, never in the registry (ruling D3).
 *
 * **Thread safety: none, like [DefaultReservationExpiry]** — runs on the simulation thread.
 *
 * @param registry The registry whose [PathReservationRegistry.occupyingHoldings] lists the trains to judge
 * @param ttlSimSeconds How long, in simulated seconds, a train must have made no progress to be stalled
 * @param simTime The simulation clock; the kDisco clock in production, a hand-moved one in a unit test
 * @since Issue #1220
 */
internal class TailStallTracker(
	private val registry: PathReservationRegistry,
	private val ttlSimSeconds: Double,
	private val simTime: () -> Double = { currentSimulationTime() }
) {
	companion object {
		/**
		 * A change of the progress reading above this many metres is progress (Issue #1220, ruling
		 * D2): a train that advances less than 1 cm within the TTL has stopped advancing.
		 */
		internal const val PROGRESS_THRESHOLD_METERS: Double = 0.01
	}

	/**
	 * One occupying train as phase 2 saw it when its stall clock last started (Issue #1220, ruling
	 * D1): the blocks it stands on, its un-entered RESERVED tail and its progress reading, read at
	 * [sinceSimTime]. Later calls compare against this snapshot, not against the previous call's, so
	 * the reading threshold is cumulative.
	 */
	internal data class ProgressSnapshot(
		val occupied: Set<DynamicTrackBlock>,
		val reserved: Set<DynamicTrackBlock>,
		val reading: Double,
		val sinceSimTime: Double
	) {
		/**
		 * Whether [current] shows progress over this snapshot: either block set changed, or the
		 * reading moved by more than [PROGRESS_THRESHOLD_METERS] from this snapshot's reading.
		 */
		fun isProgressIn(current: ProgressSnapshot): Boolean =
			occupied != current.occupied ||
				reserved != current.reserved ||
				abs(current.reading - reading) > PROGRESS_THRESHOLD_METERS
	}

	/** A train [stalled] has found stalled for at least the TTL, with the snapshot its clock started at. */
	internal class StalledTail(
		val holding: OccupyingHolding,
		val snapshot: ProgressSnapshot,
		val stalledForSimSeconds: Double
	)

	/**
	 * Per occupying train with a tail, the snapshot its stall clock started at (Issue #1220, ruling
	 * D3): replaced only when a call sees progress against it (or, after the expiry's own partial
	 * release, by [keepClock] with the remaining tail and the same clock), never refreshed on a call
	 * without progress.
	 */
	private val progress = mutableMapOf<String, ProgressSnapshot>()

	/**
	 * Take every occupying train's snapshot, restart the clock of each that progressed, and return
	 * those stalled for at least the TTL. The [exceptTrainId] train, the requester at the top of its
	 * own request, is neither judged nor snapshotted.
	 */
	fun stalled(exceptTrainId: String?): List<StalledTail> {
		val holdings = registry.occupyingHoldings()
		// Pruned to the trains still on the track with a holding: a train that left the registry, or
		// stopped standing on what it holds, starts a fresh clock should it ever be back.
		progress.keys.retainAll(holdings.mapTo(HashSet()) { it.trainId })
		if (holdings.isEmpty()) return emptyList()
		val now = simTime()
		return holdings
			.filter { it.trainId != exceptTrainId }
			.mapNotNull { judge(it, now) }
	}

	/**
	 * After the expiry's own partial release of [stalled], keep its clock with the [remaining] tail:
	 * that release is not progress, so the rest is retried on the next call.
	 */
	fun keepClock(
		stalled: StalledTail,
		remaining: Set<DynamicTrackBlock>
	) {
		progress[stalled.holding.trainId] = stalled.snapshot.copy(reserved = remaining)
	}

	/** Drop [trainId]'s clock: its tail is gone, so a new tail starts a clock of its own. */
	fun forget(trainId: String) {
		progress.remove(trainId)
	}

	/** One occupying train's stall verdict at [now]; updates its snapshot. */
	private fun judge(
		holding: OccupyingHolding,
		now: Double
	): StalledTail? {
		val trainId = holding.trainId
		if (holding.reserved.isEmpty()) {
			// Nothing to free (ruling D5): no clock either -- a new tail starts one of its own.
			progress.remove(trainId)
			return null
		}
		// Not judged on this call (ruling D2): an OCCUPIED booking, or somebody else on its block.
		val reading = readingOf(holding) ?: return null
		val current = ProgressSnapshot(holding.occupied.toSet(), holding.reserved.toSet(), reading, now)
		val last = progress[trainId]
		if (last == null || last.isProgressIn(current)) {
			progress[trainId] = current
			return null
		}
		val stalledFor = now - last.sinceSimTime
		return if (stalledFor >= ttlSimSeconds) StalledTail(holding, last, stalledFor) else null
	}

	/** The progress reading of [holding]'s own occupant, or `null` when none of its blocks has one. */
	private fun readingOf(holding: OccupyingHolding): Double? =
		holding.occupied
			.firstNotNullOfOrNull { block -> block.occupant?.takeIf { it.name == holding.trainId } }
			?.distanceToSemaphore()
}
