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
 * @see AgentLoopDriver
 * @see NoOpSimulationController
 * @since Issue #564 (SP4.2 — Goal 10 close the loop)
 */
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
	 * with the interrupt status preserved, so the driver loop can observe
	 * `isSimActive() == false` and exit cleanly instead of dying on an exception.
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
