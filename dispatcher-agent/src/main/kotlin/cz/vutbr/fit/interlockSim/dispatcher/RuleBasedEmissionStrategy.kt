/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.agents.AttributedAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import java.util.concurrent.atomic.AtomicLong

/**
 * [EmissionStrategy] adapter that delegates to the synchronous [Dispatcher] API (SP2c.5,
 * Issue #828 — P10 gate).
 *
 * ## Purpose
 *
 * [RuleBasedEmissionStrategy] routes the [RuleBasedDispatcher][cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]
 * (or any other [Dispatcher] implementation) through the **same** [DispatchTickLoop] validator
 * and four-action vocabulary as the LLM agent. This provides three guarantees (from the issue):
 *
 * 1. The rule engine is exercised against the **same** [ActionValidator] and the **same**
 *    four-action vocabulary — a real cross-check on the vocabulary's expressiveness. If the
 *    rule dispatcher cannot be expressed in four actions, the vocabulary is wrong.
 *
 * 2. The existing 10-identical-`vyhybna`-runs determinism test (P10 gate) re-points at
 *    `DispatchTickLoop + RuleBasedEmissionStrategy` and must pass **byte-identically**.
 *
 * 3. It proves the loop with the deterministic dispatcher **before** the LLM's crutches are
 *    removed.
 *
 * ## Observation conversion
 *
 * [Dispatcher.decide] takes a [DispatchObservation] (the pre-SP2c.1 format), but [DispatchTickLoop]
 * supplies a [DispatcherObservation] (the new push-based format). The conversion:
 *
 * - `approvedTrainCount` ← count of trains with phase not QUEUED and not EXITED (= `obs.activeCount`)
 * - `unapprovedTrains` ← `obs.queued` mapped to [QueuedTrainObservation]
 * - `innerBlockInputs` ← `obs.innerBlockInputs` (populated by [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector] since SP2c.5)
 * - `outerBlockInputs` ← `obs.outerBlockInputs` (same)
 * - `snapshot.trainPositions` ← built from `obs.trains` (non-QUEUED, non-EXITED) so that
 *   `approvedTrainCount == snapshot.trainPositions.size` — the only field of [SimulationSnapshot]
 *   that [RuleBasedDispatcher] accesses via [DispatchObservation.approvedTrainCount].
 *
 * ## Decision → Action mapping
 *
 * [Dispatcher.decide] returns [DispatchDecision] subtypes. This strategy converts them to
 * [DispatchAction] according to the four-action vocabulary:
 *
 * | [DispatchDecision] | [DispatchAction] |
 * |---|---|
 * | [DispatchDecision.ApproveTrain] | [DispatchAction.ApproveTrain] |
 * | [DispatchDecision.ReservePath] | [DispatchAction.RequestRoute] (same `from`/`to` semantics; `scope = RouteScope.Section` — see Issue #848 / #829) |
 * | [DispatchDecision.NoAction] | filtered out (empty list = "nothing to do") |
 * | Any other subtype | filtered out |
 *
 * `NoAction` produces no [AttributedAction] — an empty result list — which the [DispatchTickLoop]
 * treats as "nothing to do this tick" and does **not** substitute with a [ActionAuthor.TIMEOUT_NOOP]
 * (that substitution applies only to `null` returns, i.e. deadline timeouts).
 *
 * ## `CommandId` assignment
 *
 * [AttributedAction.commandId] values are assigned from an internal monotonic counter (one counter
 * per [RuleBasedEmissionStrategy] instance, lifetime-scoped to the strategy). [DispatchTickLoop]
 * may re-sequence them if needed; the values here only need to be unique within a single
 * [emit] call.
 *
 * ## `ActionAuthor`
 *
 * Every [AttributedAction] returned by this strategy has [ActionAuthor.RULE_BASED]. This tag is
 * what prevents [cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard] from marking
 * a rule-based determinism run as [cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome.Failed]
 * (only [ActionAuthor.RULE_FALLBACK] triggers that transition).
 *
 * @param dispatcher The [Dispatcher] to delegate to. [RuleBasedDispatcher][cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]
 *   for the P10 gate; can be any [Dispatcher] implementation for future use.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
class RuleBasedEmissionStrategy(
	private val dispatcher: Dispatcher
) : EmissionStrategy {
	private val commandIdCounter: AtomicLong = AtomicLong(0L)

	/**
	 * Invokes [Dispatcher.decide] with a [DispatchObservation] built from [observation] and
	 * maps the returned [DispatchDecision]s to [AttributedAction]s.
	 *
	 * Ignores [prompt] entirely — the rule-based dispatcher decides from the observation alone.
	 *
	 * Returns an empty list (not `null`) when the dispatcher produces only [DispatchDecision.NoAction]:
	 * an empty list means "nothing to do this tick" and does not trigger the
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor.TIMEOUT_NOOP] substitution in
	 * [DispatchTickLoop].
	 */
	override suspend fun emit(
		prompt: String,
		observation: DispatcherObservation
	): List<AttributedAction> {
		val dispatchObservation = toDispatchObservation(observation)
		val decisions = dispatcher.decide(dispatchObservation)
		return decisions.mapNotNull { decision -> toAttributedAction(decision, observation.tick) }
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	/**
	 * Converts a [DispatcherObservation] (push-based, SP2c.1 format) into a [DispatchObservation]
	 * (pull-based, pre-SP2c.1 format) suitable for [Dispatcher.decide].
	 *
	 * ## SimulationSnapshot stub
	 *
	 * [RuleBasedDispatcher] accesses only `snapshot.trainPositions.size` (via
	 * [DispatchObservation.approvedTrainCount]). The stub fills `trainPositions` with one entry
	 * per active train (non-QUEUED, non-EXITED) so the count is correct. All other snapshot
	 * fields are left empty.
	 *
	 * ## Optimistic-projection inaccuracy
	 *
	 * When [DispatchTickLoop] calls [emit] with an optimistically projected [observation]
	 * (for C6 re-validation), the projected `activeCount` / `queued` reflect the in-loop
	 * state after previous actions this tick — not the true sim-thread state. This is the
	 * documented intra-tick inaccuracy from the issue design table (option A); divergence
	 * is bounded to one tick and surfaces in `applied_outcomes` next tick.
	 */
	private fun toDispatchObservation(obs: DispatcherObservation): DispatchObservation {
		val activeTrainPositions =
			obs.trains
				.filter { it.phase != TrainPhase.QUEUED && it.phase != TrainPhase.EXITED }
				.map {
					TrainPositionReading(
						trainId = it.trainId,
						velocity = it.velocityMps,
						acceleration = it.accelerationMps2,
						totalDistance = 0.0,
						frontSectionName = it.frontSectionName
					)
				}

		val snapshot =
			SimulationSnapshot(
				simTime = obs.simTime,
				semaphores = emptyList(),
				blocks = emptyList(),
				trainPositions = activeTrainPositions,
				timetables = emptyList()
			)

		return DispatchObservation(
			snapshot = snapshot,
			unapprovedTrains =
				obs.queued.map { queuedTrain ->
					QueuedTrainObservation(
						trainId = queuedTrain.trainId,
						destinationInOutName = queuedTrain.destinationInOutName
					)
				},
			innerBlockInputs = obs.innerBlockInputs,
			outerBlockInputs = obs.outerBlockInputs
		)
	}

	/**
	 * Maps one [DispatchDecision] to an [AttributedAction] with [ActionAuthor.RULE_BASED].
	 *
	 * Returns `null` for [DispatchDecision.NoAction] and any unknown subtype — the
	 * caller's `mapNotNull` filters them out, producing an empty list that means
	 * "nothing to do" (not a timeout).
	 */
	private fun toAttributedAction(
		decision: DispatchDecision,
		tick: Long
	): AttributedAction? =
		when (decision) {
			is DispatchDecision.ApproveTrain ->
				AttributedAction(
					commandId = CommandId(commandIdCounter.incrementAndGet()),
					tick = tick,
					action = DispatchAction.ApproveTrain(trainId = decision.trainId),
					author = ActionAuthor.RULE_BASED
				)

			is DispatchDecision.ReservePath ->
				AttributedAction(
					commandId = CommandId(commandIdCounter.incrementAndGet()),
					tick = tick,
					action =
						DispatchAction.RequestRoute(
							trainId = decision.trainId,
							fromEndpointName = decision.fromSemaphoreName,
							toEndpointName = decision.toSeparatorName,
							// Issue #848 / #829: ReservePath is a hop-level (per-block-boundary)
							// decision, not a request for the train's final destination — see
							// RouteScope's KDoc for why ActionValidator needs this discriminant.
							scope = RouteScope.Section
						),
					author = ActionAuthor.RULE_BASED
				)

			// NoAction: return null → caller's mapNotNull produces an empty list, which DispatchTickLoop
			// treats as "nothing to do" rather than "timeout" (only null triggers TIMEOUT_NOOP).
			DispatchDecision.NoAction -> null

			// Any other subtype (HoldTrain, SetSignalAspect, SetSwitchPosition, ReleaseRoute, RequestRoute)
			// is outside the rule-based dispatcher's output vocabulary — ignore.
			else -> null
		}
}
