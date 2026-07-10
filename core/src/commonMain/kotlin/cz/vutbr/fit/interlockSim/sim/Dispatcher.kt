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
 * ## Two-phase tick
 * The shell calls [decide] twice per iteration: once **before** the per-iteration
 * polling `hold()` with an admission-phase [DispatchObservation] (real
 * [DispatchObservation.unapprovedTrains], empty block-input lists), and once
 * **after** it with a path-advancement-phase observation (empty
 * [DispatchObservation.unapprovedTrains], real block-input lists). Splitting the
 * tick keeps train admission aligned with the polling interval so that
 * path-advancement checks observe block state after newly admitted trains have
 * had a chance to move — the original `ShuntingLoop` ordering (Issue #540
 * review). A single combined call would force both decisions to observe the same
 * pre-hold state.
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
interface Dispatcher {
	/**
	 * Decide what to do given [observed].
	 *
	 * @param observed Read-only view of the current dispatch-relevant state for
	 *   this phase of the tick.
	 * @return The decisions to apply this phase, in the order they should be
	 *   applied. Never empty — implementations return `listOf(DispatchDecision.NoAction)`
	 *   when there is nothing to dispatch.
	 */
	fun decide(observed: DispatchObservation): List<DispatchDecision>
}
