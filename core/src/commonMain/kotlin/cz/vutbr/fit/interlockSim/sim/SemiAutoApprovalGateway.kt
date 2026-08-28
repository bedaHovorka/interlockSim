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

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe, mutable holder for the SEMI_AUTO approval callback.
 *
 * ## Purpose (SP2b.6 follow-up — Goal 10, Issue #806)
 *
 * When the dispatcher operates in [DispatcherMode.SEMI_AUTO], every non-[DispatchDecision.NoAction]
 * decision must be forwarded to a human operator for approval before it reaches the actuator ports.
 * The `:desktop-ui` `SemiAutoApprovalDialog` is the concrete approver, but it is only available
 * after the simulation starts and the Frame wires itself.
 *
 * The gateway bridges this timing gap in the same way [DispatchDecisionListenerHub] bridges the
 * decision-observer gap:
 * - The `:dispatcher-agent` `DispatchDecisionApplier` captures a `SemiAutoApprovalGateway` at
 *   construction time (pulled from the per-context Koin scope) and passes `gateway::approve` as
 *   its `semiAutoApprover` callback.
 * - The `:desktop-ui` `Frame.wireDispatcherControlPanel` later calls [setApprover] to install a
 *   blocking dialog-based approver that shows the pending decision to the operator on the EDT.
 * - On simulation stop, `Frame` calls `setApprover(null)` to detach, preventing a stale approver
 *   from being called after the GUI is torn down.
 *
 * ## Threading
 *
 * [approve] is called **synchronously on the kDisco simulation thread** from
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * inside [DispatchDecisionApplier.onControlStep]. The approver installed via [setApprover] may
 * block the sim thread (e.g. by calling `SwingUtilities.invokeAndWait` to show a modal dialog) —
 * this is intentional in SEMI_AUTO mode; the simulation waits for the operator's decision.
 *
 * [setApprover] may be called from the EDT (GUI wiring). The internal lock prevents data races
 * between the sim-thread reader ([approve]) and the EDT writer ([setApprover]).
 *
 * ## No-approver behaviour
 *
 * When no approver is installed ([setApprover] not yet called, or called with `null`), [approve]
 * returns `false` — matching the "drop with a warning" fallback in
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * for the case where `semiAutoApprover` is wired but the underlying approver is absent.
 *
 * @since Issue #806 (SP2b.6 follow-up — Goal 10)
 * @see DispatchDecisionListenerHub for the analogous decision-observer pattern
 * @see DispatcherMode.SEMI_AUTO
 */
class SemiAutoApprovalGateway {
	private val lock = SynchronizedObject()

	/**
	 * The current blocking approver, or `null` when the GUI has not yet attached one
	 * (or has already detached — e.g. after simulation stops). Guarded by [lock].
	 */
	private var approver: ((DispatchDecision) -> Boolean)? = null

	/**
	 * Installs [approver] as the active approver, replacing any previous one.
	 *
	 * Pass `null` to detach — e.g. on simulation stop, so the sim thread cannot call a
	 * stale approver that references a disposed dialog.
	 *
	 * Must be called from the EDT when attaching a Swing-based approver.
	 *
	 * @param approver A blocking callback invoked on the sim thread; must return `true` to
	 *   apply the decision or `false` to drop it.  May call `SwingUtilities.invokeAndWait`
	 *   to hand off the approval UI to the EDT.  Pass `null` to detach.
	 */
	fun setApprover(approver: ((DispatchDecision) -> Boolean)?) {
		synchronized(lock) {
			this.approver = approver
		}
	}

	/**
	 * Asks the installed approver whether [decision] should be applied.
	 *
	 * Returns `true` when the approver approves, `false` when the approver rejects or when
	 * no approver is currently installed.
	 *
	 * Called on the kDisco simulation thread by [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
	 * May block while the approver shows a modal dialog (expected behaviour in SEMI_AUTO mode).
	 *
	 * @param decision The pending dispatcher decision that requires operator approval.
	 * @return `true` to apply, `false` to drop.
	 */
	fun approve(decision: DispatchDecision): Boolean {
		val current = synchronized(lock) { approver }
		return current?.invoke(decision) ?: false
	}
}
