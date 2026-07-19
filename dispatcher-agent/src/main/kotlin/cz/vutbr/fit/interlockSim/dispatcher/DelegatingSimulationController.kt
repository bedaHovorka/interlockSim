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

import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationController

/**
 * Late-bound pacing controller for the agent-driver loop (SP4.2, Issue #564).
 *
 * Closes the pacing gap between the wiring layer and the GUI runtime: the
 * [AgentLoopDriver] is constructed (and its [SimulationController] fixed) when the
 * simulation context is created, but the real pacing implementation — the GUI's
 * `SimulationRunner` — only exists once the user starts the simulation. This class
 * is wired into the driver up-front with [delegate] defaulting to
 * [NoOpSimulationController]; the GUI swaps in the live `SimulationRunner` when the
 * run starts and swaps [NoOpSimulationController] back in when it stops. The agent
 * loop is thereby **paced by the existing real-time sync via `SimulationRunner`**
 * (speed multiplier, pause) without the driver ever holding a reference to a
 * GUI class.
 *
 * ## Why pause-waiting polls instead of delegating [awaitIfPaused]
 *
 * `SimulationRunner.awaitIfPaused` **consumes** pending single-step requests — that
 * consumption belongs exclusively to the kDisco simulation thread's controlled event
 * loop. If the driver thread called it too, the driver could steal a step-event
 * intended to advance the simulation by one event, breaking GUI stepping. This
 * controller therefore never forwards [awaitIfPaused], [pollStepEvent], or
 * [pollStepTime] to the delegate; it waits out a pause by polling
 * [SimulationController.isPaused] (read-only) instead. While paused, the simulation
 * publishes no new observations, so a driver that merely sleeps loses nothing.
 *
 * ## Threading
 *
 * [delegate] is `@Volatile`: it is written by the GUI/control thread
 * (`gui.SimulationController.start`/`stop`) and read by the dedicated
 * `dispatcher-agent-driver` daemon thread. Blocking `Thread.sleep` inside
 * [awaitIfPaused] is safe for the same reason `AgentLoopDriver` may block: the
 * driver runs on its own daemon thread, never on a shared coroutine pool
 * (SP0.5 invariant — the kDisco kernel never blocks on the driver).
 *
 * ## SonarCloud suppressions
 *
 * `@Suppress("kotlin:S6307")` — [awaitIfPaused] deliberately uses `Thread.sleep` (not
 * `delay()`); see its KDoc. It is `suspend` only because the [SimulationController] interface
 * forces it; the method blocks a dedicated daemon thread and handles interruption
 * cooperatively, which `delay()`/`CancellationException` cannot express without either
 * busy-spinning or exiting early.
 *
 * `@Suppress("kotlin:S6514")` — interface delegation `by` requires a stable expression, but
 * [delegate] is a `@Volatile var` swapped at runtime (NoOp ↔ live `SimulationRunner`); `by`
 * cannot follow a mutable target. The class also deliberately overrides
 * [pollStepEvent]/[pollStepTime] to NOT forward (they protect the sim thread's step-request
 * ownership), which `by` would undo.
 *
 * @see AgentLoopDriver
 * @see NoOpSimulationController
 * @since Issue #564 (SP4.2 — Goal 10 close the loop)
 */
@Suppress("kotlin:S6307", "kotlin:S6514")
class DelegatingSimulationController : SimulationController {
	/**
	 * Current pacing target. [NoOpSimulationController] until a live pacing controller
	 * (the GUI's `SimulationRunner`) is attached; reset to [NoOpSimulationController]
	 * when the simulation stops so the driver never paces against a dead runner.
	 */
	@Volatile
	var delegate: SimulationController = NoOpSimulationController

	/**
	 * Waits (by polling) while the [delegate] reports paused.
	 *
	 * Intentionally does NOT call `delegate.awaitIfPaused()` — see the class KDoc:
	 * the delegate's implementation may consume step requests that belong to the
	 * simulation thread. Returns promptly once the delegate resumes, or immediately
	 * if the driver thread is interrupted (interrupt status is preserved).
	 *
	 * Uses `Thread.sleep` (not `delay()`) deliberately: this `suspend` function is forced
	 * by the [SimulationController] interface contract, but it runs on the dedicated
	 * `dispatcher-agent-driver` daemon thread inside `runBlocking`. The poll-sleep +
	 * `catch (InterruptedException) { interrupt(); return }` pattern is the correct
	 * cooperative-interruption idiom for a blocking dedicated thread — on interrupt it
	 * stops sleeping and returns so the driver's `while (isSimActive())` loop can re-check.
	 * `delay()` would surface interruption as `CancellationException`, which cannot be
	 * swallowed (busy-spin risk inside a cancelled coroutine) nor cleanly propagated
	 * (would end the driver thread early instead of looping until `isSimActive()` is false).
	 * See the class-level `@Suppress("kotlin:S6307")`.
	 */
	override suspend fun awaitIfPaused() {
		while (delegate.isPaused()) {
			try {
				Thread.sleep(PAUSE_POLL_MS)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return
			}
		}
	}

	/**
	 * Forwards wall-clock throttling to the [delegate].
	 *
	 * An interrupt during the delegate's sleep (e.g. simulation shutdown) is swallowed
	 * with the interrupt status preserved. The driver loop exits via its own
	 * `isSimActive()` check, not via this interrupt: preserving the flag lets any
	 * cooperative-shutdown check elsewhere observe it, while swallowing the exception
	 * keeps the driver from dying mid-throttle before it can re-check `isSimActive()`.
	 */
	override fun throttle(simDeltaSeconds: Double) {
		try {
			delegate.throttle(simDeltaSeconds)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

	override fun isPaused(): Boolean = delegate.isPaused()

	/** Never consumes step-event requests — those belong to the simulation thread. */
	override fun pollStepEvent(): Boolean = false

	/** Never consumes step-time requests — those belong to the simulation thread. */
	override fun pollStepTime(): Double? = null

	override fun requestPause() {
		delegate.requestPause()
	}

	companion object {
		/** Poll interval while waiting out a pause; coarse is fine — no new observations arrive while paused. */
		private const val PAUSE_POLL_MS: Long = 10L
	}
}
