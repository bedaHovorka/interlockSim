/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	SimulationController — simulation lifecycle logic extracted from Frame (Issue #189)
*/

package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.SwingUtilities

/**
 * Manages the simulation lifecycle on behalf of [Frame] (Issue #189).
 *
 * Encapsulates all simulation-runner state so that the logic can be unit-tested
 * independently of the Swing [Frame] window hierarchy (which requires a display).
 *
 * ## Responsibilities
 * - Creating and owning the [SimulationRunner] instance
 * - Starting the runner synchronously (before the monitor thread) to prevent the
 *   race condition where [stop] is called before the monitor thread starts the runner
 * - Polling for completion on a daemon "SimulationMonitor" thread
 * - Enabling/disabling the Stop button in [ControlPanel] as the lifecycle changes
 * - Dispatching [onCompleted] back to EDT when the simulation finishes naturally
 *
 * ## Thread Safety
 * - [start] and [stop] are intended to be called from the Event Dispatch Thread (EDT).
 *   [Frame] enforces this via its own EDT guards.
 * - [runner] is `@Volatile` so the monitor thread reads a fresh value when [stop]
 *   nulls it.
 * - [onCompleted] is always dispatched to EDT via [SwingUtilities.invokeLater].
 *
 * @param controlPanel ControlPanel whose Stop button and status label are managed here.
 * @param onCompleted Callback invoked on EDT when the simulation finishes naturally.
 *   Defaults to a no-op if not provided.
 * @since 2026-04-20 (extracted from Frame for testability)
 * @see Frame
 */
internal class SimulationController(
	private val controlPanel: ControlPanel,
	private val onCompleted: () -> Unit = {},
) {
	/**
	 * The currently active runner, or `null` when no simulation is running.
	 *
	 * `@Volatile` ensures the monitor thread reads a fresh value after [stop] nulls it.
	 */
	@Volatile
	var runner: SimulationRunner? = null
		private set

	/**
	 * Start the simulation for [context].
	 *
	 * Idempotent: if a simulation is already running this is a no-op.
	 *
	 * Steps:
	 * 1. Creates [SimulationRunner] wrapping [context].
	 * 2. Calls [SimulationRunner.start] **synchronously** (before the monitor thread) to
	 *    eliminate the race condition where [stop] could be invoked before the monitor
	 *    thread has a chance to start the runner.
	 * 3. Updates [ControlPanel] status to "Running" and enables the Stop button.
	 * 4. Launches a daemon "SimulationMonitor" thread that polls [SimulationRunner.isRunning]
	 *    and on completion dispatches [onCompleted] and resets the panel via EDT.
	 *
	 * @param context The simulation context to run.
	 */
	fun start(context: SimulationContext) {
		val existing = runner
		if (existing != null && existing.isRunning()) {
			logger.debug { "start ignored — simulation already running" }
			return
		}

		val newRunner = SimulationRunner(context)
		runner = newRunner

		// Start synchronously BEFORE enabling the Stop button or launching the monitor
		// thread. This ensures stopSimulation() always has a live thread to interrupt.
		newRunner.start()

		controlPanel.updateStatus("Running")
		controlPanel.setStopEnabled(true)

		val monitorThread =
			Thread(
				{
					try {
						while (newRunner.isRunning()) {
							Thread.sleep(SIMULATION_POLL_INTERVAL_MS)
						}
					} catch (e: InterruptedException) {
						Thread.currentThread().interrupt()
					} finally {
						SwingUtilities.invokeLater {
							controlPanel.updateStatus("Stopped")
							controlPanel.setStopEnabled(false)
							onCompleted()
						}
					}
				},
				"SimulationMonitor"
			)
		monitorThread.isDaemon = true
		monitorThread.start()
	}

	/**
	 * Stop a running simulation and reset the [ControlPanel].
	 *
	 * Safe to call when no simulation is running (no-op in that case).
	 */
	fun stop() {
		val r = runner ?: return
		r.stop()
		runner = null
		controlPanel.setStopEnabled(false)
		controlPanel.updateStatus("Stopped")
	}

	/** Returns `true` while the underlying [SimulationRunner] reports running. */
	fun isRunning(): Boolean = runner?.isRunning() ?: false

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Poll interval (ms) for the monitor thread to detect simulation completion. */
		internal const val SIMULATION_POLL_INTERVAL_MS: Long = 500L
	}
}
