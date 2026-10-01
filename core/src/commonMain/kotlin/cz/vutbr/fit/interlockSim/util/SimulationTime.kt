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
 * Returns the current simulation time, or 0.0 when called outside a running simulation (for example
 * in unit tests). Uses [Process.time], which is safe to call from any kDisco process.
 *
 * @since PR #1115 review -- replaces the identical private copies in `DefaultPathReservationService`,
 *   `PathReservationRegistry` and `DynamicTrackBlock`
 */
internal fun currentSimulationTime(): Double = runCatching { Process.time() }.getOrDefault(0.0)
