/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.ksimulantenbande.kdisco.Process

/**
 * Returns the current simulation time, or [fallback] when called outside a running simulation (for
 * example in unit tests). Uses [Process.time], which is safe to call from any kDisco process.
 *
 * The default `0.0` suits callers that treat "no run" as time zero (snapshots, reservation timestamps);
 * [SimulationException][cz.vutbr.fit.interlockSim.exceptions.SimulationException] passes [Double.NaN]
 * so that "no run" stays distinguishable from a real time.
 *
 * `runCatching` catches every [Throwable]; [Process.time] throws only a `DiscoException` outside a run.
 *
 * @since PR #1115 review -- replaces the identical private copies in `DefaultPathReservationService`,
 *   `PathReservationRegistry` and `DynamicTrackBlock`; issue #701 added the [fallback] parameter and
 *   folded in the NaN and try/catch variants.
 */
internal fun currentSimulationTime(fallback: Double = 0.0): Double =
	runCatching { Process.time() }.getOrDefault(fallback)
