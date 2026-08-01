/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.util.platformSleep
import kotlin.math.roundToLong

/**
 * Headless wall-clock throttling [SimulationController].
 *
 * Provides real pacing for console/headless runs where an async dispatcher planner
 * requires a non-no-op controller to satisfy
 * `assertPlannerPacingCompatible` (dispatcher-agent module).
 *
 * This controller throttles the simulation using wall-clock delay proportional to
 * the simulation time delta and [speedMultiplier]. It never pauses or blocks on
 * step requests — those are GUI concerns.
 *
 * ## Relationship to `SimulationRunner`
 *
 * `SimulationRunner` (`:desktop-ui`) is GUI-*located* but not GUI-*coupled*; it carries
 * [java.beans.PropertyChangeSupport], lifecycle thread management, pause/step state, and
 * speed-change notifications. This class extracts only the throttling core — the part
 * required to make the pacing guard pass without `:desktop-ui` on the classpath.
 *
 * Prefer `SimulationRunner` when you also need pause/step/speed-change notifications
 * (GUI runs). Use `ThrottlingSimulationController` when you only need the
 * `assertPlannerPacingCompatible` guard to pass and the simulation-clock pacing for
 * the agent driver loop (headless/console runs with an async dispatcher planner).
 *
 * ## Resolves R8 (Issue #822)
 *
 * Before this class, `assertPlannerPacingCompatible` rejected async planners bound to
 * `NoOpSimulationController` on all headless runs — the only reachable option for
 * console examples. `ThrottlingSimulationController` closes that gap without weakening
 * the guard and without duplicating `SimulationRunner`'s full GUI-lifecycle logic.
 *
 * @param initialSpeedMultiplier Initial wall-clock speed multiplier (1.0 = real-time,
 *   2.0 = twice as fast). Must be in [[MIN_SPEED]..[MAX_SPEED]].
 * @throws IllegalArgumentException if [initialSpeedMultiplier] is outside the valid range.
 *
 * @since Issue #873 (SP2c.26 follow-up I2 — headless pacing controller)
 */
class ThrottlingSimulationController(
	initialSpeedMultiplier: Double = DEFAULT_SPEED
) : SimulationController {
	@kotlin.concurrent.Volatile
	private var speedMultiplierBacking: Double

	init {
		require(initialSpeedMultiplier in MIN_SPEED..MAX_SPEED) {
			"speedMultiplier must be in [$MIN_SPEED..$MAX_SPEED], got: $initialSpeedMultiplier"
		}
		speedMultiplierBacking = initialSpeedMultiplier
	}

	/**
	 * Wall-clock speed multiplier. 1.0 = real-time; 2.0 = twice as fast; 0.5 = half speed.
	 *
	 * Valid range: [MIN_SPEED]..[MAX_SPEED]. Values outside the range
	 * throw [IllegalArgumentException].
	 */
	var speedMultiplier: Double
		get() = speedMultiplierBacking
		set(value) {
			require(value in MIN_SPEED..MAX_SPEED) {
				"speedMultiplier must be in [$MIN_SPEED..$MAX_SPEED], got: $value"
			}
			speedMultiplierBacking = value
		}

	/** Headless: never paused, returns immediately. */
	override suspend fun awaitIfPaused() {
		// No-op: headless runs are never paused
	}

	/**
	 * Applies wall-clock throttling proportional to [simDeltaSeconds] and [speedMultiplier].
	 *
	 * Delegates to [platformSleep] so the pacing logic works on both JVM and native targets.
	 * Zero or negative deltas are a no-op.
	 */
	override fun throttle(simDeltaSeconds: Double) {
		if (simDeltaSeconds <= 0.0) return
		val sleepMs = (simDeltaSeconds / speedMultiplierBacking * MILLIS_PER_SECOND).roundToLong()
		if (sleepMs > 0) {
			platformSleep(sleepMs)
		}
	}

	/** Headless: never paused. */
	override fun isPaused(): Boolean = false

	/** Headless: no step-event support; always returns `false`. */
	override fun pollStepEvent(): Boolean = false

	/** Headless: no step-time support; always returns `null`. */
	override fun pollStepTime(): Double? = null

	/** Headless: pause requests are silently ignored. */
	override fun requestPause() {
		// No-op: headless runs do not support interactive pause
	}

	/** Headless: never paused, so resume requests are silently ignored. */
	override fun requestResume() {
		// No-op: headless runs are never paused
	}

	companion object {
		/** Minimum valid speed multiplier. */
		const val MIN_SPEED: Double = 0.1

		/** Maximum valid speed multiplier. */
		const val MAX_SPEED: Double = 100.0

		/** Default speed (real-time). */
		const val DEFAULT_SPEED: Double = 1.0

		private const val MILLIS_PER_SECOND: Double = 1000.0
	}
}
