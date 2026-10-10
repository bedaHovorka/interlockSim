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

import cz.ksimulantenbande.kdisco.Resource
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Maps each [DynamicTrackBlock] to a kDisco [Resource] with capacity 1.
 *
 * This helper is intentionally small: the first slice of Goal 1 uses kDisco
 * [Resource] only as a **setup-time capacity/FIFO gate**. The dispatcher
 * (see [MultiTrainLoop]) atomically checks that all required resources are
 * available, acquires them, delegates block ownership to
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService], and
 * immediately releases the resources again.
 *
 * ## Why not hold resources for the whole journey?
 *
 * A single kDisco process (the dispatcher) holding multiple resources while it
 * waits for others would create a self-deadlock: the dispatcher would hold some
 * resources and be passivated waiting for the rest, but only the dispatcher can
 * release resources when trains leave blocks. Acquiring all resources only when
 * they are all free, then releasing right away, keeps the dispatcher non-blocking
 * and still demonstrates the kDisco [Resource] primitive.
 *
 * ## The gate is inert today (Issue #1148)
 *
 * The dispatcher acquires and releases the resources inside one section that never
 * suspends on another process, so no other attempt can observe them held:
 * [areAllAvailable] always returns `true` when the dispatcher checks it and the gate
 * never refuses an attempt. It adds bookkeeping cost but no protection — journey-time
 * exclusivity is enforced solely by
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]. The gate is kept
 * on purpose (owner ruling on Issue #1147); removing it is out of scope. Since Issue #1148
 * the dispatcher gates, per `(entry, exit)` pair, the union of the blocks of every
 * candidate route from [cz.vutbr.fit.interlockSim.context.RouteFinder] (computed once, the
 * topology is static), because `reservePath` chooses the candidate itself.
 *
 * ## Future slices
 *
 * When path reservation becomes incremental, a per-block guard process could hold
 * each resource for the exact interval a train occupies the block, giving a true
 * physical-capacity mapping without blocking the dispatcher.
 *
 * @see Resource
 * @see MultiTrainLoop
 */
internal class BlockResourceRegistry(
	context: SimulationContext
) {
	private val resources: Map<DynamicTrackBlock, Resource> =
		context.getGraph().values().associateWith { Resource(capacity = 1) }

	/**
	 * Returns the capacity-1 [Resource] representing the physical availability of
	 * [block] during dispatcher setup.
	 */
	fun resourceFor(block: DynamicTrackBlock): Resource = resources.getValue(block)

	/**
	 * Releases resources that are still held by the dispatcher for blocks that have
	 * become FREE and unowned. This is a safety net; the dispatcher normally releases
	 * resources immediately after successful path reservation.
	 */
	fun releaseFreeResources() {
		for ((block, resource) in resources) {
			if (resource.occupied > 0 && block.getState() == TrackFacility.State.FREE && block.trainName == null) {
				resource.release(1)
			}
		}
	}

	/**
	 * Number of resources currently occupied by the dispatcher.
	 */
	fun occupiedCount(): Int = resources.values.sumOf { it.occupied }

	/**
	 * True if every required block resource is currently available (capacity not
	 * held by a previous dispatcher attempt).
	 */
	fun areAllAvailable(blocks: List<DynamicTrackBlock>): Boolean = blocks.all { resources.getValue(it).isAvailable(1) }
}
