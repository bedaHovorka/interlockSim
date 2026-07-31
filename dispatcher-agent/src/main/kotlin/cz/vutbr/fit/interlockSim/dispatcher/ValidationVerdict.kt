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
 * Outcome of [ActionValidator.validate] for one [DispatchAction] (SP2c.3, Issue #826).
 *
 * Exactly two subtypes exist:
 *
 * - [Valid] — the action passed all pre-execution checks and may be converted to a
 *   [cz.vutbr.fit.interlockSim.sim.DispatchDecision] and posted to the
 *   [ActuatorCommandQueue].
 * - [Rejected] — the action was rejected before any mutation occurred; [Rejected.code]
 *   is the machine-readable reason the dispatcher can read on the next tick.
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
sealed interface ValidationVerdict {
	/**
	 * The action passed all [ActionValidator] checks and is safe to execute.
	 */
	data object Valid : ValidationVerdict

	/**
	 * The action was rejected by [ActionValidator].
	 *
	 * @property code Machine-readable reason code. The same code that will appear in the
	 *   next tick's applied-outcomes channel (SP2c.17) so the LLM can adapt its next decision.
	 * @property detail Human-readable explanation. Never empty; always explains which field
	 *   was invalid or which state constraint was violated.
	 */
	data class Rejected(
		val code: RejectionCode,
		val detail: String
	) : ValidationVerdict
}
