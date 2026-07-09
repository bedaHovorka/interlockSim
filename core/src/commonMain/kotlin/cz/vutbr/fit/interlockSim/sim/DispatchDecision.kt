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

/**
 * Represents a single dispatcher decision returned by [Dispatcher.decide].
 *
 * This sealed class is the **result type** of the pure [Dispatcher] seam
 * (Issue #729 / SP0.7): a decision is a *request*, not yet an accomplished fact —
 * the caller ([ShuntingLoop]) applies it afterward, and application can still fail
 * (e.g. a path reservation conflict), so subtype documentation is worded as
 * "the dispatcher decided to …", not "… was …".
 *
 * Moved from `:dispatcher-agent` into `:core` by Issue #729, since [Dispatcher]
 * and [RuleBasedDispatcher] now live in `:core` and `:dispatcher-agent` depends on
 * `:core` (never the reverse).
 *
 * ## Intended subtypes
 *
 * - [ApproveTrain] — a queued train should be admitted to the simulation.
 * - [ReservePath] — a forward path should be reserved.
 * - [NoAction] — nothing to dispatch this tick.
 *
 * `HoldTrain` and any switch/signal/speed subtypes are explicitly **deferred** to
 * Issue #556 (SP2b.1) — this slice only introduces what the existing
 * [RuleBasedDispatcher] needs to emit.
 *
 * @since Issue #540 (SP0.1 — Goal 10), moved to `:core` and reworded in Issue #729
 *   (SP0.7 — Goal 10)
 */
sealed class DispatchDecision {
	/**
	 * The dispatcher decided to approve a queued train (move it from the
	 * unapproved queue to the active set and activate it in the kDisco
	 * simulation).
	 *
	 * @property trainId The name/identifier of the train to approve.
	 */
	data class ApproveTrain(
		val trainId: String
	) : DispatchDecision()

	/**
	 * The dispatcher decided to reserve a forward path of **one section** from
	 * [fromSemaphoreName] to [toSeparatorName] for [trainId].
	 *
	 * [fromSemaphoreName] is the semaphore the train is approaching (the value the
	 * shell carried as [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.towardSemaphoreName]);
	 * [toSeparatorName] is the next separator one section ahead toward the train's
	 * destination — a semaphore, or the destination InOut for the final section. The
	 * shell pre-computes `to` as the first FREE next separator
	 * ([BlockInputObservation.toSeparatorName]) so the applier can call
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath]
	 * directly. `to` is never the far destination as a multi-section shortcut: one
	 * section is reserved per decision, matching the pre-#729
	 * `reservePathToAnyNextSemaphore` outcome.
	 *
	 * @property trainId The train to reserve a path for.
	 * @property fromSemaphoreName The semaphore to reserve the path from (the
	 *   semaphore the train is approaching).
	 * @property toSeparatorName The separator to reserve the path to (the next
	 *   separator toward the destination — a semaphore, or the destination InOut
	 *   for the final section).
	 */
	data class ReservePath(
		val trainId: String,
		val fromSemaphoreName: String,
		val toSeparatorName: String
	) : DispatchDecision()

	/**
	 * No dispatch action should be taken this tick.
	 */
	data object NoAction : DispatchDecision()
}
