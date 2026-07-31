/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation

/**
 * Deterministic cross-tick scratchpad carried into every [RenderContext] (SP2c.7, Issue #830).
 *
 * All fields are **pure functions of the recorded [TickRecord] sequence plus
 * [DispatcherObservation]s**: given the same inputs, [update] always returns `==` results.
 * There is no LLM-state dependency here — the memory is updated by the caller (the control-loop
 * harness) before passing it into [RenderContext], never inside a renderer.
 *
 * ## Fields
 *
 * - [pendingRequests] — actions emitted by the dispatcher whose [AppliedOutcome] has not yet
 *   been received. Updated by [update]: new [AttributedAction]s are added; entries whose
 *   matching [AppliedOutcome] (by [CommandId]) arrives are removed; entries older than
 *   [PENDING_TTL_TICKS] ticks are dropped and surfaced in [droppedThisTick].
 *
 * - [lastActionPerTrain] — most-recently taken action per `trainId`, as `(kind, tick)`. Reset
 *   when no action targets that train; retained across ticks until overwritten.
 *
 * - [ticksSinceProgress] — number of consecutive ticks since the last *progress* event.
 *   **Mechanical definition:** any non-empty [TickRecord.outcomes] on the incoming [record]
 *   (i.e. at least one actuator outcome arrived this tick — a route reservation, a train
 *   admission, or a TTL-expiry drop) resets this counter to 0; otherwise it increments by 1.
 *   This definition is testable per branch and requires no LLM input.
 *
 * - [activeCount] — number of currently approved trains. Taken directly from
 *   [DispatcherObservation.activeCount] (the observation passed to [update]).
 *
 * - [capacity] — station capacity. Taken directly from
 *   [DispatcherObservation.capacity] (the observation passed to [update]).
 *
 * - [droppedThisTick] — [AttributedAction] entries for pending requests that expired by TTL
 *   during the last [update] call. Reset to empty on the next [update] call. Callers may
 *   render these (see [CompactTextRenderer]) so the drops are surfaced instead of silently
 *   vanishing.
 *
 * ## TTL for [pendingRequests]
 *
 * An action whose outcome never arrives — dropped by SEMI_AUTO gating or by an applier exception
 * — would leak forever and pollute every subsequent prompt. After [PENDING_TTL_TICKS] ticks
 * without a matching outcome, the entry is removed and recorded in [droppedThisTick]. The expiry
 * is never silent: the caller can include [droppedThisTick] in the rendered context so the model
 * knows the action was abandoned.
 *
 * @since Issue #830 (SP2c.7 — Goal 10 ring buffer)
 */
