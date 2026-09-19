/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * `true` when a train leaving [semaphore] into [nextBlock] finds the signal facing its direction of
 * travel, `false` when the train would pass it from behind.
 *
 * `getSegment(separator, X, null)` is the separator's segment on X's side, so this is the segment the
 * train is heading TOWARDS. When [context] cannot resolve that segment the check fails open (`true`):
 * an unexpected topology degrades to the old "treat it as facing" behaviour rather than stranding a
 * route at STOP.
 *
 * [nextBlock] must bound [semaphore]; for a non-adjacent pair `DefaultSimulationContext.getSegment`
 * throws instead of returning `null`.
 *
 * Shared by the reservation service (G4/G8 and the intermediate-signal rule of Issue #566) and by the
 * registry's PathInfo trim (Issue #1067 gap 2), so both always agree on which way a signal faces.
 *
 * @since Issue #1067
 */
internal fun semaphoreFacesNextBlock(
	context: SimulationContext,
	semaphore: DynamicRailSemaphore,
	nextBlock: DynamicTrackBlock
): Boolean {
	val towards = context.getSegment(semaphore, nextBlock, null) ?: return true
	return towards == semaphore.direction()
}
