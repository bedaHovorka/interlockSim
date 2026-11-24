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
 *   folded in the NaN and try/catch variants; issue #1123 made it public so `:desktop-ui`'s
 *   `AnimationStateCapture` shares it instead of keeping its own try/catch copy.
 */
fun currentSimulationTime(fallback: Double = 0.0): Double = currentSimulationTimeOrNull() ?: fallback

/**
 * Returns the current simulation time, or `null` when no authoritative timestamp exists on the
 * calling thread — off a running simulation [Process.time] fails and this catches it, like
 * [currentSimulationTime], but reports "no reading" instead of a fallback value (issue #1421, PR
 * #1430 review: the desktop pacing publishes a gap only with an authoritative timestamp, so an
 * off-thread wall-paced sleep can no longer invent simulation time).
 *
 * `runCatching` catches every [Throwable]; [Process.time] throws only a `DiscoException` outside a run.
 */
fun currentSimulationTimeOrNull(): Double? = runCatching { Process.time() }.getOrNull()
