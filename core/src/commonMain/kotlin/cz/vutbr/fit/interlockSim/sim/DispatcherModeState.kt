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
 * Thread-safe holder for the current [DispatcherMode] with a persistent override.
 *
 * ## State model (Issue #532 §B1)
 *
 * The holder tracks two logical values:
 *
 * 1. A **default mode** — the mode the dispatcher falls back to when no override is
 *    active.  This is fixed at construction time (defaults to [DispatcherMode.DEFAULT]
 *    = [DispatcherMode.AUTO]) and represents the automatic-operation baseline.
 * 2. An **override** — an optional [DispatcherMode] set by a human operator.  When
 *    present it takes precedence over the default and is returned by
 *    [getEffectiveMode].  The override persists until explicitly cleared via
 *    [clearOverride] — it does **not** decay after a decision, a simulation tick,
 *    or a timeout (Issue #559 explicit requirement).
 *
 * The current effective mode returned by [getEffectiveMode] is therefore:
 *
 * ```
 *   override ?: defaultMode
 * ```
 *
 * ## Thread safety
 *
 * All state is guarded by an internal [kotlinx.atomicfu.locks.SynchronizedObject]
 * lock, so the holder is safe to share between the driver thread (which reads the
 * mode when gating decisions), the sim thread (via
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]),
 * and any GUI thread (which may set/clear the override in response to operator
 * input).  Multiple concurrent writers observe a linearizable last-writer-wins
 * outcome, which matches the operator-input UX (there is only ever one human at
 * the console).
 *
 * ## Example
 *
 * ```kotlin
 * val state = DispatcherModeState()                                // default: AUTO
 * assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
 * assertThat(state.hasOverride()).isFalse()
 *
 * // Human takes control:
 * state.setOverride(DispatcherMode.MANUAL)
 * assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.MANUAL)
 * assertThat(state.hasOverride()).isTrue()
 *
 * // Human hands control back:
 * state.clearOverride()
 * assertThat(state.getEffectiveMode()).isEqualTo(DispatcherMode.AUTO)
 * assertThat(state.hasOverride()).isFalse()
 * ```
 *
 * @property defaultMode The mode used when no override is active.  Immutable after
 *   construction; changing the default requires creating a new holder.
 *
 * @since Issue #559 (SP2b.4 — Goal 10)
 */
class DispatcherModeState(
	val defaultMode: DispatcherMode = DispatcherMode.DEFAULT
) {
	private val lock = SynchronizedObject()

	/**
	 * The current human override, or `null` if no override is active.  When non-null,
	 * takes precedence over [defaultMode] in [getEffectiveMode].  Guarded by [lock].
	 */
	private var overrideMode: DispatcherMode? = null

	/**
	 * Returns the mode that should currently govern the dispatcher — the override if
	 * one is active, otherwise [defaultMode].
	 *
	 * Safe to call concurrently from any thread; the returned value is a coherent
	 * snapshot of the moment the call was made.
	 */
	fun getEffectiveMode(): DispatcherMode =
		synchronized(lock) {
			overrideMode ?: defaultMode
		}

	/**
	 * `true` if a human override is currently active (i.e. [getEffectiveMode] returns
	 * the override rather than [defaultMode]), `false` otherwise.
	 *
	 * Note that setting the override to the same value as [defaultMode] still counts
	 * as an active override for the purposes of this method — the override is present
	 * in state even though it happens to coincide with the default.  Callers that
	 * only care whether the effective mode has changed should compare
	 * [getEffectiveMode] against [defaultMode] directly.
	 */
	fun hasOverride(): Boolean =
		synchronized(lock) {
			overrideMode != null
		}

	/**
	 * Sets a human override to [mode].  Replaces any existing override.  The override
	 * persists until [clearOverride] is called; it does not decay automatically.
	 *
	 * @param mode The mode the operator wishes to switch to.
	 */
	fun setOverride(mode: DispatcherMode) {
		synchronized(lock) {
			overrideMode = mode
		}
	}

	/**
	 * Clears any active override, returning the effective mode to [defaultMode].
	 *
	 * Safe to call when no override is active — it is a no-op in that case.
	 */
	fun clearOverride() {
		synchronized(lock) {
			overrideMode = null
		}
	}
}
