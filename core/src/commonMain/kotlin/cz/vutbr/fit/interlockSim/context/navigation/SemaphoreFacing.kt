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
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

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
 * Shared by [RouteSignalConfigurator] (G4/G8 and the intermediate-signal rule of Issue #566), by
 * [PathInfoStore]'s PathInfo trim (Issue #1067 gap 2) and, through [semaphoreFacesNextBlockOrOpen], by
 * [RouteCandidateScreen]'s Step 1.4 START screen (Issue #1378), so all of them agree on which way a
 * signal faces.
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

/**
 * [semaphoreFacesNextBlock] for a semaphore route START, failing open twice: `true` when
 * [environment] is not a [SimulationContext] (nothing to resolve segments with), and `true` when the
 * check throws because [nextBlock] is unexpectedly not adjacent to [semaphore] ("cannot resolve ->
 * proceed, never strand the route").
 *
 * The single START facing rule (Issue #1378): [RouteSignalConfigurator] uses it for the G4 check of a
 * fresh candidate and the already-owned SKIP arm, and [RouteCandidateScreen.rearFacingStartReason] uses
 * it for the Step 1.4 screen, so the screen and the defence-in-depth checks behind it always agree.
 *
 * @since Issue #1378 (the body of RouteSignalConfigurator's `startFacesTravelDirection`, which now
 *   delegates here)
 */
internal fun semaphoreFacesNextBlockOrOpen(
	environment: SimulationEnvironment,
	semaphore: DynamicRailSemaphore,
	nextBlock: DynamicTrackBlock
): Boolean {
	val context = environment as? SimulationContext ?: return true
	return try {
		semaphoreFacesNextBlock(context, semaphore, nextBlock)
	} catch (e: Exception) {
		logger.debug(e) {
			"reservePath: Could not resolve whether START semaphore ${semaphore.name} faces " +
				"$nextBlock (unexpectedly not adjacent); treating as facing " +
				"the travel direction"
		}
		true
	}
}
