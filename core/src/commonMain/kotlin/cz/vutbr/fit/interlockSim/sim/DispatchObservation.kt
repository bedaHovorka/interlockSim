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

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot

/**
 * Read-only input to [Dispatcher.decide] — everything a dispatch policy needs to
 * decide what to do this tick, with no callbacks and no live mutable handles.
 *
 * Combines the general-purpose [SimulationSnapshot] (SP0.4, Issue #543) with the
 * unapproved-train queue and, for the path-advancement phase, per-block-end facts
 * that [SimulationSnapshot] does not carry (directional reservation state — see
 * [BlockEndObservation]).
 *
 * ## Two-phase tick
 * The shell ([ShuntingLoop]) builds two different [DispatchObservation]s per
 * iteration, mirroring the pre/post-hold split the original two-method [Dispatcher]
 * interface (Issue #540 / #540 review) required: an admission-phase observation
 * with real [unapprovedTrains] and empty block-end lists, and a path-advancement
 * observation with an empty [unapprovedTrains] and real block-end lists.
 *
 * @property snapshot General sense data (signals, block occupancy, train
 *   positions, timetables) at the start of this phase.
 * @property unapprovedTrains Trains queued but not yet approved, in admission
 *   order. Empty during the path-advancement phase.
 * @property innerBlockEnds Both ends of every inner track block (RailSemaphore–
 *   RailSemaphore). Empty during the admission phase.
 * @property outerBlockEnds The [DynamicRailSemaphore][cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]
 *   end of every outer track block (InOut–RailSemaphore). Empty during the
 *   admission phase.
 *
 * @since Issue #729 (SP0.7 — Goal 10)
 */
data class DispatchObservation(
	val snapshot: SimulationSnapshot,
	val unapprovedTrains: List<QueuedTrainObservation>,
	val innerBlockEnds: List<BlockEndObservation> = emptyList(),
	val outerBlockEnds: List<BlockEndObservation> = emptyList()
) {
	/** Number of trains currently approved (active in the simulation). */
	val approvedTrainCount: Int get() = snapshot.trainPositions.size
}

/**
 * A single queued (not yet approved) train, read-only.
 *
 * @property trainId The train's name/identifier.
 * @property destinationInOutName Name of the InOut this train is timetabled to exit
 *   through.
 */
data class QueuedTrainObservation(
	val trainId: String,
	val destinationInOutName: String
)

/**
 * Everything a dispatch policy needs to decide whether to extend a reservation
 * toward [towardSemaphoreName], pre-computed by the shell from live block/registry
 * state before [Dispatcher.decide] is called.
 *
 * [SimulationSnapshot]'s [cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading]
 * only carries `blockId`/`state`/`trainId` — it has no notion of *which end* a
 * block is occupied/reserved toward, or whether a reservation already extends
 * beyond a given semaphore. Those directional facts live here instead.
 *
 * @property blockId Name of the track block.
 * @property towardSemaphoreName Name of the semaphore at this end.
 * @property state Occupancy state of the block.
 * @property ownerTrainId Name of the train associated with this block: the
 *   occupant's name when OCCUPIED, [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.trainName]
 *   when RESERVED, `null` when FREE.
 * @property isApproachingThisEnd `true` when OCCUPIED and the occupant's
 *   `nextSemaphore()` is the semaphore at this end.
 * @property pathSetUpTowardThisEnd `true` when RESERVED and the path is already
 *   set up toward this end (block is reserved from the opposite end).
 * @property pathAlreadyExtendedBeyond `true` when [ownerTrainId]'s reserved path
 *   already extends beyond this end — a further reservation attempt would be a
 *   no-op.
 */
data class BlockEndObservation(
	val blockId: String,
	val towardSemaphoreName: String,
	val state: TrackFacility.State,
	val ownerTrainId: String?,
	val isApproachingThisEnd: Boolean,
	val pathSetUpTowardThisEnd: Boolean,
	val pathAlreadyExtendedBeyond: Boolean
)