data class WorkingMemory(
	/**
	 * Actions emitted by the dispatcher whose [AppliedOutcome] has not yet arrived, keyed by
	 * [CommandId]. Entries are added from [TickRecord.actions] (non-[DispatchAction.NoOp]
	 * only) and removed when the matching [AppliedOutcome.id] appears in
	 * [TickRecord.outcomes] or when the entry expires by [PENDING_TTL_TICKS].
	 */
	val pendingRequests: Map<CommandId, AttributedAction>,
	/**
	 * Most recent action taken per train: `trainId → (kind, tick)`.
	 *
	 * Updated from [TickRecord.actions]: for each [AttributedAction] targeting a named train
	 * (all except [DispatchAction.NoOp]), the entry is set to `Pair(action.kind, record.tick)`.
	 * Entries for a train are retained until overwritten by a later action for that train.
	 */
	val lastActionPerTrain: Map<String, Pair<String, Long>>,
	/**
	 * Number of consecutive most-recent ticks without a progress event.
	 *
	 * Resets to `0` when [TickRecord.outcomes] is non-empty (at least one actuator outcome
	 * arrived — a route reservation, admission, or TTL-expiry drop). Increments by `1`
	 * otherwise. A high value indicates the dispatcher may be stalled.
	 */
	val ticksSinceProgress: Int,
	/**
	 * Number of currently approved (active) trains. Taken from
	 * [DispatcherObservation.activeCount] passed to [update].
	 */
	val activeCount: Int,
	/**
	 * Station capacity (maximum concurrently active trains). Taken from
	 * [DispatcherObservation.capacity] passed to [update].
	 */
	val capacity: Int,
	/**
	 * [AttributedAction] entries for [pendingRequests] entries that expired by TTL during the
	 * most recent [update] call.
	 *
	 * Reset to empty at the start of each [update] call. Callers may include these in the
	 * next tick's [TickRecord.outcomes] to surface the drops in the ring buffer and prevent
	 * the information-starvation failure mode (Issue #830 — "the model watches an action
	 * silently vanish"). [CompactTextRenderer.renderWorkingMemory] now renders [droppedThisTick]
	 * directly as a `dropped:` line in the WORKING MEMORY section, so drops are surfaced in the
	 * prompt this tick — the "never silently dropped" claim holds at the render layer.
	 *
	 * Each entry's [AttributedAction.tick] is overwritten to the tick the expiry was
	 * *detected* on (the [update] call's `record.tick`), not the tick the action was
	 * originally posted — matching the "publish-sequence number of the tick the command was
	 * applied on" semantics of a real [AppliedOutcome].
	 */
	val droppedThisTick: List<AttributedAction> = emptyList()
) {
	/**
	 * Produces the next [WorkingMemory] from the just-completed [record] and the current
	 * [observation].
	 *
	 * This is a **pure function**: calling it with the same [record] and [observation] on
	 * the same [WorkingMemory] always returns an `==` result.
	 *
	 * ## Update steps
	 *
	 * 1. Collect TTL-expired entries from [pendingRequests] (age > [PENDING_TTL_TICKS]).
	 * 2. Remove matched entries from [pendingRequests] using [AppliedOutcome.id].
	 * 3. Add new non-NoOp [AttributedAction]s from [record.actions] to [pendingRequests].
	 * 4. Update [lastActionPerTrain] from [record.actions].
	 * 5. Recompute [ticksSinceProgress]: reset to 0 if [record.outcomes] is non-empty,
	 *    otherwise increment.
	 * 6. Set [activeCount] and [capacity] from [observation].
	 * 7. Store dropped entries in [droppedThisTick].
	 *
	 * @param record The [TickRecord] just completed (ring-buffer record for this tick).
	 * @param observation The [DispatcherObservation] captured this tick.
	 * @return New [WorkingMemory] reflecting the updated state.
	 */
	fun update(
		record: TickRecord,
		observation: DispatcherObservation
	): WorkingMemory {
		val currentTick = record.tick

		// Step 1: find TTL-expired entries
		val expired =
			pendingRequests.filter { (_, aa) ->
				currentTick - aa.tick >= PENDING_TTL_TICKS
			}

		// Step 2: ids resolved by a real outcome arriving this tick
		val resolvedIds =
			record.outcomes
				.map { it.id }
				.toSet()

		// Build new pending: remove expired + resolved, keep the rest
		val surviving =
			pendingRequests.filter { (id, _) ->
				id !in expired.keys && id !in resolvedIds
			}

		// Step 3: add new non-NoOp actions
		val newPending: Map<CommandId, AttributedAction> =
			surviving +
				record.actions
					.filter { aa -> aa.action !is DispatchAction.NoOp }
					.associateBy { aa -> aa.commandId }

		// Step 4: update lastActionPerTrain
		val newLastActions: Map<String, Pair<String, Long>> =
			lastActionPerTrain +
				record.actions
					.mapNotNull { aa ->
						trainIdOf(aa.action)?.let { trainId ->
							trainId to Pair(aa.action.kind, currentTick)
						}
					}.toMap()

		// Step 5: recompute ticksSinceProgress
		// Progress = any outcome arrived this tick (real or synthetic TTL drops included)
		val newTicksSinceProgress =
			if (record.outcomes.isNotEmpty()) 0 else ticksSinceProgress + 1

		// Step 7: expired entries surface as droppedThisTick. An entry that is also in
		// resolvedIds was resolved by a real AppliedOutcome on this tick (age == PENDING_TTL_TICKS);
		// reporting it as dropped too would double-report it. The reported tick is the tick the
		// expiry was *detected* on (currentTick), not the tick the action was originally posted —
		// matching the superseded flat-AppliedOutcome design this replaces (tick = "publish-sequence
		// number of the tick the command was applied on").
		val dropped =
			expired
				.filterKeys { id -> id !in resolvedIds }
				.values
				.map { aa -> aa.copy(tick = currentTick) }

		return WorkingMemory(
			pendingRequests = newPending,
			lastActionPerTrain = newLastActions,
			ticksSinceProgress = newTicksSinceProgress,
			activeCount = observation.activeCount,
			capacity = observation.capacity,
			droppedThisTick = dropped
		)
	}

	companion object {
		/**
		 * Number of ticks after which a pending request is considered expired and dropped.
		 * Prevents unbounded growth of [pendingRequests] when actuator outcomes never arrive
		 * (e.g. SEMI_AUTO gating, applier exception).
		 */
		const val PENDING_TTL_TICKS: Long = 5L

		/**
		 * Label used when rendering [droppedThisTick] entries. Rendered verbatim in the WORKING
		 * MEMORY prompt section so the model knows an action was abandoned without an outcome.
		 */
		const val DROPPED_NO_OUTCOME: String = "DROPPED_NO_OUTCOME"

		/**
		 * Safe zero-state memory for tick 0, before any history has accumulated.
		 */
		val EMPTY: WorkingMemory =
			WorkingMemory(
				pendingRequests = emptyMap(),
				lastActionPerTrain = emptyMap(),
				ticksSinceProgress = 0,
				activeCount = 0,
				capacity = 0
			)
	}
}
