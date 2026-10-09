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
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading

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

	companion object {
		/**
		 * Builds an observation whose stub [SimulationSnapshot] carries only [trainPositions]:
		 * semaphores, blocks, timetables and train perceptions are empty. For callers that
		 * convert from a push-based format with no full snapshot, such as `:dispatcher-agent`'s
		 * `DispatcherObservation` (a type `:core` cannot see).
		 *
		 * Every parameter is required on purpose, so a caller cannot silently lose block inputs.
		 *
		 * @param simTime Simulation time (seconds) stamped on the stub snapshot.
		 * @param trainPositions One reading per active (approved) train, so [approvedTrainCount]
		 *   is correct.
		 * @param unapprovedTrains See [DispatchObservation.unapprovedTrains].
		 * @param innerBlockInputs See [DispatchObservation.innerBlockInputs].
		 * @param outerBlockInputs See [DispatchObservation.outerBlockInputs].
		 */
		fun from(
			simTime: Double,
			trainPositions: List<TrainPositionReading>,
			unapprovedTrains: List<QueuedTrainObservation>,
			innerBlockInputs: List<BlockInputObservation>,
			outerBlockInputs: List<BlockInputObservation>
		): DispatchObservation =
			DispatchObservation(
				snapshot =
					SimulationSnapshot(
						simTime = simTime,
						semaphores = emptyList(),
						blocks = emptyList(),
						trainPositions = trainPositions,
						timetables = emptyList(),
						trainPerceptions = emptyList()
					),
				unapprovedTrains = unapprovedTrains,
				innerBlockInputs = innerBlockInputs,
				outerBlockInputs = outerBlockInputs
			)
	}
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
 * @property toSeparatorName Compatibility projection of the dispatcher's choice:
 *   `ReservationTargetPolicy.pick(candidateTargets)?.name` (Issue #970) — the first
 *   available next separator one section ahead (InOuts prioritised over semaphores):
 *   a semaphore, or the destination InOut for the final section. The shell
 *   ([ShuntingLoop]) fills it from the same [candidateTargets] list it publishes, so a
 *   reader that only needs the pick (`:dispatcher-agent`'s `NextHopResolver`) need not
 *   re-run the policy; [RuleBasedDispatcher] itself reads [candidateTargets] and never
 *   this field.
 *   **Destination-agnostic**: `ReservationTargetQuery.findReservationTargetCandidates`
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
 * @property awaitingRouteExtension `true` when [ownerTrainId] stands at this input's signal
 *   although [pathAlreadyExtendedBeyond]: its stored route runs past the signal but ends at a
 *   separator facing away from the train, so navigation cannot build a leg out of it and holds
 *   the train until the route is extended to the next signal facing it (Issue #1060). The
 *   route is extended beyond the input, yet a further reservation is NOT a no-op, so a
 *   dispatcher treats the input like one that is not extended
 *   ([toSeparatorName] is resolved for it). The flag is set for any train that stands at the
 *   signal while navigation answers an ownership conflict — including a foreign-owned block
 *   ahead of an otherwise valid leg, not only the rear-facing-end case. A later reservation
 *   re-validates everything, so a false positive only costs one search. Defaults to `false`.
 * @property candidateTargets Every next separator one section ahead of
 *   [towardSemaphoreName] that the interlocking could set a route to, in the
 *   interlocking's search order (InOuts first), each with its availability
 *   ([CandidateTarget.available]) — the facts the dispatcher chooses from (Issue #970).
 *   The choice itself is [ReservationTargetPolicy.pick]. Every candidate listed has
 *   been evaluated: a candidate is never reported `available = false` without the
 *   availability check having run.
 *
 *   **Populated only where a forward reservation is possible** (Issue #749). The shell
 *   resolves the list (and the [toSeparatorName] projection) exclusively for inputs satisfying
 *   `(!pathAlreadyExtendedBeyond || awaitingRouteExtension) && (isApproachingThisInput || pathSetUpTowardThisInput)`;
 *   for every other input — FREE, not approaching this input, or already extended beyond
 *   it without awaiting an extension ([awaitingRouteExtension]) — the list is empty and
 *   the projection `null` **without the search having been run**. Resolving it means a BFS
 *   plus a per-candidate topological-path enumeration
 *   ([PathReservationService.findReservationTargetCandidates][cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetQuery.findReservationTargetCandidates]);
 *   running it for the ~98% of inputs whose value is then discarded cost ~9% of fast-sim
 *   wall time.
 *
 *   An empty list (a `null` projection) therefore means *"no forward-reservation target
 *   applies"*, not *"the search found nothing"* — the two are indistinguishable to a
 *   dispatcher, and both call for the same response (no reservation for this input on this
 *   tick). Dispatcher implementations — including future LLM-backed ones — must not read an
 *   empty list or `toSeparatorName == null` as evidence that the track ahead is occupied.
 */
data class BlockInputObservation(
	val blockId: String,
	val towardSemaphoreName: String,
	val toSeparatorName: String? = null,
	val state: TrackFacility.State,
	val ownerTrainId: String?,
	val isApproachingThisInput: Boolean,
	val pathSetUpTowardThisInput: Boolean,
	val pathAlreadyExtendedBeyond: Boolean,
	val awaitingRouteExtension: Boolean = false,
	val candidateTargets: List<CandidateTarget> = emptyList()
)

/**
 * The kind of separator a [CandidateTarget] names — the only two kinds the interlocking's
 * forward search returns (see `findNextSemaphoresVia`).
 *
 * @since Issue #970
 */
enum class SeparatorKind {
	/** A station exit ([cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut]) — a terminal route end. */
	IN_OUT,

	/** A forward-facing signal ([cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]). */
	SEMAPHORE
}

/**
 * One separator the interlocking could set a route to from a block input, with the result of
 * its availability check — a fact the shell reports and the dispatcher chooses from through
 * [ReservationTargetPolicy] (Issue #970).
 *
 * @property name The separator's name — a legal route endpoint, never a block id.
 * @property kind Whether [name] is a station exit or a signal.
 * @property available `true` when a physically legal route to [name] exists whose blocks are all
 *   FREE (or owned by the reserving train, for the Issue #1060 extension case). Always the result
 *   of a real check: a candidate is never listed as unavailable without having been evaluated.
 * @since Issue #970
 */
data class CandidateTarget(
	val name: String,
	val kind: SeparatorKind,
	val available: Boolean
)
