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
 * Control seam between the kDisco simulation kernel and dispatch policy.
 *
 * A [Dispatcher] encapsulates **what to do** (train admission and forward-path
 * reservation policy) independently of **how the simulation steps** (kDisco
 * process scheduling and `hold()`/`passivate()` mechanics), as a pure decision
 * function: given an observed snapshot, return the list of decisions to apply.
 *
 * [decide] must not mutate simulation state or retain [observed] beyond the call.
 * All effects are expressed as the returned [DispatchDecision]s; the caller
 * (currently [ShuntingLoop]) applies them. This keeps the contract free of mutable
 * simulation internals so that alternative dispatcher implementations (LLM-backed,
 * search-based, …) can be plugged in behind this seam without touching `:core`
 * simulation code.
 *
 * ## One call per tick (SP0.11)
 * The shell calls [decide] once per iteration with a single [DispatchObservation]
 * whose fields are all populated together: the queued-train list and both
 * block-input lists are snapshotted in the same tick (Issue #733 thin-shell
 * refactor). Admission and path-advancement are therefore decided in one call,
 * and both may be non-empty. The historical two-call pre/post-hold split
 * (Issue #540 review) was removed because SP0.11 moved admission to pre-hold as
 * well, so a single pre-hold observation is the correct input for both.
 *
 * ## Thread-safety
 *
 * The kDisco simulation kernel runs on its own single thread, but [decide] is
 * invoked from **outside** the kernel — by the external drive-loop driver (SP0.10,
 * #732) on its own thread/coroutine, not the kDisco thread. Implementations must
 * therefore not rely on kDisco-thread exclusivity: keep them pure and stateless
 * ([DispatchObservation] already carries everything the policy needs — invariant 4
 * of the SP0.5 design spec, `docs/specs/2026-07-08-544-sp05-drive-loop-design.md`),
 * or synchronise any mutable state held across calls.
 *
 * @see RuleBasedDispatcher
 * @see DispatchObservation
 * @see DispatchDecision
 * @since Issue #540 (SP0.1 — Goal 10); reshaped to a pure seam in Issue #729
 *   (SP0.7 — Goal 10)
 */
fun interface Dispatcher {
	/**
	 * Decide what to do given [observed].
	 *
	 * @param observed Read-only view of the current dispatch-relevant state for
	 *   this tick.
	 * @return The decisions to apply this tick, in the order they should be
	 *   applied. Never empty — implementations return `listOf(DispatchDecision.NoAction)`
	 *   when there is nothing to dispatch.
	 */
	fun decide(observed: DispatchObservation): List<DispatchDecision>
}
