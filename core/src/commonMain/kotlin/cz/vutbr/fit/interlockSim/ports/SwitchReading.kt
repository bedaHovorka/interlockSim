/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch

/**
 * Immutable reading of one switch at the instant the planning snapshot was taken (Issue #1468,
 * Goal 9B SP4.2, #1467 spec §7): where it lies, whether it is locked, and for which train.
 *
 * Values only (spec §2): the reading carries no `DynamicRailSwitch` reference, so a routing thread can
 * plan from it without asking the registry (R4). Sampled on the simulation thread by
 * [DefaultNetworkPerceptionPort.captureSnapshot] in the same control step as the rest of the snapshot.
 *
 * @property name The switch's stable name (Issue #1473): its XML name, or the generated
 *   `SW_<x>_<y>` of its grid position (`GeneratedElementName.switchName`) — never blank.
 * @property conf The position, from `DynamicRailSwitch.conf`.
 * @property locked `DynamicRailSwitch.locked`: the switch is held in [conf] and may not be thrown.
 * @property ownerTrainId The train that owns the switch, from `PathReservationRegistry.getSwitchOwner`;
 *   `null` when no train owns it. A locked switch without an owner is an orphan lock (see
 *   `SwitchConfigurator.releaseOrphanSwitchLocks`), reported as it is.
 */
data class SwitchReading(
	val name: String,
	val conf: RailSwitch.Conf,
	val locked: Boolean,
	val ownerTrainId: String?
)
