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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Deterministic rule-based dispatcher — the baseline implementation of the
 * [Dispatcher] seam introduced by SP0.1 (Issue #540), reshaped to a pure
 * decision function by SP0.7 (Issue #729).
 *
 * ## Dispatch policy
 *
 * Each call to [decide]:
 * 1. **Admission** — admits trains from the head of
 *    [DispatchObservation.unapprovedTrains] until [maxConcurrentTrains]
 *    simultaneous trains are active.
 * 2. **Path advancement** — for every [DispatchObservation.innerBlockInputs] and
 *    [DispatchObservation.outerBlockInputs] entry, reserves the next forward
 *    section for any approaching or reserved train. Each reservation is expressed
 *    as a from→to [DispatchDecision.ReservePath] whose `to` the shell has
 *    pre-computed as the next FREE separator one section ahead — destination-agnostic;
 *    see [BlockInputObservation.toSeparatorName].
 *
 * The shell ([ShuntingLoop]) calls [decide] once per tick with a single
 * [DispatchObservation] whose fields are all populated together (SP0.11,
 * Issue #733 — see [DispatchObservation]); admission and path-advancement are
 * handled in the same call, and the two may both be non-empty.
 *
 * ## Determinism (Goal 10 Stage A3)
 * Train admission is strictly FIFO over [DispatchObservation.unapprovedTrains].
 * Path selection is applied by the shell via
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath]
 * using the `to` separator this dispatcher echoes from the observation, which
 * produces deterministic results for a fixed network topology. Given the same XML
 * network and train generation sequence, this dispatcher produces identical
 * decision sequences across consecutive runs.
 *
 * ## All-inputs evaluation
 * Each input of an inner block is evaluated independently (no short-circuit). In
 * the real domain model a block's occupant can only be approaching one input
 * ([cz.vutbr.fit.interlockSim.objects.core.TrackOccupant.nextSemaphore] is
 * single-valued) and a reserved block's path is only ever set up toward one input
 * ([cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.isSetUpPath] is
 * directional), so at most one input of a real block is ever eligible for a
 * decision per tick — independent evaluation is behaviorally identical to the
 * pre-#729 short-circuiting version for real data. The short-circuit itself is
 * no longer possible under a pure [decide]: it depended on the *live return
 * value* of a reservation attempt, which is unavailable before the shell applies
 * the returned [DispatchDecision]s.
 *
 * When the shell found no FREE next separator one section ahead
 * ([BlockInputObservation.toSeparatorName] == `null`), no reservation can be
 * requested this tick and [DispatchDecision.NoAction] effectively results for
 * that input — the train waits and is reconsidered next tick, matching the
 * pre-#729 behaviour where `reservePathToAnyNextSemaphore` returned
 * `AllPathsBlocked`.
 *
 * ## Route-scoring rule engine (SP2b.2) and command generation (SP2b.3)
 * Deterministic priority-rule scoring of candidate routes (shortest path, lowest
 * conflict risk, fewest switch movements) is implemented by [CandidatePathRuleEngine]
 * (SP2b.2, Issue #557). That engine consumes candidates from Goal 2 pathfinding,
 * optionally attaches a conflict-risk weight (conflict-aware selection), and ranks them.
 * [PathCommandTranslator] (SP2b.3, Issue #558) translates the selected [PathCandidate]
 * into explicit [DispatchDecision.SetSwitchPosition] and [DispatchDecision.SetSignalAspect]
 * commands that the interlocking independently validates before the path is reserved.
 * This per-tick [decide] currently reserves a single pre-computed section per input and does
 * not yet route through [CandidatePathRuleEngine]; wiring the engine into multi-route
 * selection here is deferred to SP2b.5 (Issue #560).
 *
 * ## Goal 9 integration hook
 * This class is also the designated place for wiring Goal 9's
 * [cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService] and
 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker]: when the
 * shell's path reservation attempt returns a
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict],
 * conflict resolution can be applied in a future iteration without touching
 * [ShuntingLoop].
 *
 * @param maxConcurrentTrains Maximum number of simultaneously approved trains.
 *   Defaults to [DEFAULT_MAX_CONCURRENT_TRAINS] which matches the physical capacity
 *   of the `vyhybna.xml` shunting loop (2 parallel tracks).
 *
 * @see Dispatcher
 * @see ShuntingLoop
 * @since Issue #540 (SP0.1 — Goal 10); reshaped to a pure seam in Issue #729
 *   (SP0.7 — Goal 10)
 */
class RuleBasedDispatcher(
	val maxConcurrentTrains: Int = DEFAULT_MAX_CONCURRENT_TRAINS
) : Dispatcher {
	companion object {
		/**
		 * Physical capacity of the `vyhybna.xml` shunting loop station: 2 parallel
		 * tracks (k1, k2).
		 *
		 * This is a station-topology constant, not a tunable performance knob: a real
		 * interlocking's concurrent-train capacity is determined by railway-civil-engineering
		 * expertise at station design time (how many parallel tracks/platforms a given
		 * station has), the same way [maxConcurrentTrains] is fixed per [RuleBasedDispatcher]
		 * instance rather than adjusted at runtime. Changing this default only makes sense
		 * if the bundled `vyhybna.xml` topology itself changes; a different station layout
		 * should pass its own capacity via the [maxConcurrentTrains] constructor parameter.
		 */
		const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 2
		private val logger = KotlinLogging.logger {}
	}

	init {
		require(maxConcurrentTrains > 0) {
			"maxConcurrentTrains must be positive, got: $maxConcurrentTrains"
		}
	}

	override fun decide(observed: DispatchObservation): List<DispatchDecision> {
		val decisions = decideAdmissions(observed) + checkAllInputs(observed)
		return decisions.ifEmpty { listOf(DispatchDecision.NoAction) }
	}

	// ── Train admission ─────────────────────────────────────────────────────

	private fun decideAdmissions(observed: DispatchObservation): List<DispatchDecision.ApproveTrain> {
		val freeSlots = maxConcurrentTrains - observed.approvedTrainCount
		if (freeSlots <= 0) {
			return emptyList()
		}
		return observed.unapprovedTrains.take(freeSlots).map { queued ->
			logger.debug { "RuleBasedDispatcher: approving ${queued.trainId}" }
			DispatchDecision.ApproveTrain(queued.trainId)
		}
	}

	// ── Path advancement ────────────────────────────────────────────────────

	/**
	 * Evaluates every block input and collects the resulting reservations.
	 *
	 * ## Same-tick same-target dedup (Goal 10 Stage A3, Issue #829)
	 *
	 * [checkInput] resolves each input independently from one frozen [observed] snapshot, so
	 * two different trains approaching a track merge (different blocks) can both compute the
	 * *same* [BlockInputObservation.toSeparatorName] as their next FREE separator — neither
	 * input can see the other's not-yet-applied reservation. Emitting both
	 * [DispatchDecision.ReservePath]s lets one land as a same-tick reservation race purely from
	 * this evaluation order, not from any real track contention (confirmed via
	 * `RuleBasedDispatcherDeterminismTest` producing an intermittent
	 * `ConflictDetectedEvent` on `vyhybna.xml`'s merge points).
	 *
	 * [claimedSeparators] tracks every target already claimed earlier in this same batch; a
	 * later input targeting an already-claimed separator is deferred (yields no decision) rather
	 * than emitted — the deferred train is simply re-evaluated next tick, by which point the
	 * winner's reservation has updated the topology and the shell will recompute a (likely
	 * different) FREE separator for it.
	 */
	private fun checkAllInputs(observed: DispatchObservation): List<DispatchDecision.ReservePath> {
		val claimedSeparators = mutableSetOf<String>()
		return (observed.innerBlockInputs + observed.outerBlockInputs).mapNotNull { checkInput(it, claimedSeparators) }
	}

	/**
	 * Decides whether a forward path reservation is warranted from [input].
	 *
	 * @param claimedSeparators Separators already claimed by an earlier input in this same
	 *   [checkAllInputs] batch; mutated in place when this call claims a new one.
	 * @return A [DispatchDecision.ReservePath] when the block state warrants one, the shell
	 *   found a FREE next separator ([BlockInputObservation.toSeparatorName] non-null), and that
	 *   separator has not already been claimed this tick; `null` when the state requires no
	 *   action (FREE, or occupied/reserved but not eligible toward this input, or already
	 *   extended, or no FREE next separator one section ahead, or the target separator was
	 *   already claimed by an earlier input this tick).
	 */
	private fun checkInput(
		input: BlockInputObservation,
		claimedSeparators: MutableSet<String>
	): DispatchDecision.ReservePath? =
		when (input.state) {
			TrackFacility.State.FREE -> null

			TrackFacility.State.OCCUPIED -> {
				if (!input.isApproachingThisInput) {
					null
				} else if (input.pathAlreadyExtendedBeyond) {
					logger.debug {
						"Path already extends beyond ${input.towardSemaphoreName} for ${input.ownerTrainId}, " +
							"skipping redundant reservation"
					}
					null
				} else {
					reserveOrDefer(input, claimedSeparators)
				}
			}

			TrackFacility.State.RESERVED -> {
				if (!input.pathSetUpTowardThisInput) {
					null
				} else if (input.pathAlreadyExtendedBeyond) {
					logger.debug {
						"Path already extends beyond ${input.towardSemaphoreName} for ${input.ownerTrainId} " +
							"(reserved block), skipping"
					}
					null
				} else {
					reserveOrDefer(input, claimedSeparators)
				}
			}
		}

	/**
	 * Shared tail of the OCCUPIED/RESERVED [checkInput] branches: emits a [DispatchDecision.ReservePath]
	 * toward [BlockInputObservation.toSeparatorName], unless there is no FREE separator yet or it was
	 * already claimed by an earlier input this tick (see [checkAllInputs]).
	 */
	private fun reserveOrDefer(
		input: BlockInputObservation,
		claimedSeparators: MutableSet<String>
	): DispatchDecision.ReservePath? {
		val target = input.toSeparatorName
		if (target == null) {
			logger.debug {
				"No FREE next separator from ${input.towardSemaphoreName} for ${input.ownerTrainId}, " +
					"deferring reservation"
			}
			return null
		}
		if (!claimedSeparators.add(target)) {
			logger.debug {
				"Separator $target already claimed by another train this tick; deferring " +
					"${input.ownerTrainId}'s reservation from ${input.towardSemaphoreName} to next tick"
			}
			return null
		}
		logger.debug {
			"Train ${input.ownerTrainId} approaching ${input.towardSemaphoreName}, " +
				"reserving forward path to $target"
		}
		return DispatchDecision.ReservePath(
			requireNotNull(input.ownerTrainId),
			input.towardSemaphoreName,
			target
		)
	}
}
