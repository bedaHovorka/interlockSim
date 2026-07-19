/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	SimulationController — simulation lifecycle logic extracted from Frame (Issue #189)
*/

package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.sim.SpeedControllable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeListener

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
 * - Reporting lifecycle and speed changes via callbacks
 *
 * ## Thread Safety
 * - [start] and [stop] are designed to be called from the same thread. They are NOT
 *   thread-safe for concurrent calls from different threads; external callers are
 *   responsible for serialization.
 * - [runner] is `@Volatile` so the monitor thread reads a fresh value when [stop]
 *   nulls it.
 * - Callbacks are invoked on whichever thread performs the lifecycle change.
 *
 * @param onStateChanged Callback for lifecycle state updates. Invoked on the same
 *   thread that performs the state change (caller thread for [start]/[stop], monitor
 *   thread for natural completion).
 * @param onSpeedChanged Callback for speed indicator updates. Invoked on the thread
 *   that emits the speed change; callers are responsible for EDT marshalling as needed.
 * @param onCompleted Callback invoked when the simulation finishes naturally on the
 *   monitor thread. Defaults to a no-op.
 * @since 2026-04-20 (extracted from Frame for testability)
 * @see Frame
 */
internal class SimulationController(
	private val onStateChanged: (SimulationStatus) -> Unit = {},
	private val onSpeedChanged: (Double) -> Unit = {},
	private val onCompleted: () -> Unit = {}
) {
	/**
	 * The currently active runner, or `null` when no simulation is running.
	 *
	 * `@Volatile` ensures the monitor thread reads a fresh value after [stop] nulls it.
	 */
	@Volatile
	var runner: SimulationRunner? = null
		private set

	/** Listener registered on the active runner for speed changes; removed on stop. */
	private var speedListener: PropertyChangeListener? = null

	/**
	 * Reference to the running main process when it implements [SpeedControllable]
	 * (e.g. [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]). `setSpeed` propagates
	 * speed changes here so the simulation thread's wall-clock pacing actually
	 * tracks the GUI controls — without this link the runner-side `speedMultiplier`
	 * is dead-state w.r.t. RealTimeSynch.
	 */
	@Volatile
	private var speedControllable: SpeedControllable? = null

	/**
	 * SP4.2 (Issue #564): the context-scoped [DelegatingSimulationController] that paces
	 * the dispatcher-agent loop. While a simulation is running, its delegate is the live
	 * [SimulationRunner] so the agent loop follows the existing real-time sync (speed
	 * multiplier, pause); on stop/completion the delegate is reset to
	 * [NoOpSimulationController]. `null` when the context has no dispatcher agent wired.
	 */
	@Volatile
	private var agentPacing: DelegatingSimulationController? = null

	/**
	 * Desired speed multiplier applied to new and currently running simulations.
	 *
	 * Stored so that a speed selection before [start] is honoured once the runner is created.
	 */
	private var desiredSpeed: Double = SimulationRunner.DEFAULT_SPEED

	/**
	 * Current effective speed: the live runner's speed when a simulation is running,
	 * otherwise the stored [desiredSpeed] that will be applied on the next [start].
	 */
	val speed: Double get() = runner?.speedMultiplier ?: desiredSpeed

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
	 * 3. Emits [SimulationStatus.RUNNING] via [onStateChanged].
	 * 4. Launches a daemon "SimulationMonitor" thread that polls [SimulationRunner.isRunning]
	 *    and on completion emits [SimulationStatus.STOPPED] and invokes [onCompleted].
	 *
	 * @param context The simulation context to run.
	 */
	fun start(context: SimulationContext) {
		val existing = runner
		if (existing != null && existing.isRunning()) {
			logger.debug { "start ignored — simulation already running" }
			return
		}

		// Clean up any stale speed listener from a previous run that finished naturally
		// (monitor thread may still be in its finally block when we get here).
		if (existing != null && !existing.isRunning()) {
			cleanupSpeedListener(existing)
		}

		val newRunner = SimulationRunner(context)
		newRunner.speedMultiplier = desiredSpeed
		runner = newRunner
		val mainProcess = (context as? DefaultSimulationContext)?.getMainProcess()
		val controllable = mainProcess as? SpeedControllable
		speedControllable = controllable
		controllable?.speedMultiplier = desiredSpeed

		// SP4.2 (Issue #564): pace the dispatcher-agent loop with this run's runner.
		// getOrNull: the binding is absent in Koin setups without :dispatcher-agent's module.
		// The ClassCastException guard covers test doubles for SimulationContext whose scope
		// isn't backed by a real Koin registry (e.g. a relaxed mock) — treated the same as
		// "no dispatcher agent wired" since pacing is an optional seam either way.
		val pacing =
			try {
				(context as? DefaultSimulationContext)?.scope?.getOrNull<DelegatingSimulationController>()
			} catch (e: ClassCastException) {
				null
			}
		agentPacing = pacing
		pacing?.delegate = newRunner

		// Start synchronously BEFORE enabling the Stop button or launching the monitor
		// thread. This ensures stopSimulation() always has a live thread to interrupt.
		newRunner.start()

		// Wire speed callback for SimulationRunner speed changes.
		// The listener is removed when the simulation stops (in stop() or monitor finally).
		val listener =
			PropertyChangeListener { evt ->
				val multiplier = evt.newValue as? Double
				if (multiplier == null) {
					logger.debug { "Ignoring unexpected ${SimulationRunner.PROP_SPEED_MULTIPLIER} value: ${evt.newValue}" }
					return@PropertyChangeListener
				}
				onSpeedChanged(multiplier)
			}
		speedListener = listener
		newRunner.addPropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, listener)
		onSpeedChanged(newRunner.speedMultiplier)

		onStateChanged(SimulationStatus.RUNNING)

		launchMonitorThread(newRunner)
	}

	/**
	 * Launch a daemon "SimulationMonitor" thread that polls [newRunner] for completion
	 * and dispatches callback notifications when done.
	 *
	 * Guards against stale-monitor by checking `runner === newRunner` before mutating
	 * state so that a stop+start cycle cannot clobber the new run's state.
	 */
	private fun launchMonitorThread(newRunner: SimulationRunner) {
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
						// Guard against stale-monitor: if stop() + start(ctxB) ran before
						// this callback executes, runner has been replaced with a new instance.
						// Skip reset to avoid clobbering the new run's state.
						if (runner === newRunner) {
							cleanupSpeedListener(newRunner)
							runner = null
							speedControllable = null
							detachAgentPacing()
							onSpeedChanged(SimulationRunner.DEFAULT_SPEED)
							onStateChanged(SimulationStatus.STOPPED)
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
	 * Stop a running simulation and emit [SimulationStatus.STOPPED].
	 *
	 * Safe to call when no simulation is running (no-op in that case).
	 */
	fun stop() {
		val r = runner ?: return
		cleanupSpeedListener(r)
		r.stop()
		runner = null
		speedControllable = null
		detachAgentPacing()
		onSpeedChanged(SimulationRunner.DEFAULT_SPEED)
		onStateChanged(SimulationStatus.STOPPED)
	}

	/**
	 * Detach the live runner from the agent-pacing seam (SP4.2, Issue #564).
	 *
	 * Resets the delegate to [NoOpSimulationController] so a still-draining agent-driver
	 * thread never throttles against a stopped runner, and clears the local reference.
	 */
	private fun detachAgentPacing() {
		agentPacing?.delegate = NoOpSimulationController
		agentPacing = null
	}

	/** Removes the speed [PropertyChangeListener] from [r] and clears the reference. */
	private fun cleanupSpeedListener(r: SimulationRunner) {
		speedListener?.let { r.removePropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, it) }
		speedListener = null
	}

	/** Returns `true` while the underlying [SimulationRunner] reports running. */
	fun isRunning(): Boolean = runner?.isRunning() ?: false

	/**
	 * Set the simulation speed multiplier.
	 *
	 * Applied immediately to the currently running simulation (if any) and stored
	 * so it is also honoured by the next [start] call.
	 *
	 * @param multiplier Speed factor in [SimulationRunner.MIN_SPEED]..[SimulationRunner.MAX_SPEED].
	 * @throws IllegalArgumentException if [multiplier] is outside the valid range.
	 */
	fun setSpeed(multiplier: Double) {
		require(multiplier in SimulationRunner.MIN_SPEED..SimulationRunner.MAX_SPEED) {
			"speedMultiplier must be in [${SimulationRunner.MIN_SPEED}..${SimulationRunner.MAX_SPEED}], got: $multiplier"
		}
		desiredSpeed = multiplier
		runner?.speedMultiplier = multiplier
		speedControllable?.speedMultiplier = multiplier
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Poll interval (ms) for the monitor thread to detect simulation completion. */
		internal const val SIMULATION_POLL_INTERVAL_MS: Long = 100L
	}

	/** Simulation lifecycle states emitted via [onStateChanged]. */
	enum class SimulationStatus {
		RUNNING,
		STOPPED
	}
}
