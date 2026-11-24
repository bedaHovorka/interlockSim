/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.SimulationTimeTracker
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import javax.swing.Timer

/**
 * The simulation clock shown in [ControlPanel] (Issue #1421): single owner of the 10 Hz update
 * timer, of the [SimulationTimeTracker] that follows the context's event stream, and of the time
 * the clock shows now.
 *
 * [startUpdates] creates the Swing `Timer` (10 Hz, EDT) that calls [refresh]; the shown time
 * ratchets within one run (it never goes backward) and resets when a fresh tracker is attached,
 * i.e. with each new run. The clock reads the simulation thread's paced position through
 * [pacedTime], which [Frame] wires to the runner, so this class never touches
 * `SimulationController`.
 *
 * **All methods are EDT-only.**
 */
internal class SimulationClock(
	private val controlPanel: ControlPanel,
	private val pacedTime: () -> Double
) {
	/**
	 * The tracker that reads the current run's time from its context's event stream. The clock
	 * cannot ask kDisco itself: `Process.time()` reads a thread-local context, so off the
	 * simulation thread the read fails and only the tracker's last event time shows.
	 */
	var tracker: SimulationTimeTracker? = null
		private set

	/** The context [tracker] listens to, so a new tracker can detach the old one. */
	private var trackedContext: Context<*, *>? = null

	/** The time the clock shows now; reset with each new tracker, i.e. with each new run. */
	private var shown: Double = 0.0

	private var updateTimer: Timer? = null

	/**
	 * Replaces [tracker] with a fresh one on [context], or with none when [context] is `null`
	 * (editing mode). A fresh tracker per run starts the clock at zero again (Issue #1421).
	 */
	fun attach(context: Context<*, *>?) {
		tracker?.let { old -> trackedContext?.removePropertyChangeListener(old) }
		val fresh = context?.let { SimulationTimeTracker() }
		fresh?.let { context.addPropertyChangeListener(it) }
		tracker = fresh
		trackedContext = context
		shown = 0.0
	}

	/**
	 * Start the 10 Hz update timer (Issue #205).
	 *
	 * - 100 ms interval (10 Hz) provides responsive display without excessive CPU overhead
	 * - Much less frequent than 30 FPS rendering, but frequent enough for smooth time display
	 * - Swing Timer automatically runs on EDT (thread-safe)
	 *
	 * **Must be called from EDT.**
	 */
	fun startUpdates() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startUpdates must be called from EDT"
		}

		updateTimer =
			Timer(100) {
				// 10 Hz update rate
				refresh()
			}
		updateTimer?.start()
	}

	/**
	 * Shows the simulated time of the run in [ControlPanel] (Issue #1421). EDT only.
	 *
	 * [pacedTime] moves smoothly between events where the run paces itself; [tracker] covers what
	 * pacing cannot see (a run without pacing). [finalPacedTime] feeds in the run's final paced
	 * position captured at a STOPPED transition, after the controller has already cleared the
	 * runner (PR #1430 review); `0.0` is the neutral value under the ratchet.
	 *
	 * The shown time never goes backward within one run.
	 */
	fun refresh(finalPacedTime: Double = 0.0) {
		val reported = tracker?.lastSimTime ?: 0.0
		shown = maxOf(shown, finalPacedTime, pacedTime(), reported)
		controlPanel.updateTime(shown)
	}

	/**
	 * Stop the update timer and clean up resources (Issue #205).
	 *
	 * Prevents memory leaks when switching between contexts or exiting simulation mode.
	 * Safe to call multiple times or when timer is not running.
	 *
	 * **Must be called from EDT.**
	 */
	fun stopUpdates() {
		updateTimer?.stop()
		updateTimer = null
	}
}
