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

/**
 * Implemented by simulation processes whose wall-clock pacing can be retuned at runtime.
 *
 * Implementations MUST treat the property as thread-safe: reads happen on the kDisco
 * simulation thread (e.g. inside a real-time-sync loop), writes happen on the EDT
 * (or any thread driving GUI controls / keyboard shortcuts). Use `@kotlin.concurrent.Volatile`
 * on the backing field.
 *
 * Range validation is left to the implementation; the canonical bounds used by the
 * desktop GUI live on `SimulationRunner.MIN_SPEED`/`MAX_SPEED`.
 */
interface SpeedControllable {
	/** Wall-clock speed multiplier. 1.0 = real-time, 2.0 = twice as fast, 0.5 = half-speed. */
	var speedMultiplier: Double
}
