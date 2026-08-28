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

/**
 * Canonical wall-clock speed-multiplier bounds and range validation shared by all
 * [SimulationController] pacing implementations.
 *
 * The throttle *computation* itself is intentionally NOT centralised here:
 * [SimulationRunner] (`:desktop-ui`) rounds with `java.lang.Math.round` (round-half-up,
 * JVM-only) and sleeps via `Thread.sleep`, while [ThrottlingSimulationController]
 * (`:core`/commonMain) rounds with `kotlin.math.roundToLong` (round-half-to-even)
 * and sleeps via the KMP `platformSleep` expect/actual. Those two rounding modes
 * differ at exact half-millisecond boundaries, so unifying the formula would
 * silently change one implementation's sleep durations. Each class keeps its own
 * `throttle()` body; only the bounds and the range validator are shared here.
 *
 * @since Issue #873 (SP2c.26 follow-up I2 — headless pacing controller)
 */
object SimulationPacing {
	/** Minimum valid wall-clock speed multiplier. */
	const val MIN_SPEED: Double = 0.1

	/** Maximum valid wall-clock speed multiplier. */
	const val MAX_SPEED: Double = 100.0

	/** Default speed multiplier (real-time). */
	const val DEFAULT_SPEED: Double = 1.0

	/** Milliseconds per second — the wall-clock/sim-time conversion factor used by `throttle()`. */
	const val MILLIS_PER_SECOND: Double = 1000.0

	/**
	 * Validates that [value] is within [[MIN_SPEED]..[MAX_SPEED]].
	 *
	 * @throws IllegalArgumentException if [value] is outside the valid range.
	 */
	fun requireSpeedMultiplier(value: Double) {
		require(value in MIN_SPEED..MAX_SPEED) {
			"speedMultiplier must be in [$MIN_SPEED..$MAX_SPEED], got: $value"
		}
	}
}
