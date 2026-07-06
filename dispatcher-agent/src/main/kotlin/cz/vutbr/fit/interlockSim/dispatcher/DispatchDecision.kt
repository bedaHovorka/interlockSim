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

/**
 * Represents a single dispatcher decision that was enacted during a simulation tick.
 *
 * This sealed class is the **result type** of a [Dispatcher][cz.vutbr.fit.interlockSim.sim.Dispatcher]
 * invocation, allowing callers (tests, logging, the preference store) to inspect what
 * the dispatcher chose to do.
 *
 * ## Status at SP0.1
 *
 * This class is a **placeholder skeleton** created in Issue #540 (SP0.1) to establish
 * the seam type in `:dispatcher-agent`.  The concrete subtypes for path reservation,
 * train approval, and hold decisions will be filled in during SP2b.1 (Issue #556,
 * Dispatcher interface + DispatchDecision).  The SP0.1 [RuleBasedDispatcher][cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher]
 * returns no decision objects yet — it acts internally.
 *
 * ## Intended subtypes (SP2b.1)
 *
 * - `ApproveTrain(trainId: String)` — a queued train was admitted to the simulation
 * - `ReservePath(trainId: String, fromSemaphore: String)` — a forward path was reserved
 * - `HoldTrain(trainId: String, durationSeconds: Double)` — a train was held (conflict
 *   resolution via [cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService])
 * - `NoAction` — nothing to dispatch this tick
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */
sealed class DispatchDecision {
	/**
	 * A train was approved (moved from the unapproved queue to the active set and
	 * activated in the kDisco simulation).
	 *
	 * @property trainId The name/identifier of the approved train.
	 */
	data class ApproveTrain(
		val trainId: String
	) : DispatchDecision()

	/**
	 * A forward path reservation was made from [fromSemaphoreName] for [trainId].
	 *
	 * @property trainId The train for which the path was reserved.
	 * @property fromSemaphoreName The semaphore the path was reserved from.
	 */
	data class ReservePath(
		val trainId: String,
		val fromSemaphoreName: String
	) : DispatchDecision()

	/**
	 * No dispatch action was taken this tick.
	 */
	data object NoAction : DispatchDecision()
}
