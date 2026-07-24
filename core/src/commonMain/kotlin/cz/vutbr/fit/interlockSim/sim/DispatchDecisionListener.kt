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
 * Observer notified by the dispatcher decision-applier whenever a
 * [DispatchDecision] is actually applied on the kDisco simulation thread.
 *
 * ## Purpose (SP2b.6 — Goal 10, Issue #561)
 *
 * The `:desktop-ui` `DispatcherControlPanel` exposes a "Why this route?" button
 * that displays the rationale of the last applied dispatcher decision. To make
 * that button functional, the sim-thread applier
 * (`:dispatcher-agent` `DispatchDecisionApplier`) must push every applied
 * decision to the GUI panel. This interface is the seam: the applier holds a
 * `DispatchDecisionListener?` and calls [onDecisionApplied] for each applied,
 * non-`NoAction` decision; a `:desktop-ui` wiring attaches a sink that forwards
 * the decision (on the EDT) to the panel.
 *
 * Living in `:core` `commonMain` (alongside [DispatchDecision] and
 * [DispatcherModeState]) lets both `:dispatcher-agent` (the applier) and
 * `:desktop-ui` (the GUI wiring) depend on it without creating a module edge.
 *
 * ## Threading
 *
 * [onDecisionApplied] is invoked on the kDisco simulation thread. Implementations
 * that touch Swing **must** dispatch the work to the EDT themselves (e.g. via
 * `SwingUtilities.invokeLater`). See [DispatchDecisionListenerHub] for a
 * thread-safe, mutable sink holder.
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 * @see DispatchDecisionListenerHub
 */
fun interface DispatchDecisionListener {
	/**
	 * Called on the simulation thread for each applied, non-`NoAction`
	 * [DispatchDecision]. Dropped decisions (e.g. under `DispatcherMode.MANUAL`)
	 * and `NoAction` do not trigger this callback.
	 */
	fun onDecisionApplied(decision: DispatchDecision)
}

/**
 * Thread-safe, mutable [DispatchDecisionListener] sink holder.
 *
 * The dispatcher decision-applier captures a `DispatchDecisionListenerHub` at
 * construction time (pulled from the per-context Koin scope), but the GUI panel
 * is only wired to its context later — when the simulation starts. The hub
 * bridges that timing gap: the applier calls [onDecisionApplied] (which forwards
 * to the current [sink]); the GUI later points [setSink] at a lambda that pushes
 * the decision to `DispatcherControlPanel.updateDecisionRationale` on the EDT.
 *
 * Headless/console runs leave [sink] `null`, so [onDecisionApplied] is a no-op —
 * backward compatible with callers that never attach a GUI.
 *
 * ## Thread safety
 *
 * [sink] is guarded by an internal [kotlinx.atomicfu.locks.SynchronizedObject]
 * lock, mirroring [DispatcherModeState]: the applier reads it on the sim thread
 * and the GUI writes it on the EDT. [DispatchDecision] instances are immutable,
 * so a last-writer-wins snapshot of the sink reference is safe.
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 * @see DispatchDecisionListener
 */
class DispatchDecisionListenerHub : DispatchDecisionListener {
	private val lock = SynchronizedObject()

	/**
	 * The current sink, or `null` when no GUI is attached. Guarded by [lock].
	 * The sink is responsible for any thread dispatch it needs (e.g. EDT).
	 */
	private var sink: ((DispatchDecision) -> Unit)? = null

	/**
	 * Installs [sink] as the active forwarder, replacing any previous one.
	 *
	 * Pass `null` to detach (e.g. when a simulation stops, so the sim thread can
	 * no longer push into a stale panel).
	 */
	fun setSink(sink: ((DispatchDecision) -> Unit)?) {
		synchronized(lock) {
			this.sink = sink
		}
	}

	/**
	 * Forwards [decision] to the current [sink]. A no-op when no sink is set.
	 *
	 * Called on the kDisco simulation thread by the decision-applier.
	 */
	override fun onDecisionApplied(decision: DispatchDecision) {
		val current = synchronized(lock) { sink }
		current?.invoke(decision)
	}
}
