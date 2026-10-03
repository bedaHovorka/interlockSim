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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

/**
 * Resolves a named endpoint to its [DynamicPathSeparator], checking [inOutByName] first and then
 * [semaphoreByName]. Returns `null` if the name is neither an InOut nor a Semaphore of the network.
 *
 * Partial paths (InOut to Semaphore or Semaphore to InOut) are valid in addition to the full
 * end-to-end (InOut to InOut) form, so both element types are accepted.
 *
 * Shared by `DefaultInterlockingFacade` and `DefaultNetworkActuatorPort` (issue #701).
 */
internal fun resolveEndpoint(
	name: String,
	inOutByName: Map<String, DynamicInOut>,
	semaphoreByName: Map<String, DynamicRailSemaphore>
): DynamicPathSeparator? = inOutByName[name] ?: semaphoreByName[name]
