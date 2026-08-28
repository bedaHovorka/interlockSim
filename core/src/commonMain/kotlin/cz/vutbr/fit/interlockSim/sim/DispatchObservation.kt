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
 * unapproved-train queue and the per-block-input facts that [SimulationSnapshot]
 * does not carry (directional reservation state — see [BlockInputObservation]).
 *
 * ## One observation per tick (SP0.11)
 * The shell ([ShuntingLoop]) publishes a single [DispatchObservation] per iteration
 * carrying ALL fields populated at once: the queued trains and both block-input
 * lists are snapshotted together (see [ShuntingLoop.latestObservation]). Admission
 * and path-advancement are decided in the same [Dispatcher.decide] call. The
 * historical two-observation pre/post-hold split (Issue #540) was removed by the
 * SP0.11 thin-shell refactor (Issue #733); the `innerBlockInputs`/`outerBlockInputs`
 * defaults remain `emptyList()` only so bare/early callers stay valid.
 *
 * @property snapshot General sense data (signals, block occupancy, train
 *   positions, timetables) at the start of this tick.
 * @property unapprovedTrains Trains queued but not yet approved, in admission
 *   order.
 * @property innerBlockInputs All inputs of every inner track block (RailSemaphore–
 *   RailSemaphore) — one per semaphore end a train could enter the next section
 *   through.
 * @property outerBlockInputs The [DynamicRailSemaphore][cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]
 *   input of every outer track block (InOut–RailSemaphore) — the semaphore a train
 *   entering from the InOut proceeds toward.
 *
 * @since Issue #729 (SP0.7 — Goal 10)
 */
data class DispatchObservation(
	val snapshot: SimulationSnapshot,
	val unapprovedTrains: List<QueuedTrainObservation>,
	val innerBlockInputs: List<BlockInputObservation> = emptyList(),
	val outerBlockInputs: List<BlockInputObservation> = emptyList()
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
 * only carries `blockId`/`state`/`trainId` — it has no notion of *which input* a
 * block is occupied/reserved toward, or whether a reservation already extends
 * beyond a given semaphore. Those directional facts live here instead.
 *
 * @property blockId Name of the track block.
 * @property towardSemaphoreName Name of the semaphore at this input.
 * @property toSeparatorName The first FREE next separator one section ahead (InOuts
 *   prioritised over semaphores) — a semaphore, or the destination InOut for the
 *   final section — pre-computed by the shell (see [ShuntingLoop]).
 *   **Destination-agnostic**: `PathReservationService.findNextReservationTarget`
 *   takes only a start separator, so it cannot know where the train is headed —
 *   like a real interlocking granting *"postav jízdní cestu od X k Y"*, start and
 *   end are given to it; knowing the destination is the dispatcher's job. Because
 *   InOuts are always prioritised, the nearest exit wins whenever a branch
 *   terminating at an InOut competes with one continuing into the station; on
 *   `vyhybna.xml`'s two-InOut passing loop both branches lead to the same exit, so
 *   this happens to look destination-directed there. `null` when no FREE next
 *   separator exists, in which case the dispatcher emits
 *   [DispatchDecision.NoAction] for this input (the train waits and is
 *   reconsidered next tick).
 *
 *   **Populated only where a forward reservation is possible** (Issue #749). The shell
 *   resolves it exclusively for inputs satisfying
 *   `!pathAlreadyExtendedBeyond && (isApproachingThisInput || pathSetUpTowardThisInput)`;
 *   for every other input — FREE, not approaching this input, or already extended beyond
 *   it — this is `null` **without the search having been run**. Resolving it means a BFS
 *   plus a per-candidate topological-path enumeration
 *   ([PathReservationService.findNextReservationTarget][cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.findNextReservationTarget]);
 *   running it for the ~98% of inputs whose value is then discarded cost ~9% of fast-sim
 *   wall time.
 *
 *   A `null` here therefore means *"no forward-reservation target applies"*, not
 *   *"the search found nothing"* — the two are indistinguishable to a dispatcher, and
 *   both call for the same response (no reservation for this input on this tick).
 *   Dispatcher implementations — including future LLM-backed ones — must not read
 *   `toSeparatorName == null` as evidence that the track ahead is occupied.
 * @property state Occupancy state of the block.
 * @property ownerTrainId Name of the train associated with this block: the
 *   occupant's name when OCCUPIED, [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.trainName]
 *   when RESERVED, `null` when FREE.
 * @property isApproachingThisInput `true` when OCCUPIED and the occupant's
 *   `nextSemaphore()` is the semaphore at this input.
 * @property pathSetUpTowardThisInput `true` when RESERVED and the path is already
 *   set up toward this input (block is reserved from the opposite end).
 * @property pathAlreadyExtendedBeyond `true` when [ownerTrainId]'s reserved path
 *   already extends beyond this input — a further reservation attempt would be a
 *   no-op.
 */
data class BlockInputObservation(
	val blockId: String,
	val towardSemaphoreName: String,
	val toSeparatorName: String? = null,
	val state: TrackFacility.State,
	val ownerTrainId: String?,
	val isApproachingThisInput: Boolean,
	val pathSetUpTowardThisInput: Boolean,
	val pathAlreadyExtendedBeyond: Boolean
)
