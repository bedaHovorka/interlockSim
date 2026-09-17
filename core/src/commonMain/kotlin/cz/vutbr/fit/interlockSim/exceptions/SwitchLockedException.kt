package cz.vutbr.fit.interlockSim.exceptions

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Thrown when [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch.setUpPath] would move a
 * switch that is locked in a different position -- safety property SI-5: a switch cannot toggle
 * while a train route runs through it.
 *
 * A distinct subclass of [PathSeparatorChangeException] (not that class itself), so a caller such
 * as [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService.configureSwitchesInPath]
 * can catch it FIRST and tell it apart from the #742 case that same superclass already reports
 * (no [Conf] joins the route's segments -- a permanent, geometric impossibility). This condition is
 * the opposite: transient contention that resolves once the other route releases the switch
 * (Issue #1065).
 *
 * @property heldConf The switch's current, locked configuration.
 * @property requiredConf The configuration the candidate route needs.
 */
class SwitchLockedException(
	message: String,
	obj: PathSeparator,
	val heldConf: Conf,
	val requiredConf: Conf
) : PathSeparatorChangeException(message, obj)
