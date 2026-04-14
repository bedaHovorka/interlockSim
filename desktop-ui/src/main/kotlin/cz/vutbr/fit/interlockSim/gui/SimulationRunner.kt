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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Wall-clock throttling wrapper around [SimulationContext.run].
 *
 * Phase 1.1 of Goal 7 (Issue #188). Provides speed control and pause support
 * without altering simulation semantics. Speed only affects the rate at which
 * simulation events are observed in wall-clock time; the event sequence,
 * timestamps, and physics remain identical to an unthrottled run.
 *
 * Threading model:
 *  - [start] launches a dedicated simulation thread that invokes [context.run].
 *  - The kDisco dispatcher is single-threaded; Swing/EDT may read/write
 *    [speedMultiplier] and [isPaused] concurrently with the simulation thread,
 *    so both are `@Volatile`.
 *  - [throttle] and [awaitIfPaused] are called from the simulation thread only.
 *
 * Not wired into Main/Frame by this change — that is Issue #189's scope.
 *
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/188">Issue #188</a>
 */
class SimulationRunner(
	private val context: SimulationContext
) {
	private val pcs = PropertyChangeSupport(this)
	private val lifecycleLock = Any()
	private val pauseLock = Object()

	@Volatile
	private var simThread: Thread? = null

	@Volatile
	private var speedMultiplierBacking: Double = DEFAULT_SPEED

	@Volatile
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
	var isPaused: Boolean
		get() = pausedBacking
		set(value) {
			val old: Boolean
			synchronized(pauseLock) {
				old = pausedBacking
				if (old == value) return
				pausedBacking = value
				if (!value) {
					pauseLock.notifyAll()
				}
			}
			pcs.firePropertyChange(PROP_IS_PAUSED, old, value)
		}

	/** Register a listener for all property change events. */
	fun addPropertyChangeListener(listener: PropertyChangeListener) {
		pcs.addPropertyChangeListener(listener)
	}

	/** Register a listener for a specific property. */
	fun addPropertyChangeListener(propertyName: String, listener: PropertyChangeListener) {
		pcs.addPropertyChangeListener(propertyName, listener)
	}

	fun removePropertyChangeListener(listener: PropertyChangeListener) {
		pcs.removePropertyChangeListener(listener)
	}

	fun removePropertyChangeListener(propertyName: String, listener: PropertyChangeListener) {
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
			val thread = Thread({
				try {
					context.run()
				} catch (e: InterruptedException) {
					logger.debug { "Simulation thread interrupted: ${e.message}" }
					Thread.currentThread().interrupt()
				}
			}, "SimulationRunner-sim")
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
		synchronized(lifecycleLock) {
			val thread = simThread
			// Release any paused wait so the thread can observe interrupt.
			synchronized(pauseLock) {
				pauseLock.notifyAll()
			}
			if (thread != null && thread.isAlive) {
				thread.interrupt()
			}
			simThread = null
		}
	}

	/** True if a simulation thread has been started and is still alive. */
	fun isRunning(): Boolean = simThread?.isAlive == true

	/**
	 * Block the calling thread (expected to be the simulation thread) while
	 * [isPaused] is true. Returns immediately when not paused. Respects
	 * thread interruption.
	 */
	@Throws(InterruptedException::class)
	fun awaitIfPaused() {
		synchronized(pauseLock) {
			while (pausedBacking) {
				pauseLock.wait()
			}
		}
	}

	/**
	 * Sleep wall-clock time proportional to the simulation delta scaled by
	 * [speedMultiplier]. If paused, blocks until resumed before sleeping.
	 *
	 * @param simDeltaSeconds Simulation time advanced since the previous
	 *     tick, in seconds. Must be non-negative. Zero or negative values
	 *     are a no-op.
	 */
	@Throws(InterruptedException::class)
	fun throttle(simDeltaSeconds: Double) {
		awaitIfPaused()
		if (simDeltaSeconds <= 0.0) return
		val speed = speedMultiplierBacking
		val sleepMs = (simDeltaSeconds / speed * MILLIS_PER_SECOND).toLong()
		if (sleepMs > 0) {
			Thread.sleep(sleepMs)
		}
	}

	companion object {
		const val MIN_SPEED: Double = 0.1
		const val MAX_SPEED: Double = 100.0
		const val DEFAULT_SPEED: Double = 1.0

		const val PROP_SPEED_MULTIPLIER: String = "speedMultiplier"
		const val PROP_IS_PAUSED: String = "isPaused"

		private const val MILLIS_PER_SECOND: Double = 1000.0

		private val logger = KotlinLogging.logger {}
	}
}
