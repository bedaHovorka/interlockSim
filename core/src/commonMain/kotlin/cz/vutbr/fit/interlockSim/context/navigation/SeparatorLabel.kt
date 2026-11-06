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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Name of [separator] for diagnostics, falling back to its `toString()` when it has none.
 *
 * Shared by [DefaultPathReservationService] and [RouteCandidateScreen], whose denial reasons and
 * log lines must name a separator the same way.
 */
internal fun separatorLabel(separator: PathSeparator): String =
	when (separator) {
		is DynamicRailSemaphore -> separator.name.takeIf { it.isNotBlank() }
		is DynamicInOut -> separator.name.takeIf { it.isNotBlank() }
		is DynamicRailSwitch -> separator.name.takeIf { it.isNotBlank() }
		else -> null
	} ?: separator.toString()
