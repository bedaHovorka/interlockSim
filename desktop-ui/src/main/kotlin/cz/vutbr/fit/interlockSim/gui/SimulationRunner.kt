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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationController
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Wall-clock throttling wrapper and [SimulationController] implementation.
 *
 * Implements Goal 8 pause/step/speed control on top of [SimulationContext.run].
 * Speed only affects the rate at which simulation events are observed in
 * wall-clock time; the event sequence, timestamps, and physics remain
 * identical to an unthrottled run.
 *
 * Threading model:
 *  - [start] launches a dedicated simulation thread that invokes [context.run].
 *  - The kDisco dispatcher is single-threaded; Swing/EDT may read/write
 *    [speedMultiplier] and [isPaused] concurrently with the simulation thread.
 *    [speedMultiplier] is `@Volatile`; [isPaused] and step-request flags are
 *    guarded by [lock].
 *  - [throttle] and [awaitIfPaused] are called from the simulation thread only,
 *    via the [SimulationContext.run] `beforeEvent` hook.
 */
class SimulationRunner(
	private val context: SimulationContext
) : SimulationController {
	private val pcs = PropertyChangeSupport(this)
	private val lifecycleLock = Any()

	/** Expose the context for dispatcher integration (SP2b.6, Issue #561) */
	val simulationContext: SimulationContext get() = context

	/** Single lock guarding pausedBacking, stepEventRequested, and stepTimeRequested. */
	private val lock = Object()

	private var stepEventRequested: Boolean = false

	private var stepTimeRequested: Double? = null

	@Volatile
	private var stepTimeDeltaBacking: Double = DEFAULT_STEP_TIME_DELTA

	/**
	 * Time delta used by [requestStepTime] when advancing the simulation by a
	 * fixed amount of simulation time.
	 *
	 * Valid range: [MIN_STEP_TIME_DELTA]..[MAX_STEP_TIME_DELTA]. Values outside
	 * the range throw [IllegalArgumentException].
	 */
	var stepTimeDelta: Double
		get() = stepTimeDeltaBacking
		set(value) {
			require(value in MIN_STEP_TIME_DELTA..MAX_STEP_TIME_DELTA) {
				"stepTimeDelta must be in [$MIN_STEP_TIME_DELTA..$MAX_STEP_TIME_DELTA], got: $value"
			}
			stepTimeDeltaBacking = value
		}

	@Volatile
	private var simThread: Thread? = null

	@Volatile
	private var speedMultiplierBacking: Double = DEFAULT_SPEED

	private var pausedBacking: Boolean = false

	/**
	 * Wall-clock speed multiplier. 1.0 = real-time (simulation seconds
	 * elapse in wall-clock seconds); 2.0 = twice as fast; 0.5 = half speed.
	 *
	 * Valid range: [MIN_SPEED]..[MAX_SPEED]. Values outside the range
	 * throw [IllegalArgumentException].
	 *
	 * Fires a [PropertyChangeSupport] event with name [PROP_SPEED_MULTIPLIER]
	 * on change.
	 */
	var speedMultiplier: Double
		get() = speedMultiplierBacking
		set(value) {
			require(value in MIN_SPEED..MAX_SPEED) {
				"speedMultiplier must be in [$MIN_SPEED..$MAX_SPEED], got: $value"
			}
			val old = speedMultiplierBacking
			if (old != value) {
				speedMultiplierBacking = value
				pcs.firePropertyChange(PROP_SPEED_MULTIPLIER, old, value)
			}
		}

	/**
	 * Pause flag. When true, [awaitIfPaused] blocks the simulation thread
	 * without advancing simulation time. When set back to false, any blocked
	 * thread is released.
	 *
	 * Fires a [PropertyChangeSupport] event with name [PROP_IS_PAUSED]
	 * on change.
	 */
	@get:JvmName("isPausedProp")
	var isPaused: Boolean
		get() = synchronized(lock) { pausedBacking }
		set(value) {
			val old: Boolean
			synchronized(lock) {
				old = pausedBacking
				if (old == value) return
				pausedBacking = value
				if (!value) lock.notifyAll()
			}
			pcs.firePropertyChange(PROP_IS_PAUSED, old, value)
		}

	fun requestStepEvent() {
		val oldEvent: Boolean
		val oldTime: Double?
		synchronized(lock) {
			oldEvent = stepEventRequested
			oldTime = stepTimeRequested
			stepEventRequested = true
			stepTimeRequested = null
			lock.notifyAll()
		}
		if (!oldEvent) pcs.firePropertyChange(PROP_STEP_EVENT_REQUESTED, false, true)
		if (oldTime != null) pcs.firePropertyChange(PROP_STEP_TIME_REQUESTED, oldTime, null)
	}

	fun requestStepTime(simSeconds: Double) {
		require(simSeconds in MIN_STEP_TIME_DELTA..MAX_STEP_TIME_DELTA) {
			"Step-time delta must be in [$MIN_STEP_TIME_DELTA..$MAX_STEP_TIME_DELTA], got: $simSeconds"
		}
		val oldEvent: Boolean
		val oldTime: Double?
		synchronized(lock) {
			oldEvent = stepEventRequested
			oldTime = stepTimeRequested
			stepTimeRequested = simSeconds
			stepEventRequested = false
			lock.notifyAll()
		}
		if (oldEvent) pcs.firePropertyChange(PROP_STEP_EVENT_REQUESTED, true, false)
		if (oldTime != simSeconds) pcs.firePropertyChange(PROP_STEP_TIME_REQUESTED, oldTime, simSeconds)
	}

	override fun pollStepEvent(): Boolean {
		val r: Boolean
		synchronized(lock) {
			r = stepEventRequested
			if (r) stepEventRequested = false
		}
		if (r) pcs.firePropertyChange(PROP_STEP_EVENT_REQUESTED, true, false)
		return r
	}

	override fun pollStepTime(): Double? {
		val r: Double?
		synchronized(lock) {
			r = stepTimeRequested
			if (r != null) stepTimeRequested = null
		}
		if (r != null) pcs.firePropertyChange(PROP_STEP_TIME_REQUESTED, r, null)
		return r
	}

	/**
	 * Request an immediate pause from the simulation thread (e.g., triggered by a collision warning).
	 *
	 * Thread-safe: may be called from the simulation thread; sets [isPaused] to `true` so that the
	 * next [awaitIfPaused] tick suspends the loop.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	override fun requestPause() {
		isPaused = true
	}

	/**
	 * Release a previously requested pause, allowing the simulation to continue.
	 *
	 * Thread-safe: delegates to the [isPaused] setter, which notifies all waiters under [lock].
	 * Calling this when not paused is a harmless no-op (the setter short-circuits when the value
	 * is already `false`).
	 *
	 * @since Issue #872 (SP2c.26 follow-up I1)
	 */
	override fun requestResume() {
		isPaused = false
	}

	override fun isPaused(): Boolean = synchronized(lock) { pausedBacking }

	/** Register a listener for all property change events. */
	fun addPropertyChangeListener(listener: PropertyChangeListener) {
		pcs.addPropertyChangeListener(listener)
	}

	/** Register a listener for a specific property. */
	fun addPropertyChangeListener(
		propertyName: String,
		listener: PropertyChangeListener
	) {
		pcs.addPropertyChangeListener(propertyName, listener)
	}

	fun removePropertyChangeListener(listener: PropertyChangeListener) {
		pcs.removePropertyChangeListener(listener)
	}

	fun removePropertyChangeListener(
		propertyName: String,
		listener: PropertyChangeListener
	) {
		pcs.removePropertyChangeListener(propertyName, listener)
	}

	/**
	 * Launch the simulation on a dedicated daemon thread. Idempotent: if a
	 * simulation thread is already live, this call is a no-op.
	 */
	fun start() {
		synchronized(lifecycleLock) {
			val existing = simThread
			if (existing != null && existing.isAlive) {
				logger.debug { "start() ignored — simulation thread already alive" }
				return
			}
			val thread =
				Thread(
					@Suppress("TooGenericExceptionCaught") {
						try {
							context.run(controller = this)
						} catch (e: InterruptedException) {
							logger.debug { "Simulation thread interrupted: ${e.message}" }
							Thread.currentThread().interrupt()
						} catch (t: Throwable) {
							logger.error(t) { "Simulation thread terminated unexpectedly" }
						} finally {
							synchronized(lifecycleLock) {
								if (simThread === Thread.currentThread()) {
									simThread = null
								}
							}
						}
					},
					"SimulationRunner-sim"
				)
			thread.isDaemon = true
			simThread = thread
			thread.start()
		}
	}

	/**
	 * Request simulation shutdown. Interrupts the simulation thread and
	 * wakes any paused wait. Safe to call when not started. Idempotent.
	 */
	fun stop() {
		// Interrupt under lifecycleLock only; do NOT hold two locks at once.
		synchronized(lifecycleLock) {
			val thread = simThread
			if (thread != null && thread.isAlive) {
				thread.interrupt()
			}
		}
		// Wake any paused wait OUTSIDE lifecycleLock so we never hold both locks.
		synchronized(lock) { lock.notifyAll() }
		// simThread is cleared by the simulation thread's own finally block
		// once it truly terminates.
	}

	/** True if a simulation thread has been started and is still alive. */
	fun isRunning(): Boolean = simThread?.isAlive == true

	/**
	 * Suspends the simulation coroutine while [isPaused] is true.
	 *
	 * Returns immediately when not paused. Also returns early if a step-event
	 * ([requestStepEvent]) or step-time request ([requestStepTime]) is pending —
	 * the step-event is consumed atomically under [lock]; the step-time value is
	 * left for the caller to read via [pollStepTime].
	 *
	 * Safe to block here: this is always invoked on the dedicated
	 * `SimulationRunner-sim` thread via a `runBlocking` dispatcher, so
	 * [Object.wait] never pins a shared coroutine thread pool.
	 *
	 * All three mutable fields ([pausedBacking], [stepEventRequested],
	 * [stepTimeRequested]) are guarded by [lock]. Evaluating the wait condition
	 * and calling [Object.wait] happen under the same lock, so notifications from
	 * [requestStepEvent], [requestStepTime], or the [isPaused] setter can never
	 * be lost between the condition check and the [Object.wait] call.
	 */
	override suspend fun awaitIfPaused() {
		var consumedStep = false
		synchronized(lock) {
			while (pausedBacking && !stepEventRequested && stepTimeRequested == null) {
				try {
					lock.wait()
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					return
				}
			}
			// Consume a pending step-event atomically while still holding the lock,
			// eliminating the TOCTOU window that existed when consumption was deferred
			// outside the synchronized block.
			if (stepEventRequested) {
				stepEventRequested = false
				consumedStep = true
			}
		}
		// Fire property-change event outside the lock — pcs callbacks must not
		// be invoked while holding any internal lock.
		if (consumedStep) pcs.firePropertyChange(PROP_STEP_EVENT_REQUESTED, true, false)
		// stepTimeRequested is intentionally left unconsumed; the beforeEvent
		// lambda reads it via pollStepTime() to compute the step-time window.
	}

	/**
	 * Sleep wall-clock time proportional to the simulation delta scaled by
	 * [speedMultiplier]. Called from the `beforeEvent` hook after [awaitIfPaused]
	 * has already handled any pause state for this cycle.
	 *
	 * @param simDeltaSeconds Simulation time advanced since the previous
	 *     tick, in seconds. Zero or negative values are a no-op.
	 */
	@Throws(InterruptedException::class)
	override fun throttle(simDeltaSeconds: Double) {
		if (simDeltaSeconds <= 0.0) return
		val speed = speedMultiplierBacking
		val sleepMs = Math.round(simDeltaSeconds / speed * MILLIS_PER_SECOND)
		if (sleepMs > 0) {
			Thread.sleep(sleepMs)
		}
	}

	companion object {
		const val MIN_SPEED: Double = 0.1
		const val MAX_SPEED: Double = 100.0
		const val DEFAULT_SPEED: Double = 1.0

		const val MIN_STEP_TIME_DELTA: Double = 0.001
		const val MAX_STEP_TIME_DELTA: Double = 60.0
		const val DEFAULT_STEP_TIME_DELTA: Double = 1.0

		const val PROP_SPEED_MULTIPLIER: String = "speedMultiplier"
		const val PROP_IS_PAUSED: String = "isPaused"
		const val PROP_STEP_EVENT_REQUESTED: String = "stepEventRequested"
		const val PROP_STEP_TIME_REQUESTED: String = "stepTimeRequested"

		private const val MILLIS_PER_SECOND: Double = 1000.0

		private val logger = KotlinLogging.logger {}
	}
}
